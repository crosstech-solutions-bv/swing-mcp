package solutions.crosstech.swingmcp.common.spi;

import solutions.crosstech.swingmcp.common.dto.SnapshotNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A UI toolkit the agent can drive.
 *
 * <p>One implementation exists per widget toolkit that can live inside a target
 * JVM — {@code SwingToolkit} for AWT/Swing and {@code JavaFxToolkit} for
 * JavaFX. Both are addressed through the same command set, so the MCP tool
 * surface is unchanged: a client never names a toolkit, it just works with
 * whatever the target application happens to use.</p>
 *
 * <h2>Threading</h2>
 * <p>Every toolkit owns exactly one thread on which its scene may be touched —
 * the Event Dispatch Thread for Swing, the JavaFX Application Thread for
 * JavaFX. {@link #invokeOnUiThread} is the only sanctioned way in, and
 * implementations must never let a wedged UI thread hang the agent
 * indefinitely.</p>
 *
 * <h2>Element identity</h2>
 * <p>Each toolkit hands out opaque uids under its own {@link #uidPrefix()}, so
 * {@code ToolkitRegistry} can route a uid back to the toolkit that issued it
 * without ambiguity. This matters in mixed applications, where a Swing frame
 * hosts JavaFX content through a {@code JFXPanel} (or the reverse via
 * {@code SwingNode}) and both toolkits are live in one JVM at once.</p>
 *
 * <p>Implementations are expected to be thread-safe: commands arrive on agent
 * connection threads, not on the UI thread.</p>
 */
public interface UiToolkit {

    /** Stable short name used in diagnostics and in {@code list_windows} output. */
    String id();

    /**
     * Prefix for every uid this toolkit issues, including the trailing
     * separator — for example {@code "comp-"} or {@code "fx-"}. Must be unique
     * across toolkits and must never change once released, because clients hold
     * uids across calls.
     */
    String uidPrefix();

    /**
     * Whether this toolkit is usable in the current JVM right now: its classes
     * are loadable <em>and</em> its UI has actually started. Implementations
     * must answer without throwing, even when the toolkit is entirely absent.
     */
    boolean isAvailable();

    /** Whether the calling thread is this toolkit's UI thread. */
    boolean isUiThread();

    /**
     * Runs a query on the UI thread and waits for its value.
     *
     * @param work      the query; must not block on anything but the scene
     * @param timeoutMs hard cap, after which an {@link IllegalStateException} is
     *                  thrown rather than hanging the agent
     */
    <T> T invokeOnUiThread(Callable<T> work, long timeoutMs) throws Exception;

    /**
     * Posts work to the UI thread without waiting. Used for actions that may
     * open a modal dialog and therefore never return on the UI thread.
     */
    void postToUiThread(Runnable work);

    // ---- scene inspection -------------------------------------------------

    SnapshotNode takeSnapshot(Map<String, Object> params) throws Exception;

    List<Map<String, Object>> listWindows() throws Exception;

    Map<String, Object> getComponentDetails(Map<String, Object> params) throws Exception;

    List<Map<String, Object>> findComponent(Map<String, Object> params) throws Exception;

    Map<String, Object> getTableData(Map<String, Object> params) throws Exception;

    Map<String, Object> getListItems(Map<String, Object> params) throws Exception;

    List<Map<String, Object>> listDialogs() throws Exception;

    Map<String, Object> takeScreenshot(Map<String, Object> params) throws Exception;

    // ---- interaction ------------------------------------------------------

    /**
     * Clicks a component.
     *
     * <p>Returns {@code Object}, not {@code String}, because a click may open a
     * modal dialog and never return on the UI thread; in that case the result is
     * the pending-dialog map described by
     * {@code AbstractUiToolkit.pendingResult()} rather than a confirmation
     * string. The same applies to every other command below that can open a
     * dialog. Collapsing that to a string would throw away the dialog list the
     * client needs in order to recover.</p>
     */
    Object click(Map<String, Object> params) throws Exception;

    String fill(Map<String, Object> params) throws Exception;

    /** May open a modal dialog (a typed ENTER submits a form); see {@link #click}. */
    Object typeText(Map<String, Object> params) throws Exception;

    String focus(Map<String, Object> params) throws Exception;

    String hover(Map<String, Object> params) throws Exception;

    String selectOption(Map<String, Object> params) throws Exception;

    String selectTreeNode(Map<String, Object> params) throws Exception;

    String selectTableCell(Map<String, Object> params) throws Exception;

    /** May open a modal dialog; see {@link #click}. */
    Object selectMenuItem(Map<String, Object> params) throws Exception;

    /** May open a modal dialog; see {@link #click}. */
    Object selectContextMenuItem(Map<String, Object> params) throws Exception;

    /** May open a modal dialog; see {@link #click}. */
    Object handleDialog(Map<String, Object> params) throws Exception;

    /** May open a modal dialog; see {@link #click}. */
    Object pressKey(Map<String, Object> params) throws Exception;

    String drag(Map<String, Object> params) throws Exception;

    String scroll(Map<String, Object> params) throws Exception;

    String waitFor(Map<String, Object> params) throws Exception;

    // ---- windows ----------------------------------------------------------

    String selectWindow(Map<String, Object> params) throws Exception;

    String resizeWindow(Map<String, Object> params) throws Exception;

    String moveWindow(Map<String, Object> params) throws Exception;

    String setWindowState(Map<String, Object> params) throws Exception;

    /** May open a confirm-on-close dialog; see {@link #click}. */
    Object closeWindow(Map<String, Object> params) throws Exception;

    // ---- misc -------------------------------------------------------------

    Map<String, Object> getClipboard() throws Exception;

    String setClipboard(Map<String, Object> params) throws Exception;

    String evaluateJava(Map<String, Object> params) throws Exception;

    /**
     * Thrown by a toolkit for a command it cannot honour, carrying a message
     * the model can act on rather than a bare stack trace.
     */
    class UnsupportedByToolkitException extends UnsupportedOperationException {
        public UnsupportedByToolkitException(String toolkit, String command, String why) {
            super(command + " is not supported on " + toolkit + ": " + why);
        }
    }
}
