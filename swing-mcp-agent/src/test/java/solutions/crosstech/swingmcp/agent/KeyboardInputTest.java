package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #23: press_key and type_text deliver key events to the application
 * itself, so they work while it is in the background and never reach another
 * application.
 */
class KeyboardInputTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CommandHandler handler = new CommandHandler(new JsonCodec());
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicInteger enters = new AtomicInteger();
    private final List<Integer> pressedCodes = new CopyOnWriteArrayList<>();
    private JFrame frame;
    private JTextField first;
    private JTextField second;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        SwingUtilities.invokeAndWait(() -> {
            first = new JTextField(12);
            first.setName("first");
            first.addActionListener(e -> enters.incrementAndGet());
            first.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    pressedCodes.add(e.getKeyCode());
                }
            });
            second = new JTextField(12);
            second.setName("second");
            JMenuItem save = new JMenuItem("Save");
            save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK));
            save.addActionListener(e -> saves.incrementAndGet());
            JMenu file = new JMenu("File");
            file.add(save);
            JMenuBar bar = new JMenuBar();
            bar.add(file);
            frame = new JFrame("Keyboard Test");
            frame.setJMenuBar(bar);
            frame.setLayout(new FlowLayout());
            frame.add(first);
            frame.add(second);
            frame.pack();
            frame.setVisible(true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                w.dispose();
            }
        });
    }

    @Test
    void typeTextInsertsAnyCharacter() throws Exception {
        CommandResponse r = call(CommandType.TYPE_TEXT, Map.of("uid", uidOf("first"), "text", "Hello é€ 42!"));
        assertTrue(r.success(), r.error());
        assertEquals("Hello é€ 42!", textOf(first));
        assertTrue(String.valueOf(r.result()).contains("JTextField"), String.valueOf(r.result()));
    }

    @Test
    void keysReachAWindowInTheBackground() throws Exception {
        String uid = uidOf("first");
        // Another window takes the focus, as the user's editor would.
        AtomicReference<JTextField> elsewhere = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame other = new JFrame("Other Window");
            JTextField field = new JTextField(10);
            other.add(field);
            other.pack();
            other.setLocation(frame.getX() + 40, frame.getY() + 40);
            other.setVisible(true);
            other.toFront();
            field.requestFocus();
            elsewhere.set(field);
        });
        long deadline = System.currentTimeMillis() + 3_000;
        while (!frameFocused(SwingUtilities.getWindowAncestor(elsewhere.get()))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Assumptions.assumeTrue(frameFocused(SwingUtilities.getWindowAncestor(elsewhere.get())),
            "the window manager did not hand focus to the other window");
        Assumptions.assumeFalse(frameFocused(frame), "the target window must be in the background");
        CommandResponse typed = call(CommandType.TYPE_TEXT, Map.of("uid", uid, "text", "abc"));
        assertTrue(typed.success(), typed.error());
        CommandResponse enter = call(CommandType.PRESS_KEY, Map.of("uid", uid, "keys", "ENTER"));
        assertTrue(enter.success(), enter.error());
        assertEquals("abc", textOf(first));
        assertEquals(1, enters.get(), "ENTER fires the field's action");
        assertEquals("", textOf(elsewhere.get()), "nothing may reach the other window");
    }

    @Test
    void acceleratorWorksWithoutUid() throws Exception {
        call(CommandType.FOCUS, Map.of("uid", uidOf("first")));
        CommandResponse r = call(CommandType.PRESS_KEY, Map.of("keys", "CTRL+S"));
        assertTrue(r.success(), r.error());
        waitUntil("menu accelerator", () -> saves.get() == 1);
    }

    @Test
    void tabMovesTheFocus() throws Exception {
        String uid = uidOf("first");
        assertTrue(call(CommandType.PRESS_KEY, Map.of("uid", uid, "keys", "TAB")).success());
        waitUntil("focus moved to the second field", () -> {
            AtomicReference<java.awt.Component> owner = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> owner.set(frame.getMostRecentFocusOwner()));
            } catch (Exception e) {
                return false;
            }
            return owner.get() == second;
        });
    }

    @Test
    void namedKeysAreParsed() throws Exception {
        String uid = uidOf("first");
        for (String key : List.of("F5", "HOME", "PAGE_DOWN", "SHIFT+END")) {
            CommandResponse r = call(CommandType.PRESS_KEY, Map.of("uid", uid, "keys", key));
            assertTrue(r.success(), key + ": " + r.error());
        }
        assertTrue(pressedCodes.containsAll(List.of(KeyEvent.VK_F5, KeyEvent.VK_HOME,
            KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_SHIFT, KeyEvent.VK_END)), pressedCodes.toString());
        CommandResponse unknown = call(CommandType.PRESS_KEY, Map.of("uid", uid, "keys", "HYPERDRIVE"));
        assertFalse(unknown.success());
        assertTrue(unknown.error().contains("Unknown key"), unknown.error());
    }

    @Test
    void keyThatOpensAModalDialogReturnsPending() throws Exception {
        SwingUtilities.invokeAndWait(() -> second.addActionListener(e ->
            JOptionPane.showMessageDialog(frame, "Saved", "Info", JOptionPane.INFORMATION_MESSAGE)));
        long start = System.currentTimeMillis();
        CommandResponse r = call(CommandType.PRESS_KEY, Map.of("uid", uidOf("second"), "keys", "ENTER"));
        assertTrue(System.currentTimeMillis() - start < 5_000, "press_key must not block on the dialog");
        assertTrue(r.success(), r.error());
        assertEquals("pending", ((Map<?, ?>) r.result()).get("status"));
        assertTrue(call(CommandType.HANDLE_DIALOG, Map.of("button", "OK")).success());
        waitUntil("dialog closed", () -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof Dialog && w.isVisible()) {
                    return false;
                }
            }
            return true;
        });
    }

    private static boolean frameFocused(Window w) {
        AtomicReference<Boolean> focused = new AtomicReference<>(false);
        try {
            SwingUtilities.invokeAndWait(() -> focused.set(w.isFocused()));
        } catch (Exception e) {
            return false;
        }
        return focused.get();
    }

    private String textOf(JTextField field) throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> text.set(field.getText()));
        return text.get();
    }

    private String uidOf(String name) throws Exception {
        CommandResponse found = call(CommandType.FIND_COMPONENT, Map.of("query", name, "by", "NAME"));
        assertTrue(found.success(), found.error());
        return String.valueOf(((Map<?, ?>) ((List<?>) found.result()).get(0)).get("uid"));
    }

    private CommandResponse call(CommandType type, Map<String, Object> params) throws Exception {
        String request = mapper.writeValueAsString(new CommandRequest(UUID.randomUUID().toString(), type, params));
        return mapper.readValue(handler.handle(request), CommandResponse.class);
    }

    private static void waitUntil(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
