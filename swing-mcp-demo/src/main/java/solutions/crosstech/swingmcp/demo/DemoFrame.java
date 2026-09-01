package solutions.crosstech.swingmcp.demo;

import java.awt.BorderLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Demo Swing application exercising the component types supported by
 * swing-mcp. Used for manual testing and integration tests.
 */
public class DemoFrame extends JFrame {

    private final JLabel statusLabel = new JLabel("Ready");

    public DemoFrame() {
        super("Swing MCP Demo");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setJMenuBar(buildMenuBar());

        JTabbedPane tabs = new JTabbedPane();
        tabs.setName("mainTabs");
        tabs.addTab("Buttons", new ButtonsPanel(statusLabel));
        tabs.addTab("Forms", new FormsPanel(statusLabel));
        tabs.addTab("Lists", new ListsPanel(statusLabel));
        tabs.addTab("Table", new TablePanel(statusLabel));
        tabs.addTab("Tree", new TreePanel(statusLabel));

        statusLabel.setName("statusLabel");
        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        setSize(640, 480);
        setLocationByPlatform(true);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New");
        newItem.addActionListener(e -> statusLabel.setText("File > New clicked"));
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> dispose());
        fileMenu.add(newItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> DialogDemo.showAbout(this, statusLabel));
        helpMenu.add(aboutItem);

        bar.add(fileMenu);
        bar.add(helpMenu);
        return bar;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DemoFrame().setVisible(true));
    }
}
