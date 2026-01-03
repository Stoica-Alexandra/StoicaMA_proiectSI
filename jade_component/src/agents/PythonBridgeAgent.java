package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent "bridge" între JADE și un serviciu AI local (FastAPI). Primește cereri
 * de tip REQUEST pe ontologia AI_ANALYSIS și întoarce răspunsul API-ului ca
 * INFORM.
 */
public class PythonBridgeAgent extends Agent {

	/** Ontologia folosită pentru mesajele de analiză AI */
	public static final String ONT_AI = "AI_ANALYSIS";

	/** Client HTTP folosit pentru apelul către FastAPI (localhost) */
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@Override
	protected void setup() {
		/** Înregistrare în DF ca serviciu "python-bridge" */
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());

		ServiceDescription sd = new ServiceDescription();
		sd.setType("python-bridge");
		sd.setName("ollama-ai-bridge");
		dfd.addServices(sd);

		try {
			DFService.register(this, dfd);
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println(getLocalName() + " (PythonBridgeAgent) pornit.");

		/** Primește doar REQUEST cu ontologia AI_ANALYSIS */
		MessageTemplate mt = MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
				MessageTemplate.MatchOntology(ONT_AI));

		/** Loop: extrage filepath, apelează API-ul și răspunde către agentul apelant */
		addBehaviour(new CyclicBehaviour(this) {
			@Override
			public void action() {
				ACLMessage msg = myAgent.receive(mt);
				if (msg == null) {
					block();
					return;
				}

				try {
					String content = msg.getContent() == null ? "" : msg.getContent();
					String filePath = extractFilePath(content);

					/** Construiește JSON-ul pentru serviciul FastAPI */
					String payload = String.format(
							"{\"instruction\":\"Analyze file type and likely role based only on the filepath string.\",\"filepath\":\"%s\"}",
							escapeJson(filePath));

					System.out.println("=== SENDING ===");
					System.out.println(payload);
					System.out.println("===============");

					/** Request către endpoint-ul local */
					HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:8000/agent/solve"))
							.header("Content-Type", "application/json")
							.POST(BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();

					HttpResponse<String> resp = http.send(req,
							HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

					System.out.println("=== RESPONSE ===");
					System.out.println("Status: " + resp.statusCode());
					System.out.println(resp.body());
					System.out.println("================");

					/** Răspuns către Searcher: INFORM + aceeași ontologie */
					ACLMessage reply = msg.createReply();
					reply.setPerformative(ACLMessage.INFORM);
					reply.setOntology(ONT_AI);
					reply.setContent(resp.body());

					myAgent.send(reply);

				} catch (Exception e) {
					/** Dacă API-ul pică / e eroare de rețea: FAILURE */
					e.printStackTrace();
					ACLMessage failure = msg.createReply();
					failure.setPerformative(ACLMessage.FAILURE);
					failure.setOntology(ONT_AI);
					failure.setContent("ERROR: " + e.getMessage());
					myAgent.send(failure);
				}
			}
		});
	}

	/** Escapare minimă pentru a insera filepath în JSON fără să strice formatul */
	private String escapeJson(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	/**
	 * Extrage filepath din conținutul mesajului. Acceptă formatul principal
	 * "ANALYZE|<path>" + câteva fallback-uri.
	 */
	private String extractFilePath(String content) {
		if (content == null)
			return "";

		/** Formatul principal folosit de Searcher: ANALYZE|path */
		if (content.startsWith("ANALYZE|")) {
			return content.substring("ANALYZE|".length()).trim();
		}

		/** Fallback: caută "file:" și ia tot după */
		int idx = content.toLowerCase().indexOf("file:");
		if (idx >= 0) {
			return content.substring(idx + "file:".length()).trim();
		}

		/** Fallback: caută un path între ghilimele care se termină cu extensie */
		Pattern p = Pattern.compile("['\"]([^'\"]+\\.[a-zA-Z0-9]+)['\"]");
		Matcher m = p.matcher(content);
		if (m.find())
			return m.group(1);

		/** Ultim fallback: întregul content */
		return content.trim();
	}

	@Override
	protected void takeDown() {
		/** Deregistrare din DF la închiderea agentului */
		try {
			DFService.deregister(this);
		} catch (Exception ignored) {
		}
	}
}
