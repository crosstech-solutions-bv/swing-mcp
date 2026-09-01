package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for Issue #1: a menu item that opens a modal dialog must not
 * deadlock the agent. {@code SELECT_MENU_ITEM} has to return promptly with a
 * pending/modal indication, {@code LIST_DIALOGS} and {@code HANDLE_DIALOG}
 * must work while the dialog is open, and the menu popup must be cleared.
 *
 * <p>Skipped in headless environments (CI runs it under xvfb).</p>
 */
class ModalDialogCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CommandHandler handler = new CommandHandler(new JsonCodec());
    private JFrame frame;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("Modal Test Frame");
            JMenuBar bar = new JMenuBar();
            JMenu help = new JMenu("Help");
            JMenuItem about = new JMenuItem("About");
            about.addActionListener(e ->
                JOptionPane.showMessageDialog(frame, "About this app", "About", JOptionPane.INFORMATION_MESSAGE));
            help.add(about);
            JMenuItem noop = new JMenuItem("Noop");
            noop.addActionListener(e -> { /* no dialog: plain action */ });
            help.add(noop);
            bar.add(help);
            frame.setJMenuBar(bar);
            frame.setSize(400, 300);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                w.dispose();
            }
        });
    }

    @Test
    void modalMenuItemDoesNotDeadlockAgentAndDialogIsHandleable() throws Exception {
        // 1. select_menu_item must return promptly with a pending/modal result.
        long start = System.currentTimeMillis();
        CommandResponse selectResponse = call(CommandType.SELECT_MENU_ITEM, Map.of("path", "Help > About"));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5_000, "select_menu_item must not block on the modal dialog (took " + elapsed + " ms)");
        assertTrue(selectResponse.success(), "select_menu_item failed: " + selectResponse.error());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) selectResponse.result();
        assertEquals("pending", result.get("status"));
        assertEquals(Boolean.TRUE, result.get("modalDialogOpen"));

        // 2. list_dialogs must work while the modal dialog is open.
        CommandResponse dialogs = call(CommandType.LIST_DIALOGS, Map.of());
        assertTrue(dialogs.success(), "list_dialogs failed: " + dialogs.error());
        assertTrue(String.valueOf(dialogs.result()).contains("About"), "About dialog not listed: " + dialogs.result());

        // 3. handle_dialog(OK) must close it.
        CommandResponse handled = call(CommandType.HANDLE_DIALOG, Map.of("button", "OK"));
        assertTrue(handled.success(), "handle_dialog failed: " + handled.error());

        waitUntil("dialog closed", () -> visibleDialogCount() == 0);

        // 4. The menu popup/selection path must be cleared (Defect C).
        waitUntil("menu selection cleared", () -> {
            AtomicInteger len = new AtomicInteger(-1);
            try {
                SwingUtilities.invokeAndWait(() ->
                    len.set(MenuSelectionManager.defaultManager().getSelectedPath().length));
            } catch (Exception e) {
                return false;
            }
            return len.get() == 0;
        });
    }

    /** Issue #2: a plain menu item must leave no visible popup in the tree. */
    @Test
    void plainMenuItemLeavesNoStalePopup() throws Exception {
        CommandResponse response = call(CommandType.SELECT_MENU_ITEM, Map.of("path", "Help > Noop"));
        assertTrue(response.success(), "select_menu_item failed: " + response.error());

        waitUntil("no visible popup remains", () -> visiblePopupCount() == 0);
        CommandResponse dialogs = call(CommandType.LIST_DIALOGS, Map.of());
        assertTrue(dialogs.success());
    }

    /** Issue #2: after the modal flow, no stale popup remains once the dialog is dismissed. */
    @Test
    void modalMenuFlowLeavesNoStalePopup() throws Exception {
        call(CommandType.SELECT_MENU_ITEM, Map.of("path", "Help > About"));
        CommandResponse handled = call(CommandType.HANDLE_DIALOG, Map.of("button", "OK"));
        assertTrue(handled.success(), "handle_dialog failed: " + handled.error());

        waitUntil("dialog closed", () -> visibleDialogCount() == 0);
        waitUntil("no visible popup remains", () -> visiblePopupCount() == 0);
    }

    private int visiblePopupCount() {
        AtomicInteger count = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> count.set(countVisiblePopups(Window.getWindows())));
        } catch (Exception e) {
            return -1;
        }
        return count.get();
    }

    private int countVisiblePopups(Window[] windows) {
        int n = 0;
        for (Window w : windows) {
            n += countVisiblePopupsIn(w);
        }
        return n;
    }

    private int countVisiblePopupsIn(java.awt.Component comp) {
        int n = 0;
        if (comp instanceof javax.swing.JPopupMenu popup && popup.isShowing()) {
            n++;
        }
        if (comp instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                n += countVisiblePopupsIn(child);
            }
        }
        return n;
    }

    private CommandResponse call(CommandType type, Map<String, Object> params) throws Exception {
        String request = mapper.writeValueAsString(
            new CommandRequest(UUID.randomUUID().toString(), type, params));
        return mapper.readValue(handler.handle(request), CommandResponse.class);
    }

    private int visibleDialogCount() {
        int count = 0;
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && w.isVisible()) {
                count++;
            }
        }
        return count;
    }

    private void waitUntil(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
