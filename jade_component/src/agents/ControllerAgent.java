package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;

import java.io.File;

/**
 * Agentul Controller coordonează pornirea și oprirea agenților Finder din
 * sistem.
 */
public class ControllerAgent extends Agent {
	private static final long serialVersionUID = 1L;

	/** Ontologia pentru mesaje de control */
	public static final String ONT_CONTROL = "CONTROL";

	/** Comandă de pornire a agenților Finder */
	public static final String CMD_START = "START_FINDERS";

	/** Comandă de oprire a agenților Finder */
	public static final String CMD_SHUTDOWN = "SHUTDOWN_FINDERS";

	protected void setup() {
		System.out.println("Controller pornit: " + getLocalName());

		/** Înregistrează serviciul în DF ca "controller-service" */
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());

		ServiceDescription sd = new ServiceDescription();
		sd.setType("controller-service");
		sd.setName("controller");
		dfd.addServices(sd);

		try {
			DFService.register(this, dfd);
		} catch (Exception e) {
			e.printStackTrace();
		}

		addBehaviour(new ControlBehaviour());
	}

	/** Comportament ciclic care primește și procesează comenzile de control. */
	private class ControlBehaviour extends CyclicBehaviour {

		private static final long serialVersionUID = 1L;

		public void action() {
			ACLMessage msg = receive();
			if (msg == null) {
				block();
				return;
			}

			/** Acceptă doar mesaje cu ontologia CONTROL */
			if (!ONT_CONTROL.equals(msg.getOntology()))
				return;

			String c = msg.getContent() == null ? "" : msg.getContent().trim();

			/** Comandă de pornire a agenților Finder */
			if (c.startsWith(CMD_START)) {
				String[] parts = c.split("\\|", 2);
				String folder = (parts.length == 2) ? parts[1] : "";

				int started = startFinders(folder);

				ACLMessage reply = msg.createReply();
				reply.setPerformative(ACLMessage.INFORM);
				reply.setOntology(ONT_CONTROL);
				reply.setContent("STARTED|" + started);
				send(reply);
			}

			/** Comandă de oprire a agenților Finder */
			if (CMD_SHUTDOWN.equals(c)) {
				shutdownAllFinders();

				ACLMessage reply = msg.createReply();
				reply.setPerformative(ACLMessage.INFORM);
				reply.setOntology(ONT_CONTROL);
				reply.setContent("SHUTDOWN_OK");
				send(reply);
			}
		}
	}

	/**
	 * Pornește agenți Finder pentru directorul dat și pentru fiecare subdirector.
	 */
	private int startFinders(String folderPath) {
		File root = (folderPath == null || folderPath.trim().isEmpty()) ? new File(System.getProperty("user.home"))
				: new File(folderPath);

		/** Verifică dacă folderul este valid */
		if (!root.exists() || !root.isDirectory()) {
			System.out.println("Folder invalid: " + root.getAbsolutePath());
			return 0;
		}

		/** Oprește Finderii existenți înainte de pornire */
		shutdownAllFinders();

		File[] dirs = root.listFiles(File::isDirectory);
		ContainerController cc = getContainerController();
		int count = 0;
		long t = System.currentTimeMillis();

		try {
			/** Finder pentru directorul rădăcină */
			AgentController acRoot = cc.createNewAgent("finder_root_" + t, "agents.FinderAgent",
					new Object[] { root.getAbsolutePath() });
			acRoot.start();
			count++;

			/** Finder pentru fiecare subdirector */
			if (dirs != null) {
				for (int i = 0; i < dirs.length; i++) {
					AgentController ac = cc.createNewAgent("finder_" + i + "_" + t, "agents.FinderAgent",
							new Object[] { dirs[i].getAbsolutePath() });
					ac.start();
					count++;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("Finders porniți: " + count + " pentru " + root.getAbsolutePath());
		return count;
	}

	/** Oprește toți agenții Finder înregistrați în DF. */
	private void shutdownAllFinders() {
		DFAgentDescription template = new DFAgentDescription();
		ServiceDescription sd = new ServiceDescription();
		sd.setType("file-search");
		template.addServices(sd);

		try {
			DFAgentDescription[] result = DFService.search(this, template);
			for (DFAgentDescription dfd : result) {
				ACLMessage kill = new ACLMessage(ACLMessage.REQUEST);
				kill.addReceiver(dfd.getName());
				kill.setOntology(FinderAgent.ONT_SEARCH);
				kill.setContent(FinderAgent.CMD_TERMINATE);
				send(kill);
			}
			System.out.println("Shutdown: s-a trimis TERMINATE la " + result.length + " Finder(s).");
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
	}

	@Override
	protected void takeDown() {
		/** Scoate agentul din DF la închidere */
		try {
			DFService.deregister(this);
		} catch (Exception ignored) {
		}
	}
}
