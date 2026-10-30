package solutions.crosstech.swingmcp.agent.toolkit;

import solutions.crosstech.swingmcp.agent.ComponentScanner;
import solutions.crosstech.swingmcp.common.dto.SnapshotNode;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * AWT/Swing implementation, delegating to the existing {@link ComponentScanner}.
 *
 * <p>This class deliberately adds no scanning logic of its own: it is a thin
 * adapter that puts the long-standing Swing behaviour behind the shared
 * toolkit interface, so introducing a second toolkit cannot change what Swing
 * users already rely on. The threading policy applied here is the same one
 * that previously lived in {@code CommandHandler}.</p>
 */
public class SwingToolkit extends AbstractUiToolkit {

    /** Kept for backwards compatibility: released uids look like {@code comp-12}. */
    public static final String UID_PREFIX = "comp-";

    private final ComponentScanner scanner;

    public SwingToolkit() {
        this(new ComponentScanner());
    }

    public SwingToolkit(ComponentScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String id() {
        return "swing";
    }

    @Override
    public String uidPrefix() {
        return UID_PREFIX;
    }

    @Override
    public boolean isAvailable() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return false;
            }
            // Loadable and initialised is not enough: require a real window, so a
            // JavaFX-only app that merely has AWT on the classpath is not claimed.
            for (Window w : Window.getWindows()) {
                if (w.isDisplayable()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isUiThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    @Override
    public void postToUiThread(Runnable work) {
        SwingUtilities.invokeLater(work);
    }

    @Override
    protected String uiThreadName() {
        return "Event Dispatch Thread";
    }

    @Override
    protected List<Map<String, Object>> listDialogsOnUiThread() {
        return scanner.listDialogs();
    }

    // ---- scene inspection (marshalled onto the EDT) ------------------------

    @Override
    public SnapshotNode takeSnapshot(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.takeSnapshot(p));
    }

    @Override
    public List<Map<String, Object>> listWindows() throws Exception {
        return onUi(scanner::listWindows);
    }

    @Override
    public Map<String, Object> getComponentDetails(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.getComponentDetails(p));
    }

    @Override
    public List<Map<String, Object>> findComponent(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.findComponent(p));
    }

    @Override
    public Map<String, Object> getTableData(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.getTableData(p));
    }

    @Override
    public Map<String, Object> getListItems(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.getListItems(p));
    }

    @Override
    public List<Map<String, Object>> listDialogs() throws Exception {
        return onUi(scanner::listDialogs);
    }

    /** Screenshots use {@code java.awt.Robot} and must not run on the EDT. */
    @Override
    public Map<String, Object> takeScreenshot(Map<String, Object> p) throws Exception {
        return scanner.takeScreenshot(p);
    }

    // ---- interaction ------------------------------------------------------

    /** May open a modal dialog, so fire-and-poll. */
    @Override
    public Object click(Map<String, Object> p) throws Exception {
        return action(() -> scanner.click(p));
    }

    /** Document and change listeners may open a modal dialog (validation), so fire-and-poll. */
    @Override
    public String fill(Map<String, Object> p) throws Exception {
        return actionText(() -> scanner.fill(p));
    }

    /**
     * {@link #action} for commands whose result is text: a pending result is
     * reported as text too, so a listener that opens a modal dialog yields a
     * prompt answer instead of a UI-thread timeout.
     */
    private String actionText(Callable<String> work) throws Exception {
        Object result = action(work);
        return result instanceof Map<?, ?> pending ? pendingText(pending) : (String) result;
    }

    private static String pendingText(Map<?, ?> pending) {
        StringBuilder text = new StringBuilder("Pending: ").append(pending.get("note"));
        if (pending.get("dialogs") instanceof List<?> dialogs && !dialogs.isEmpty()) {
            text.append(" Open dialogs: ").append(dialogs);
        }
        return text.toString();
    }

    /**
     * Event-based typing may open a modal dialog (e.g. ENTER on a form), so it is
     * fire-and-poll, with time for one EDT turn per character. Robot typing stays
     * synchronous: it must not overlap with whatever the client does next.
     */
    @Override
    public Object typeText(Map<String, Object> p) throws Exception {
        if (robot(p)) {
            return scanner.typeText(p);
        }
        int length = p.get("text") == null ? 0 : String.valueOf(p.get("text")).length();
        return actionOffUi(() -> scanner.typeText(p), Math.min(UI_TIMEOUT_MS, ACTION_WAIT_MS + 20L * length));
    }

    private static boolean robot(Map<String, Object> p) {
        return Boolean.parseBoolean(String.valueOf(p.get("robot")));
    }

    /** Focus listeners may open a modal dialog (validation on focus change), so fire-and-poll. */
    @Override
    public String focus(Map<String, Object> p) throws Exception {
        return actionText(() -> scanner.focus(p));
    }

    /**
     * Mouse-entered handlers may open a modal dialog, so event-based hovering is
     * fire-and-poll. Robot hovering stays synchronous, like robot typing.
     */
    @Override
    public String hover(Map<String, Object> p) throws Exception {
        if (robot(p)) {
            return scanner.hover(p);
        }
        return actionText(() -> scanner.hover(p));
    }

    /** Selection listeners may open a modal dialog, so the selection commands are fire-and-poll. */
    @Override
    public String selectOption(Map<String, Object> p) throws Exception {
        return actionText(() -> scanner.selectOption(p));
    }

    @Override
    public String selectTreeNode(Map<String, Object> p) throws Exception {
        return actionText(() -> scanner.selectTreeNode(p));
    }

    @Override
    public String selectTableCell(Map<String, Object> p) throws Exception {
        return actionText(() -> scanner.selectTableCell(p));
    }

    @Override
    public Object selectMenuItem(Map<String, Object> p) throws Exception {
        return action(() -> scanner.selectMenuItem(p));
    }

    @Override
    public Object selectContextMenuItem(Map<String, Object> p) throws Exception {
        return actionOffUi(() -> scanner.selectContextMenuItem(p));
    }

    @Override
    public Object handleDialog(Map<String, Object> p) throws Exception {
        return action(() -> scanner.handleDialog(p));
    }

    /** A key may open a modal dialog (CTRL+O, ENTER), so event-based presses are fire-and-poll. */
    @Override
    public Object pressKey(Map<String, Object> p) throws Exception {
        if (robot(p)) {
            return scanner.pressKey(p);
        }
        return actionOffUi(() -> scanner.pressKey(p));
    }

    /**
     * A drop handler may open a modal dialog, so event-based drags are
     * fire-and-poll. Robot drags stay synchronous, like robot typing.
     */
    @Override
    public String drag(Map<String, Object> p) throws Exception {
        if (robot(p)) {
            return scanner.drag(p);
        }
        return actionText(() -> scanner.drag(p));
    }

    /** The application's wheel or scroll handlers may open a modal dialog, so fire-and-poll. */
    @Override
    public Object scroll(Map<String, Object> p) throws Exception {
        return action(() -> scanner.scroll(p));
    }

    /** May open a modal dialog, so fire-and-poll. */
    @Override
    public Object mouseWheel(Map<String, Object> p) throws Exception {
        return action(() -> scanner.mouseWheel(p));
    }

    /** Polls from the command thread; each check runs on the EDT, bounded by the time left. */
    @Override
    public String waitFor(Map<String, Object> p) throws Exception {
        return scanner.waitFor(p);
    }

    // ---- windows ----------------------------------------------------------

    @Override
    public String selectWindow(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.selectWindow(p));
    }

    @Override
    public String resizeWindow(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.resizeWindow(p));
    }

    @Override
    public String moveWindow(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.moveWindow(p));
    }

    @Override
    public String setWindowState(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.setWindowState(p));
    }

    /** Closing listeners may ask for confirmation in a modal dialog, so fire-and-poll. */
    @Override
    public Object closeWindow(Map<String, Object> p) throws Exception {
        return action(() -> scanner.closeWindow(p));
    }

    // ---- misc -------------------------------------------------------------

    @Override
    public Map<String, Object> getClipboard() throws Exception {
        return onUi(scanner::getClipboard);
    }

    @Override
    public String setClipboard(Map<String, Object> p) throws Exception {
        return onUi(() -> scanner.setClipboard(p));
    }

    @Override
    public String evaluateJava(Map<String, Object> p) throws Exception {
        return scanner.evaluateJava(p);
    }
}
