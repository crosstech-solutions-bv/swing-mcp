package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * handle_dialog answers the dialog the user would be answering, the topmost
 * modal one, and a context-menu item that opens a modal dialog does not leave
 * its popup on screen.
 */
class DialogTargetingTest {

    private final SwingCommands commands = new SwingCommands();
    private final AtomicInteger paletteOk = new AtomicInteger();
    private final AtomicInteger outerOk = new AtomicInteger();
    private JFrame frame;
    private JDialog palette;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("Dialog Targeting Test");
            JPanel panel = new JPanel(new FlowLayout());

            JButton message = new JButton("Message");
            message.setName("message");
            message.addActionListener(e -> JOptionPane.showMessageDialog(frame, "Saved", "Saved",
                JOptionPane.INFORMATION_MESSAGE));
            panel.add(message);

            JButton nested = new JButton("Nested");
            nested.setName("nested");
            nested.addActionListener(e -> openOuterDialog());
            panel.add(nested);

            JPanel area = new JPanel();
            area.setName("area");
            area.setPreferredSize(new java.awt.Dimension(120, 60));
            JPopupMenu popup = new JPopupMenu();
            JMenuItem ask = new JMenuItem("Ask");
            ask.addActionListener(e -> JOptionPane.showMessageDialog(frame, "Asked", "Asked",
                JOptionPane.INFORMATION_MESSAGE));
            popup.add(ask);
            area.setComponentPopupMenu(popup);
            panel.add(area);

            frame.add(panel);
            frame.setSize(400, 200);
            frame.setVisible(true);

            // A tool palette open from startup: created first, non-modal, with an OK button.
            palette = new JDialog(frame, "Palette", false);
            JButton ok = new JButton("OK");
            ok.addActionListener(e -> paletteOk.incrementAndGet());
            palette.add(ok);
            palette.setSize(150, 80);
            palette.setLocation(450, 0);
            palette.setVisible(true);
        });
    }

    private void openOuterDialog() {
        JDialog outer = new JDialog(frame, "Outer", true);
        JPanel content = new JPanel(new FlowLayout());
        JButton inner = new JButton("Inner");
        inner.addActionListener(e -> JOptionPane.showMessageDialog(outer, "Inner message", "Inner",
            JOptionPane.INFORMATION_MESSAGE));
        content.add(inner);
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            outerOk.incrementAndGet();
            outer.dispose();
        });
        content.add(ok);
        outer.add(content);
        outer.setSize(250, 100);
        outer.setVisible(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (frame != null) {
            SwingCommands.disposeAllWindows();
        }
    }

    @Test
    void nonModalPaletteDoesNotShadowAnOptionPane() throws Exception {
        assertPending(commands.call(CommandType.CLICK, Map.of("uid", commands.uidOf("message"))));
        SwingCommands.waitUntil("message dialog shown", () -> SwingCommands.dialogVisible("Saved"));

        CommandResponse handled = commands.call(CommandType.HANDLE_DIALOG, Map.of("button", "OK"));
        assertTrue(handled.success(), handled.error());

        SwingCommands.waitUntil("message dialog closed", () -> !SwingCommands.dialogVisible("Saved"));
        assertEquals(0, paletteOk.get(), "handle_dialog pressed the palette's OK instead");
        assertTrue(palette.isVisible());
    }

    @Test
    void nestedModalDialogsTargetTheInnerOne() throws Exception {
        assertPending(commands.call(CommandType.CLICK, Map.of("uid", commands.uidOf("nested"))));
        SwingCommands.waitUntil("outer dialog shown", () -> SwingCommands.dialogVisible("Outer"));
        assertPending(commands.call(CommandType.HANDLE_DIALOG, Map.of("button", "Inner")));
        SwingCommands.waitUntil("inner dialog shown", () -> SwingCommands.dialogVisible("Inner"));

        CommandResponse handled = commands.call(CommandType.HANDLE_DIALOG, Map.of("button", "OK"));
        assertTrue(handled.success(), handled.error());

        SwingCommands.waitUntil("inner dialog closed", () -> !SwingCommands.dialogVisible("Inner"));
        assertEquals(0, outerOk.get(), "handle_dialog answered the outer dialog instead of the inner one");
        assertTrue(SwingCommands.dialogVisible("Outer"));
        assertEquals(0, paletteOk.get());
    }

    @Test
    void contextMenuItemOpeningAModalDialogHidesThePopup() throws Exception {
        CommandResponse r = commands.call(CommandType.SELECT_CONTEXT_MENU_ITEM,
            Map.of("uid", commands.uidOf("area"), "path", "Ask"));
        assertPending(r);
        SwingCommands.waitUntil("dialog shown", () -> SwingCommands.dialogVisible("Asked"));
        // While the dialog blocks, the context menu must already be gone.
        SwingCommands.waitUntil("no popup on screen", () -> SwingCommands.visiblePopupCount() == 0);

        CommandResponse handled = commands.call(CommandType.HANDLE_DIALOG, Map.of("button", "OK"));
        assertTrue(handled.success(), handled.error());
        SwingCommands.waitUntil("dialog closed", () -> !SwingCommands.dialogVisible("Asked"));
    }

    private static void assertPending(CommandResponse r) {
        assertTrue(r.success(), r.error());
        assertTrue(r.result() instanceof Map<?, ?> m && "pending".equals(m.get("status")),
            "expected a pending result, got " + r.result());
    }
}
