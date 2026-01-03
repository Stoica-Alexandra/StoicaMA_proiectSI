package agents;

import com.formdev.flatlaf.FlatLightLaf;

/**
 * Clasa principală a aplicației. Pornește interfața grafică și platforma JADE
 * cu agentul SearcherAgent.
 */
public class Main {
	/**
	 * Punctul de intrare în aplicație.
	 */
	public static void main(String[] args) {
		/** Inițializează tema grafică FlatLaf pentru Swing */
		FlatLightLaf.setup();

		/** Pornește platforma JADE și agentul principal */
		jade.Boot.main(new String[] { "-gui", "-agents", "searcher:agents.SearcherAgent" });
	}
}
