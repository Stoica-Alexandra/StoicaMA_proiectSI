# Test chat completion (OpenAI-compat) pe Ollama
$body = @{ 
  model    = 'qwen2.5:1.5b-instruct'
  messages = @(@{ role = 'user'; content = 'Spune un salut scurt.' })
}

$resp = Invoke-RestMethod -Uri 'http://localhost:11434/v1/chat/completions' -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 5)

# Afișează doar textul răspunsului
$resp.choices[0].message.content