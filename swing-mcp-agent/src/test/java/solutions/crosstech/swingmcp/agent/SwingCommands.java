package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/** Sends commands through a {@link CommandHandler}, as the server would, plus shared test helpers. */
final class SwingCommands {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CommandHandler handler = new CommandHandler(new JsonCodec());

    CommandResponse call(CommandType type, Map<String, Object> params) throws Exception {
        String request = mapper.writeValueAsString(new CommandRequest(UUID.randomUUID().toString(), type, params));
        return mapper.readValue(handler.handle(request), CommandResponse.class);
    }

    /** The uid of the first component with this name. */
    String uidOf(String name) throws Exception {
        CommandResponse found = call(CommandType.FIND_COMPONENT, Map.of("query", name, "by", "NAME"));
        assertTrue(found.success(), found.error());
        List<?> matches = (List<?>) found.result();
        assertTrue(!matches.isEmpty(), "no component named " + name);
        return String.valueOf(((Map<?, ?>) matches.get(0)).get("uid"));
    }

    static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    static int visibleDialogCount() {
        int count = 0;
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && w.isVisible()) {
                count++;
            }
        }
        return count;
    }

    static boolean dialogVisible(String title) {
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog d && d.isVisible() && title.equals(d.getTitle())) {
                return true;
            }
        }
        return false;
    }

    /** Showing popup menus across all windows, counted on the EDT; -1 if the EDT did not answer. */
    static int visiblePopupCount() {
        AtomicInteger count = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (Window w : Window.getWindows()) {
                    count.addAndGet(popupsIn(w));
                }
            });
        } catch (Exception e) {
            return -1;
        }
        return count.get();
    }

    private static int popupsIn(Component comp) {
        int n = comp instanceof JPopupMenu popup && popup.isShowing() ? 1 : 0;
        if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                n += popupsIn(child);
            }
        }
        return n;
    }

    /** Disposes every window. Posted, not awaited, so a still-open modal dialog cannot hang it. */
    static void disposeAllWindows() throws Exception {
        SwingUtilities.invokeLater(() -> {
            for (Window w : Window.getWindows()) {
                w.dispose();
            }
        });
        waitUntil("windows disposed", () -> {
            for (Window w : Window.getWindows()) {
                if (w.isDisplayable()) {
                    return false;
                }
            }
            return true;
        });
    }
}
