# Căutare distribuită de fișiere cu asistent AI (PydanticAI/Ollama)

<p align="right">Student: Stoica Maria-Alexandra<br/>Grupa: 3141A</p>

---

Acest proiect implementează un sistem multi-agent distribuit pentru căutarea fișierelor într-o structură locală de directoare, integrând un asistent AI pentru analiza metadatelor fișierelor găsite.

## 1. Arhitectura sistemului

Sistemul este compus din două componente principale:
 1. Componenta JADE (Java): Gestionează coordonarea, căutarea distribuită și interfața grafică.
 2. Componenta AI (Python): Un serviciu FastAPI care utilizează PydanticAI și Ollama pentru a analiza rolul fișierelor.

Agenții JADE utilizați:
 * **SearcherAgent** (GUI): Agentul principal care: 
   * pornește automat Controller + PythonBridge; 
   * gestionează UI-ul (SearchWindow); 
   * pornește/oprește Finderii prin Controller; 
   * trimite cereri de căutare către Finderii din DF; 
   * la FOUND oprește restul Finderilor;
   * (opțional) cere analiză AI pentru tipul și rolul unui fișier, doar pe baza căii acestuia.
 * **ControllerAgent**: Gestionează ciclul de viață al agenților Finder (pornire/oprire centralizată).
 * **FinderAgent**: Agenți specializați care:
   * scanează directoare specifice în mod recursiv și raportează rezultatul;
   * caută fișiere exclusiv pe baza numelui complet (inclusiv extensia);
   * pot opri căutarea la cerere (STOP/TERMINATE);
   * pot copia fișierul găsit într-un folder de extragere.
 * **PythonBridgeAgent**: Acționează ca un gateway între protocolul ACL JADE și API-ul REST al serviciului Python.

Alte clase:
 * **SearchWindow**: Interfața Swing pentru aplicația de căutare distribuită. Permite: alegerea folderului, pornirea/oprirea agenților Finder, căutarea unui fișier, setarea folderului de extragere și afișarea log-ului.
 * **Main**: Inițializează tema grafică FlatLaf pentru Swing, pornește platforma JADE și agentul SearcherAgent.

## 2. Cerințe
* Java 17+
* Python 3.10+ (recomandat 3.11/3.12)
* Visual Studio Code
* Model Ollama: qwen2.5:1.5b-instruct
* JADE Framework (jade.jar)
* FlatLaf (flatlaf-3.6.2.jar)

## 3. Structura proiectului
StoicaMA_proiectSI
  - 📁 jade_component
    - 📁 bin
    - 📁 src/agents/
       - 📄 ControllerAgent.java
       - 📄 FinderAgent.java
       - 📄 Main.java
       - 📄 PythonBridgeAgent.java
       - 📄 SearcherAgent.java
       - 📄 SearchWindow.java
    - 📄 flatlaf-3.6.2.jar
    - 📄 jade.jar
    - ...
  - 📁 python_service
    - 📁 src
       - 📄 app.py
    - 📄 .env
    - 📄 requirements.txt
    - 📄 uvicorn_config.py
  - 📄 checker.ps1

## 4. Instrucțiuni de rulare
Trebuie:
* terminal pentru server uvicorn în VS Code;
* terminal CMD deschis în directorul jade_component/ pentru platforma JADE;
* terminal CMD pentru Ollama;
* terminal CMD pentru Verificarea modelului Ollama încărcat în mod curent în memorie.

Ordinea recomandată de pornire:
1. Serverul Python (FastAPI + Ollama)
2. Modelul Ollama
3. Platforma JADE

### 4.1. Python
**Creare mediu virtual din terminal**
```
py --version
py -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
```

**Sau din tastatură (VS Code)**

 1. Deschide proiectul în Visual Studio Code
 2. Apasă: *CTRL + SHIFT + P*
 3. Selectează: *Python: Create Environment*
 4. Alege: *Venv*
 5. Selectează versiunea de Python dorită
 6. Selectează fișierul: *requirements.txt*
 7. VS Code va crea automat mediul virtual și va instala dependențele

Activarea interpreterului Python din venv:
    
 1. Apasă din nou: *CTRL + SHIFT + P*
 2. Selectează: *Python: Select Interpreter*
 3. Alege interpreterul din: *./venv/Scripts/python.exe*

**Lansare server uvicorn în VS Code**
```
python -m uvicorn app:app --app-dir src --reload --port 8000 --env-file .env
```

### 4.2. Ollama
```
ollama --version
ollama pull qwen2.5:1.5b-instruct
ollama run qwen2.5:1.5b-instruct
```

Verificarea modelului încărcat în mod curent în memorie (folosiți un alt terminal)
```
ollama ps
```

### 4.3. Lansare platformă JADE (din directorul jade_component/), folosind CMD
```
javac -cp ".\jade.jar;.\flatlaf-3.6.2.jar" -d bin src\agents\*.java
java -cp "bin;.\jade.jar;.\flatlaf-3.6.2.jar" agents.Main
```

## 5. Descrierea protocolului de comunicare
Sistemul utilizează mesaje ACL (FIPA-ACL) pentru comunicarea dintre agenți. Comunicarea este structurată pe ontologii distincte:
* Ontologia *CONTROL* - utilizată între SearcherAgent și ControllerAgent pentru managementul ecosistemului de agenți Finder.
* Ontologia *FILE_SEARCH* - utilizată pentru comunicarea dintre SearcherAgent și agenții FinderAgent în timpul procesului de căutare distribuită.
* Ontologia *AI_ANALYSIS* - utilizată între SearcherAgent și PythonBridgeAgent pentru analiza asistată de AI.
