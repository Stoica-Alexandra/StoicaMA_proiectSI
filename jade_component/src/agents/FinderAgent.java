package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.ThreadedBehaviourFactory;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import java.nio.file.StandardCopyOption;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Agent Finder: caută un fișier într-un director (baseDir) și raportează
 * rezultatul. Poate opri căutarea la cerere (STOP/TERMINATE) și poate copia
 * fișierul găsit într-un folder de extragere.
 */
public class FinderAgent extends Agent {
	private static final long serialVersionUID = 1L;

	/** Ontologia folosită pentru cereri/răspunsuri de căutare */
	public static final String ONT_SEARCH = "FILE_SEARCH";

	/** Comandă: închide agentul (și oprește căutarea dacă rulează) */
	public static final String CMD_TERMINATE = "TERMINATE";

	/** Comandă: oprește căutarea curentă pentru un convId */
	public static final String CMD_STOP = "STOP_SEARCH"; // STOP_SEARCH|<convId>

	/** Comandă: pornește căutarea unui fișier */
	public static final String CMD_REQ = "SEARCH"; // SEARCH|<convId>|<filename>

	/** Directorul în care agentul caută fișiere */
	private Path baseDir;

	/** convId-ul căutării curente */
	private volatile String activeConvId = null;

	/** Flag pentru anularea căutării curente */
	private volatile boolean cancelSearch = false;

	/** Flag simplu: nu pornește o a doua căutare cât timp una e în desfășurare */
	private volatile boolean searching = false;

	/** Rulează job-ul de căutare într-un thread separat */
	private final ThreadedBehaviourFactory tbf = new ThreadedBehaviourFactory();

	protected void setup() {
		/** Ia directorul de bază din argumentele agentului */
		baseDir = Paths.get((String) getArguments()[0]);

		/** Înregistrează serviciul în DF ca "file-search" */
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());

		ServiceDescription sd = new ServiceDescription();
		sd.setType("file-search");
		sd.setName("finder");
		dfd.addServices(sd);

		try {
			DFService.register(this, dfd);
		} catch (Exception ignored) {
		}

		System.out.println(getLocalName() + " pornit pentru: " + baseDir);

		/** Bucla principală: primește comenzi (SEARCH / STOP_SEARCH / TERMINATE) */
		addBehaviour(new CyclicBehaviour() {
			public void action() {
				ACLMessage msg = receive();
				if (msg == null) {
					block();
					return;
				}

				/** Acceptă doar mesajele cu ontologia de căutare */
				if (!ONT_SEARCH.equals(msg.getOntology()))
					return;

				String c = msg.getContent() == null ? "" : msg.getContent().trim();

				/** Termină agentul și oprește căutarea dacă rulează */
				if (CMD_TERMINATE.equals(c)) {
					// dacă e în căutare, cere oprirea; apoi închide
					cancelSearch = true;
					doDelete();
					return;
				}

				/** Oprește căutarea curentă dacă convId-ul corespunde */
				if (c.startsWith(CMD_STOP + "|")) {
					String convId = c.split("\\|", 2)[1];
					if (convId != null && convId.equals(activeConvId)) {
						cancelSearch = true;
					}
					return;
				}

				/** Pornește o căutare nouă: SEARCH|convId|filename|[outDir] */
				if (c.startsWith(CMD_REQ + "|")) {
					String[] parts = c.split("\\|", 4);
					if (parts.length < 3)
						return;

					String convId = parts[1];
					String filename = parts[2];
					String outDir = (parts.length == 4) ? parts[3] : null;

					/** Dacă e deja în căutare, ignoră cererea nouă */
					if (searching)
						return;

					activeConvId = convId;
					cancelSearch = false;
					searching = true;

					/** Rulează căutarea în thread separat și trimite răspuns */
					addBehaviour(tbf.wrap(new OneShotBehaviour() {
						@Override
						public void action() {
							try {
								searchAndReply(msg, filename, convId, outDir);
							} finally {
								searching = false;
							}
						}
					}));

					return;
				}

			}
		});
	}

	/**
	 * Caută fișierul în baseDir (recursiv) și răspunde cu: -
	 * FOUND|convId|original|extracted - NOT_FOUND|convId|baseDir -
	 * CANCELLED|convId|baseDir - ERROR|convId|mesaj
	 */
	private void searchAndReply(ACLMessage msg, final String filename, final String convId, final String outDir) {
		final ACLMessage reply = msg.createReply();
		reply.setOntology(ONT_SEARCH);

		try {
			final Path[] foundPath = { null };

			/** Parcurge recursiv directorul și oprește dacă găsește fișierul */
			Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (cancelSearch)
						return FileVisitResult.TERMINATE;

					if (file.getFileName().toString().equalsIgnoreCase(filename)) {
						foundPath[0] = file;
						return FileVisitResult.TERMINATE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
					return cancelSearch ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
				}
			});

			/** Dacă s-a cerut anularea, raportează CANCELLED */
			if (cancelSearch) {
				reply.setPerformative(ACLMessage.FAILURE);
				reply.setContent("CANCELLED|" + convId + "|" + baseDir.toString());
				send(reply);
				return;
			}

			/** Dacă s-a găsit fișierul, raportează FOUND (cu opțională extragere/copie) */
			if (foundPath[0] != null) {
				if (outDir != null && !"NO_EXTRACT".equals(outDir)) {
					Path out = (outDir == null || outDir.trim().isEmpty()) ? Paths.get("extracted") // fallback
							: Paths.get(outDir);
					Files.createDirectories(out);

					Path outFile = out.resolve(foundPath[0].getFileName().toString());
					Files.copy(foundPath[0], outFile, StandardCopyOption.REPLACE_EXISTING);

					reply.setPerformative(ACLMessage.INFORM);
					reply.setContent("FOUND|" + convId + "|" + foundPath[0] + "|" + outFile.toAbsolutePath());

				} else {
					/** FĂRĂ EXTRAGERE */
					reply.setPerformative(ACLMessage.INFORM);
					reply.setContent("FOUND|" + convId + "|" + foundPath[0] + "|");
				}

				send(reply);
				return;
			}

			/** Dacă nu s-a găsit fișierul */
			reply.setPerformative(ACLMessage.FAILURE);
			reply.setContent("NOT_FOUND|" + convId + "|" + baseDir.toString());

		} catch (Exception e) {
			/** Orice eroare de I/O */
			reply.setPerformative(ACLMessage.FAILURE);
			reply.setContent("ERROR|" + convId + "|" + e.getMessage());
		}

		send(reply);
	}

	@Override
	protected void takeDown() {
		/** Scoate agentul din DF la închidere */
		try {
			DFService.deregister(this);
		} catch (Exception ignored) {
		}

		System.out.println(getLocalName() + " oprit.");
	}

}
