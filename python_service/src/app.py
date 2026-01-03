# python_service/app.py - Extended Service
from typing import List, Optional
from pydantic import BaseModel, Field, ValidationError
from fastapi import FastAPI, HTTPException
import json, re, urllib.request, os, sys

# Load environment variables
from pathlib import Path
env_file = Path(__file__).parent.parent / ".env"
if env_file.exists():
    try:
        from dotenv import load_dotenv
        load_dotenv(env_file)
        print(f"[INFO] Loaded .env from {env_file}")
    except ImportError:
        print(f"[WARN] dotenv not installed, using os.getenv only")

from pydantic_ai import Agent
from pydantic_ai.models.test import TestModel

# ========== Configuration ==========
USE_OLLAMA = os.getenv("USE_OLLAMA") == "1"
OLLAMA_MODEL_NAME = os.getenv("OLLAMA_MODEL", "qwen2.5:1.5b-instruct")
OLLAMA_BASE = os.getenv("OLLAMA_BASE", "http://localhost:11434")
print(f"OLLAMA status: {USE_OLLAMA} (model {OLLAMA_MODEL_NAME})")

if USE_OLLAMA:
    from pydantic_ai.models.openai import OpenAIChatModel
    from pydantic_ai.providers.ollama import OllamaProvider
    MODEL = OpenAIChatModel(
        model_name=OLLAMA_MODEL_NAME,
        provider=OllamaProvider(base_url=f"{OLLAMA_BASE}/v1"),
    )
else:
    MODEL = TestModel()

# ========== Models ==========
class Plan(BaseModel):
    """Concise plan + final answer."""
    steps: List[str] = Field(description="Steps in order")
    answer: str

class FileAnalysisRequest(BaseModel):
    instruction: str
    filepath: Optional[str] = None  

# ========== Helper Functions ==========
def _to_plan_safe(raw_output) -> Plan:
    if isinstance(raw_output, Plan):
        return raw_output
    if isinstance(raw_output, dict):
        try:
            return Plan.model_validate(raw_output)
        except:
            pass
    if isinstance(raw_output, str):
        try:
            return Plan.model_validate_json(raw_output)
        except ValidationError:
            m = re.search(r"\{.*\}", raw_output, re.S)
            if m:
                try:
                    return Plan.model_validate_json(m.group(0))
                except ValidationError:
                    pass
            return Plan(
                steps=["Model returned unformatted text; applied fallback."],
                answer=raw_output[:500]
            )
    return Plan(steps=["Unknown output; fallback."], answer=str(raw_output)[:500])

def _call_ollama_raw(prompt: str) -> Optional[str]:
    """Call Ollama directly for debugging."""
    if not USE_OLLAMA:
        return None
    try:
        payload = json.dumps({
            "model": OLLAMA_MODEL_NAME,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 512,
        }).encode("utf-8")
        req = urllib.request.Request(
            f"{OLLAMA_BASE}/v1/chat/completions",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.read().decode("utf-8")
    except Exception as e:
        print(f"[DEBUG] Ollama raw call failed: {e}")
        return None

def _parse_plain_text_to_plan(text: str) -> Plan:
    """Fallback parser for non-JSON responses."""
    if text is None:
        return Plan(steps=["No content returned."], answer="")
    
    t = text.replace("\\n", "\n")
    lines = [ln.strip() for ln in t.splitlines() if ln.strip()]
    cleaned_lines = [re.sub(r"\\\[|\\\]|\\\(|\\\)", "", ln) for ln in lines]
    
    # Extract answer
    answer = ""
    for ln in cleaned_lines[::-1]:
        m = re.search(r"=\s*([+-]?\d+(?:\.\d+)?)", ln)
        if m:
            answer = m.group(1)
            break
        m2 = re.search(r"([+-]?\d+(?:\.\d+)?)\s*$", ln)
        if m2:
            answer = m2.group(1)
            break
    
    if not answer and cleaned_lines:
        answer = cleaned_lines[-1][:200]
    
    # Extract steps
    steps = []
    for ln in cleaned_lines:
        if ln.strip() == answer:
            continue
        steps.append(ln)
        if len(steps) >= 6:
            break
    
    if not steps:
        steps = [t[:500]]
    
    return Plan(steps=steps, answer=str(answer))

# ========== Agent & Tools ==========
agent = Agent(
    MODEL,
    output_type=Plan,
    system_prompt=(
        "You are an assistant that analyzes file metadata only. "
        "Return ONLY valid JSON with EXACT schema:"
        "{\"steps\": [\"...\"], \"answer\": \"...\"}"
        "Rules:"
        "- Do NOT read any file."
        "- Do NOT claim you opened or accessed the file."
        "- Infer the file type AND its likely role based on filepath and name and extension."
        "No extra text, markdown, or commentary."
        "Return RAW JSON only."
    ),
    retries=2,
    output_retries=2,
)

# ========== FastAPI App ==========
app = FastAPI(title="PydanticAI Extended Service")

# ========== Existing Endpoint ==========
@app.post("/agent/solve", response_model=Plan)
async def solve(req: FileAnalysisRequest) -> Plan:
    """
    Endpoint used by PythonBridgeAgent.
    IMPORTANT: We do NOT read the file. We only use the filepath string.
    """
    filepath = (req.filepath or "").strip()

    # Build a very explicit prompt so the model doesn't hallucinate reading.
    prompt_parts = [
        "Analyze file TYPE and likely ROLE or PURPOSE based ONLY on the filepath string.",
        "Rules:",
        "- Do not read the file.",
        "- Do not claim you opened the file.",
        "- Base your answer on extension/name only.",
        "",
        f"Instruction: {req.instruction.strip()}",
        f"Filepath: {filepath if filepath else '[missing]'}",
        "",
        "Return JSON only in this exact format:",
        "{\"steps\": [\"...\"], \"answer\": \"...\"}",
        "IMPORTANT:",
        "- Do NOT use Markdown",
        "- Do NOT use code blocks",
        "- Do NOT wrap output in ``` or ```json",
        "- Return raw JSON only"
    ]
    prompt = "\n".join(prompt_parts)
    raw_resp = None

    try:
        print(f"[DEBUG] Prompt: {prompt}")
        raw_resp = _call_ollama_raw(prompt)
        if raw_resp:
            print(f"[DEBUG] Ollama raw: {raw_resp[:200]}")
        
        result = await agent.run(prompt)
        print(f"[DEBUG] Agent output type: {type(result.output)}")
        output = _to_plan_safe(result.output)
        return output
        
    except Exception as e:
        print(f"[ERROR] Agent failed: {e}")
        
        # Fallback parsing
        if raw_resp:
            try:
                j = json.loads(raw_resp)
                content = j.get("choices", [{}])[0].get("message", {}).get("content", "")
                plan = _parse_plain_text_to_plan(content)
                print(f"[DEBUG] Fallback plan: {plan}")
                return plan
            except Exception as e2:
                print(f"[ERROR] Fallback failed: {e2}")
        
        raise HTTPException(status_code=502, detail=f"Agent call failed: {e}")

# ========== Health Endpoint ==========
@app.get("/health")
async def health():
    """Basic health check."""
    return {
        "status": "ok",
        "use_ollama": USE_OLLAMA,
        "provider": "ollama" if USE_OLLAMA else "test",
        "model_name": OLLAMA_MODEL_NAME if USE_OLLAMA else "TestModel",
        "python_version": sys.version.split()[0],
        "pid": os.getpid(),
    }
# Rulare: uvicorn app:app --reload --port 8000 --env-file .env