package solutions.crosstech.swingmcp.demo;

import java.awt.Frame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;

/**
 * Simple dialog demonstrations for the demo application.
 */
public final class DialogDemo {

    private DialogDemo() {
    }

    /**
     * Shows the About dialog and reports the interaction to the status label.
     */
    public static void showAbout(Frame owner, JLabel statusLabel) {
        JOptionPane.showMessageDialog(
            owner,
            "Swing MCP Demo\nUsed for testing the swing-mcp server.",
            "About",
            JOptionPane.INFORMATION_MESSAGE);
        statusLabel.setText("About dialog closed");
    }
}
