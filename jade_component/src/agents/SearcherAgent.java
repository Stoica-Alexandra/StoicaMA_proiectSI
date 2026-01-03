package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import jade.content.lang.sl.SLCodec;
import jade.content.onto.basic.Action;
import jade.domain.FIPANames;
import jade.domain.FIPAService;
import jade.domain.JADEAgentManagement.JADEManagementOntology;
import jade.domain.JADEAgentManagement.ShutdownPlatform;

import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;

import javax.swing.*;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * Agentul principal (client) care: - pornește automat Controller +
 * PythonBridge; - gestionează UI-ul (SearchWindow); - pornește/oprește Finderii
 * prin Controller; - trimite cereri de căutare către Finderii din DF; - la
 * FOUND oprește restul Finderilor și (opțional) cere analiză AI.
 */
public class SearcherAgent extends Agent {
	private static final long serialVersionUID = 1L;

	/** Ontology pentru mesaje către/ de la PythonBridgeAgent */
	public static final String ONT_AI = "AI_ANALYSIS";

	/** Lista Finderilor detectați în DF (cache) */
	private final List<AID> cachedFinders = new ArrayList<>();

	/** Interfața grafică */
	private SearchWindow gui;

	/** ID-ul conversației/cererii curente (ca să filtrăm răspunsurile) */
	private volatile String currentConvId = null;

	/** Flag ca să acceptăm doar primul FOUND */
	private volatile boolean foundAlready = false;

	/** Flag global de shutdown */
	private volatile boolean shuttingDown = false;

	/** Setări UI: extragere/AI */
	private volatile boolean extractEnabled = true;
	private volatile boolean aiEnabled = true;

	/** Când e true, butonul Search trebuie blocat până vine răspuns de la AI */
	private volatile boolean waitingAi = false;

	/** Număr de răspunsuri așteptate/primite de la finderi */
	private volatile int expectedResponses = 0;
	private volatile int receivedResponses = 0;

	/** Numele fișierului căutat (doar pentru log) */
	private volatile String searchTarget = "";

	/** Folderul de extragere selectat de user */
	private volatile String extractFolder = null; // ales de user

	/** Codec + ontology necesare pentru shutdown platform via AMS */
	private final SLCodec codec = new SLCodec();

	@Override
	protected void setup() {
		// Necesare pentru request de shutdown către AMS
		getContentManager().registerLanguage(codec);
		getContentManager().registerOntology(JADEManagementOntology.getInstance());

		// UI
		gui = new SearchWindow(this);
		gui.setVisible(true);

		// Pornește automat agenții necesari
		ensureControllerRunning();
		ensurePythonBridgeRunning();

		// Primește: CONTROL (controller), FILE_SEARCH (finder), AI_ANALYSIS
		// (python-bridge)
		final MessageTemplate mt = MessageTemplate
				.or(MessageTemplate.or(MessageTemplate.MatchOntology(ControllerAgent.ONT_CONTROL),
						MessageTemplate.MatchOntology(FinderAgent.ONT_SEARCH)), MessageTemplate.MatchOntology(ONT_AI));

		addBehaviour(new CyclicBehaviour() {
			@Override
			public void action() {
				ACLMessage msg = receive(mt);
				if (msg == null) {
					block();
					return;
				}

				// Debug în consolă
				String sender = msg.getSender() != null ? msg.getSender().getLocalName() : "???";
				String perf = ACLMessage.getPerformative(msg.getPerformative());
				String ont = msg.getOntology();
				String content = msg.getContent();
				System.out.println("[" + sender + "] " + perf + " (" + ont + "): " + content);

				if (ControllerAgent.ONT_CONTROL.equals(ont)) {
					handleControllerMessage(msg);
				} else if (FinderAgent.ONT_SEARCH.equals(ont)) {
					handleFinderMessage(msg);
				} else if (ONT_AI.equals(ont)) {
					handleAiMessage(msg);
				}
			}
		});

		ui("Selectează un folder și apasă Start agenți Finder.");
		if (gui != null) {
			gui.setSearchEnabled(false);
			gui.setStartEnabled(true);
		}
	}

	/** Adaugă mesaj în log-ul UI */
	private void ui(String s) {
		if (gui != null)
			gui.appendLog(s);
	}

	/** Activează/dezactivează butonul Search în funcție de stări */
	private void updateSearchButtonState() {
		if (gui == null)
			return;

		boolean findersReady = !cachedFinders.isEmpty();
		boolean enabled = findersReady && !shuttingDown && !waitingAi;

		gui.setSearchEnabled(enabled);
	}

	/** Caută un agent Controller în DF și întoarce AID-ul lui */
	private AID findControllerInDF() {
		/** Definim template-ul de căutare în DF */
		DFAgentDescription template = new DFAgentDescription();
		ServiceDescription sd = new ServiceDescription();
		sd.setType("controller-service");
		template.addServices(sd);

		try {
			/** Căutăm agenții care oferă acest serviciu */
			DFAgentDescription[] results = DFService.search(this, template);

			/** Dacă există cel puțin un Controller, îl returnăm pe primul */
			if (results != null && results.length > 0) {
				return results[0].getName();
			}
		} catch (Exception e) {
			ui("Eroare DF (controller): " + e.getMessage());
		}

		return null;
	}

	/** Creează ControllerAgent dacă nu există deja */
	private void ensureControllerRunning() {
		try {
			ContainerController cc = getContainerController();
			AgentController ctrl = cc.createNewAgent("controller", "agents.ControllerAgent", null);
			ctrl.start();
			System.out.println("Controller creat automat.");
		} catch (Exception e) {
			System.out.println("Controller deja rulează.");
		}
	}

	/** Creează PythonBridgeAgent dacă nu există deja */
	private void ensurePythonBridgeRunning() {
		try {
			ContainerController cc = getContainerController();
			AgentController ai = cc.createNewAgent("python-bridge", // nume fix (simplu)
					"agents.PythonBridgeAgent", // clasa completă
					null);
			ai.start();
			ui("AI Assistant (PythonBridge) pornit automat.");
		} catch (Exception e) {
			// cel mai des: name already in use -> există deja, e OK
			ui("AI Assistant (PythonBridge) deja rulează.");
			System.out.println("[DBG] PythonBridge create/start: " + e.getMessage());
		}
	}

	/** Procesează mesajele venite de la Controller (STARTED/SHUTDOWN_OK) */
	private void handleControllerMessage(ACLMessage msg) {
		String c = msg.getContent() == null ? "" : msg.getContent().trim();

		if (msg.getPerformative() == ACLMessage.INFORM && c.startsWith("STARTED|")) {
			int expected = 0;
			try {
				expected = Integer.parseInt(c.split("\\|")[1]);
			} catch (Exception ignored) {
			}

			ui("Finderii au pornit (" + expected + "). Se actualizează lista din DF...");

			// pornește un polling în DF fără thread manual
			startFinderRefreshPolling(expected);

			return;
		}

		if (msg.getPerformative() == ACLMessage.INFORM && "SHUTDOWN_OK".equals(c)) {
			ui("Finderii au fost opriți.");
			if (shuttingDown) {
				ui("Închid platforma...");
				requestPlatformShutdown();
			}
		}
	}

	/** Procesează răspunsurile Finderilor (FOUND/NOT_FOUND/CANCELLED etc.) */
	private void handleFinderMessage(ACLMessage msg) {
		String c = msg.getContent();
		if (c == null || currentConvId == null)
			return;

		// Acceptăm doar mesaje din conversația curentă
		if (!c.contains("|" + currentConvId + "|"))
			return;

		// Dacă am găsit deja, ignorăm restul
		if (foundAlready)
			return;

		receivedResponses++;

		// Primul FOUND câștigă
		if (msg.getPerformative() == ACLMessage.INFORM && c.startsWith("FOUND|")) {
			foundAlready = true;

			// FOUND|convId|original|extracted
			String[] parts = c.split("\\|", 4);
			String originalPath = (parts.length >= 3) ? parts[2] : "(necunoscut)";
			String extractedPath = (parts.length == 4) ? parts[3] : "";

			ui("GĂSIT!");
			ui("Original: " + originalPath);

			String pathForAi;
			if (extractEnabled && extractedPath != null && !extractedPath.isEmpty()
					&& !"(necunoscut)".equals(extractedPath)) {
				ui("Extras în: " + extractedPath);
				pathForAi = extractedPath; // IMPORTANT: AI analizează fișierul local extras
			} else {

				ui("Extragere fișier dezactivată.");
				pathForAi = originalPath; // fallback
			}

			// Trimite STOP către ceilalți finderi (pentru convId curent)
			for (AID finder : cachedFinders) {
				ACLMessage stop = new ACLMessage(ACLMessage.REQUEST);
				stop.setOntology(FinderAgent.ONT_SEARCH);
				stop.addReceiver(finder);
				stop.setContent(FinderAgent.CMD_STOP + "|" + currentConvId);
				send(stop);
			}

			// Opțional: trimite către AI
			if (aiEnabled) {
				waitingAi = true;
				updateSearchButtonState();
				ui("Trimit către AI pentru analiză...");
				sendToAI(pathForAi, currentConvId);
				// nu endSearchUiState() aici
			} else {
				ui("Analiza AI dezactivată.");
				endSearchUiState();
			}
			return;
		}

		// Dacă au răspuns toți și nimeni nu a găsit
		if (receivedResponses >= expectedResponses) {
			ui("Nu s-a găsit: " + searchTarget);
			endSearchUiState();
		}
	}

	/** Procesează răspunsul venit de la PythonBridgeAgent */
	private void handleAiMessage(ACLMessage msg) {
		waitingAi = false; // am primit răspuns (INFORM sau FAILURE)

		if (msg.getPerformative() == ACLMessage.FAILURE) {
			ui("\n--- Analiză AI (eroare) ---");
			ui(msg.getContent());
			ui("--------------------------\n");
		} else {
			String raw = msg.getContent();
			String answerOnly = extractAnswer(raw);

			ui("\n--- Analiză AI ---");
			ui(answerOnly);
			ui("------------------\n");
		}

		endSearchUiState();
	}

	/**
	 * Extrage câmpul "answer" din JSON-ul primit de la API (fallback: returnează
	 * tot textul)
	 */
	private String extractAnswer(String json) {
		if (json == null)
			return "(fără conținut)";

		try {
			int idx = json.indexOf("\"answer\"");
			if (idx == -1)
				return json;

			int colon = json.indexOf(":", idx);
			int startQuote = json.indexOf("\"", colon + 1);
			int endQuote = json.indexOf("\"", startQuote + 1);

			if (startQuote == -1 || endQuote == -1)
				return json;

			return json.substring(startQuote + 1, endQuote);
		} catch (Exception e) {
			return json; // fallback sigur
		}
	}

	/** Setează folderul unde Finderii vor copia fișierul găsit */
	public void setExtractFolder(String path) {
		this.extractFolder = path;
		ui("Folder extragere: " + path);
	}

	/** Reface starea butoanelor după o căutare/analiză */
	private void endSearchUiState() {
		if (gui != null) {
			boolean ready = !cachedFinders.isEmpty();
			gui.setStartEnabled(!ready); // Start OFF dacă avem finderi, altfel ON
			updateSearchButtonState();
		}
	}

	/** Cere Controller-ului să pornească Finderii pentru folderul selectat */
	public void startFinders(String folder) {
		if (shuttingDown)
			return;

		if (folder == null || folder.trim().isEmpty()) {
			ui("Alege mai întâi un folder (Browse).");
			return;
		}

		if (gui != null) {
			gui.setSearchEnabled(false);
			gui.setStartEnabled(false);
		}

		ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
		msg.setOntology(ControllerAgent.ONT_CONTROL);
		msg.setContent(ControllerAgent.CMD_START + "|" + folder);
		AID ctrl = findControllerInDF();
		if (ctrl == null) {
			ui("Controller nu este disponibil în DF.");
			endSearchUiState();
			return;
		}
		msg.addReceiver(ctrl);

		send(msg);

		ui("Pornesc Finderii pentru folderul selectat...");
	}

	/** Cere Controller-ului să oprească toți Finderii (prin TERMINATE) */
	public void shutdownFinders() {
		if (shuttingDown)
			return;

		waitingAi = false;
		ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
		msg.setOntology(ControllerAgent.ONT_CONTROL);
		msg.setContent(ControllerAgent.CMD_SHUTDOWN);
		AID ctrl = findControllerInDF();
		if (ctrl == null) {
			ui("Controller nu este disponibil în DF.");
			endSearchUiState();
			return;
		}
		msg.addReceiver(ctrl);

		send(msg);

		cachedFinders.clear();
		endSearchUiState();
		ui("Cerere de shutdown trimisă.");
	}

	/** Trimite cererea de căutare către toți Finderii din cache */
	public void searchFile(String fileName) {
		if (shuttingDown)
			return;

		extractEnabled = gui.isExtractEnabled();
		aiEnabled = gui.isAiEnabled();

		if (fileName == null || fileName.trim().isEmpty()) {
			ui("Introduceți un nume de fișier!");
			return;
		}

		if (extractEnabled && (extractFolder == null || extractFolder.trim().isEmpty())) {
			ui("Alege un folder de extragere (Browse Extract) înainte de Search.");
			return;
		}

		if (cachedFinders.isEmpty()) {
			ui("Nu există finderi activi. Apasă Start agenți Finder.");
			if (gui != null) {
				gui.setSearchEnabled(false);
				gui.setStartEnabled(true);
			}
			return;
		}

		foundAlready = false;
		currentConvId = UUID.randomUUID().toString().substring(0, 8);
		searchTarget = fileName.trim();

		expectedResponses = cachedFinders.size();
		receivedResponses = 0;

		if (gui != null) {
			gui.clearLog();
			gui.setSearchEnabled(false);
			gui.setStartEnabled(false);
		}

		System.out.println("================");
		System.out.println("Caut: " + searchTarget + " ...");
		ui("Caut: " + searchTarget + " ...");

		for (AID finder : cachedFinders) {
			ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
			req.setOntology(FinderAgent.ONT_SEARCH);
			req.addReceiver(finder);
			if (extractEnabled)
				req.setContent(FinderAgent.CMD_REQ + "|" + currentConvId + "|" + searchTarget + "|" + extractFolder);
			else
				req.setContent(FinderAgent.CMD_REQ + "|" + currentConvId + "|" + searchTarget);
			send(req);
		}
	}

	/**
	 * Polling scurt în DF ca să prindem Finderii după START (fără thread manual)
	 */
	private void startFinderRefreshPolling(final int expected) {
		final int maxAttempts = 10;
		final long periodMs = 250;

		addBehaviour(new TickerBehaviour(this, periodMs) {
			private int attempt = 0;
			private int last = -1;

			@Override
			protected void onTick() {
				attempt++;

				refreshFindersFromDF();

				int now = cachedFinders.size();
				if (now >= expected) {
					stopAndReport();
					return;
				}
				if (now == last && now > 0) {
					stopAndReport();
					return;
				}

				last = now;

				if (attempt >= maxAttempts) {
					stopAndReport();
				}
			}

			private void stopAndReport() {
				stop(); // oprește ticker-ul

				ui("Finders disponibili: " + cachedFinders.size());

				boolean ready = !cachedFinders.isEmpty();
				if (ready)
					ui("Gata. Poți căuta acum.");
				else
					ui("Nu am găsit finderi în DF. Încearcă Start din nou.");
			}
		});
	}

	/** Reîncarcă lista Finderilor din DF (type=file-search) */
	private void refreshFindersFromDF() {
		cachedFinders.clear();

		DFAgentDescription template = new DFAgentDescription();
		ServiceDescription sd = new ServiceDescription();
		sd.setType("file-search");
		template.addServices(sd);

		try {
			DFAgentDescription[] results = DFService.search(this, template);
			for (DFAgentDescription dfd : results)
				cachedFinders.add(dfd.getName());

			endSearchUiState();
		} catch (Exception e) {
			ui("Eroare DF: " + e.getMessage());
			if (gui != null) {
				gui.setSearchEnabled(false);
				gui.setStartEnabled(true);
			}
		}
	}

	/** Trimite către PythonBridgeAgent cererea de analiză pentru un path */
	private void sendToAI(String filePath, String convId) {
		if (shuttingDown)
			return;
		// găsim python-bridge în DF
		DFAgentDescription template = new DFAgentDescription();
		ServiceDescription sd = new ServiceDescription();
		sd.setType("python-bridge");
		template.addServices(sd);

		try {
			DFAgentDescription[] results = DFService.search(this, template);
			if (results.length == 0) {
				ui("Agentul AI nu a fost găsit în DF.");
				waitingAi = false;
				endSearchUiState();
				return;
			}

			AID aiAgent = results[0].getName();
			ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
			req.addReceiver(aiAgent);
			req.setOntology(ONT_AI);
			req.setConversationId(convId);
			req.setContent("ANALYZE|" + filePath);

			send(req);

		} catch (Exception e) {
			ui("Eroare la trimiterea către AI: " + e.getMessage());
			waitingAi = false;
			endSearchUiState();
		}

	}

	/** Închidere aplicație: oprește finderi + cere shutdown platform */
	public void shutdownPlatform() {
		if (shuttingDown)
			return;
		shuttingDown = true;

		ui("Închidere aplicație...");
		shutdownFinders();

		// FORȚEAZĂ shutdown platform
		requestPlatformShutdown();
	}

	/** Request de shutdown către AMS (ShutdownPlatform) */
	private void requestPlatformShutdown() {
		addBehaviour(new OneShotBehaviour() {
			@Override
			public void action() {
				try {
					ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
					req.addReceiver(getAMS());
					req.setLanguage(codec.getName());
					req.setOntology(JADEManagementOntology.NAME);
					req.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);

					Action act = new Action(getAMS(), new ShutdownPlatform());
					getContentManager().fillContent(req, act);

					FIPAService.doFipaRequestClient(myAgent, req);

				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					doDelete();
				}
			}
		});
	}

	@Override
	protected void takeDown() {
		if (gui != null)
			SwingUtilities.invokeLater(() -> gui.dispose());
	}
}
