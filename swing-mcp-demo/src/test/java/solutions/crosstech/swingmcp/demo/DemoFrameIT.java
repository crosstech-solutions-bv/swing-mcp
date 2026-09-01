package solutions.crosstech.swingmcp.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

/**
 * Integration test constructing the full demo UI. Skipped in headless
 * environments; CI runs it under a virtual display (xvfb).
 */
@DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
class DemoFrameIT {

    @Test
    void demoFrameBuildsAllTabs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DemoFrame frame = new DemoFrame();
            try {
                JTabbedPane tabs = (JTabbedPane) findByName(frame.getContentPane(), "mainTabs");
                assertNotNull(tabs);
                assertEquals(5, tabs.getTabCount());
                assertEquals("Buttons", tabs.getTitleAt(0));
                assertEquals("Forms", tabs.getTitleAt(1));
                assertEquals("Lists", tabs.getTitleAt(2));
                assertEquals("Table", tabs.getTitleAt(3));
                assertEquals("Tree", tabs.getTitleAt(4));

                JLabel status = (JLabel) findByName(frame.getContentPane(), "statusLabel");
                assertNotNull(status);
                assertEquals("Ready", status.getText());
            } finally {
                frame.dispose();
            }
        });
    }

    private static Component findByName(Component root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = findByName(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
