package agents;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Interfața Swing pentru aplicația de căutare distribuită. Permite: alegerea
 * folderului, pornirea/oprirea agenților Finder, căutarea unui fișier, setarea
 * folderului de extragere și afișarea log-ului.
 */
class SearchWindow extends JFrame {
	private static final long serialVersionUID = 1L;

	/** Zonă de log (cu scroll vertical), folosită pentru mesaje din aplicație */
	private final JTextArea log = new JTextArea(15, 55);

	/** Input: numele fișierului de căutat */
	private final JTextField fileField = new JTextField(18);

	/** Afișează folderul selectat pentru căutare (read-only) */
	private final JTextField folderField = new JTextField(28);

	/** Afișează folderul selectat pentru extragere (read-only) */
	private final JTextField extractField = new JTextField(28);

	/** Buton: pornește agenții Finder */
	private final JButton startBtn = new JButton("Start agenți Finder");

	/** Buton: oprește agenții Finder */
	private final JButton shutdownBtn = new JButton("Shutdown agenți Finder");

	/** Buton: pornește căutarea */
	private final JButton searchBtn = new JButton("Search");

	/** Buton: alege folderul de căutare */
	private final JButton browseBtn = new JButton("Browse...");

	/** Buton: alege folderul de extragere */
	private final JButton browseExtractBtn = new JButton("Browse Extract...");

	/** Opțiune: dacă fișierul găsit se copiază în folderul de extragere */
	private final JCheckBox cbExtract = new JCheckBox("Extragere fișier", true);

	/** Opțiune: trimite fișierul către AI pentru analiză */
	private final JCheckBox cbAI = new JCheckBox("Analiză AI", true);

	/**
	 * Construiește UI-ul și leagă acțiunile de metodele agentului Searcher.
	 */
	public SearchWindow(SearcherAgent agent) {
		setTitle("Distributed File Search");
		setLayout(new BoxLayout(getContentPane(), BoxLayout.Y_AXIS));

		log.setEditable(false);
		folderField.setEditable(false);
		extractField.setEditable(false);

		/** Rândul 1: folder + browse + start + shutdown */
		JPanel row1 = new JPanel();
		row1.add(new JLabel("Folder:"));
		row1.add(folderField);

		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			int res = fc.showOpenDialog(this);
			if (res == JFileChooser.APPROVE_OPTION) {
				File dir = fc.getSelectedFile();
				folderField.setText(dir.getAbsolutePath());

				setSearchEnabled(false);
				startBtn.setEnabled(true);
				appendLog("Folder selectat. Apasă Start agenți Finder.");
			}
		});

		row1.add(browseBtn);

		startBtn.addActionListener(e -> {
			setSearchEnabled(false);
			setStartEnabled(false);
			agent.startFinders(folderField.getText());
		});
		row1.add(startBtn);

		shutdownBtn.addActionListener(e -> {
			setSearchEnabled(false);
			agent.shutdownFinders();
		});
		row1.add(shutdownBtn);

		/** Rând extragere: folder extragere + browse + checkbox */
		JPanel rowExtract = new JPanel();
		rowExtract.add(new JLabel("Extragere:"));
		rowExtract.add(extractField);

		browseExtractBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			int res = fc.showOpenDialog(this);
			if (res == JFileChooser.APPROVE_OPTION) {
				File dir = fc.getSelectedFile();
				extractField.setText(dir.getAbsolutePath());
				agent.setExtractFolder(dir.getAbsolutePath());
				appendLog("Folder extragere setat.");
			}
		});
		rowExtract.add(browseExtractBtn);
		rowExtract.add(cbExtract);

		/** Rândul 2: nume fișier + Search + checkbox AI */
		JPanel row2 = new JPanel();
		row2.add(new JLabel("Nume fișier:"));
		row2.add(fileField);

		searchBtn.setEnabled(false);
		searchBtn.addActionListener(e -> agent.searchFile(fileField.getText()));
		row2.add(searchBtn);
		row2.add(cbAI);

		add(row1);
		add(rowExtract);
		add(row2);

		/** Scroll doar pe verticală (fără bară orizontală) */
		JScrollPane scroll = new JScrollPane(log, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll);

		/** În loc de scroll orizontal, face wrap pe linii */
		log.setLineWrap(true);
		log.setWrapStyleWord(true);

		pack();
		setLocationRelativeTo(null);

		/** La închiderea ferestrei: se cere shutdown pe platformă */
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				agent.shutdownPlatform();
			}
		});
	}

	/** Activează/dezactivează butonul Start */
	public void setStartEnabled(boolean enabled) {
		SwingUtilities.invokeLater(() -> startBtn.setEnabled(enabled));
	}

	/** Activează/dezactivează butonul Search */
	public void setSearchEnabled(boolean ready) {
		SwingUtilities.invokeLater(() -> searchBtn.setEnabled(ready));
	}

	/** Returnează dacă extragerea este activată */
	public boolean isExtractEnabled() {
		return cbExtract.isSelected();
	}

	/** Returnează dacă analiza AI este activată */
	public boolean isAiEnabled() {
		return cbAI.isSelected();
	}

	/** Adaugă o linie în log */
	public void appendLog(String s) {
		SwingUtilities.invokeLater(() -> {
			log.append(s + "\n");
			log.setCaretPosition(log.getDocument().getLength());
		});
	}

	/** Curăță log-ul */
	public void clearLog() {
		SwingUtilities.invokeLater(() -> log.setText(""));
	}
}
