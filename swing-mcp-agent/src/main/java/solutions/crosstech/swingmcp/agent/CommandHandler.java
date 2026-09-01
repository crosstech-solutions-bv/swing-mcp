package solutions.crosstech.swingmcp.agent;

import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * Handles incoming JSON line commands from the MCP server and dispatches them
 * to the appropriate Swing component operations on the EDT.
 *
 * <p>Action commands that can open a <em>modal</em> dialog ({@code CLICK},
 * {@code SELECT_MENU_ITEM}, {@code SELECT_CONTEXT_MENU_ITEM},
 * {@code HANDLE_DIALOG}) are executed fire-and-poll: the action is posted and
 * awaited for a short bounded time. If it has not completed by then (typically
 * because a modal dialog took over the event pump), a {@code pending} result
 * describing the open dialogs is returned instead of blocking forever, so the
 * client can immediately follow up with {@code list_dialogs} /
 * {@code handle_dialog}.</p>
 */
public class CommandHandler {

    private static final Logger LOG = Logger.getLogger(CommandHandler.class.getName());

    /** How long an action command may run before returning a pending result. */
    private static final long ACTION_WAIT_MS = 500;
    /** Hard cap for EDT round-trips of query commands; prevents infinite hangs. */
    private static final long EDT_TIMEOUT_MS = 20_000;
    /** Bounded wait when collecting dialog info for a pending result. */
    private static final long DIALOG_PROBE_MS = 2_000;

    private final JsonCodec codec;
    private final ComponentScanner scanner;
    private final boolean evaluateEnabled;
    private final String expectedToken;

    /** Test/embedding constructor: no auth token, evaluate_java disabled. */
    public CommandHandler(JsonCodec codec) {
        this(codec, false, null);
    }

    /**
     * @param codec           JSON codec
     * @param evaluateEnabled whether {@code evaluate_java} is permitted (gated here,
     *                        not only server-side, so the toggle is actually enforced)
     * @param expectedToken   per-session auth token; when non-null, every command
     *                        must carry a matching token or it is rejected
     */
    public CommandHandler(JsonCodec codec, boolean evaluateEnabled, String expectedToken) {
        this.codec = codec;
        this.evaluateEnabled = evaluateEnabled;
        this.expectedToken = expectedToken;
        this.scanner = new ComponentScanner();
    }

    /**
     * Processes a single JSON command line and returns a JSON response line.
     *
     * @param jsonLine the incoming JSON command
     * @return a JSON response string
     */
    public String handle(String jsonLine) {
        try {
            CommandRequest request = codec.decodeRequest(jsonLine);
            requireAuthorized(request);
            Object result = dispatch(request);
            CommandResponse response = new CommandResponse(request.requestId(), true, result, null);
            return codec.encode(response);
        } catch (Exception e) {
            // Never log the raw line: it carries the session auth token.
            LOG.log(Level.WARNING, "Error handling command: " + redactToken(jsonLine), e);
            try {
                CommandRequest req = codec.decodeRequest(jsonLine);
                CommandResponse error = new CommandResponse(req.requestId(), false, null, e.getMessage());
                return codec.encode(error);
            } catch (Exception inner) {
                return "{\"success\":false,\"error\":\"Failed to parse request\"}";
            }
        }
    }

    /**
     * Pre-dispatch gate used by {@link AgentServer}: returns an error response
     * line if the command is not authorized, or {@code null} if it is. Lets the
     * server drop the connection on the first bad token instead of letting an
     * unauthenticated peer keep probing.
     */
    String rejectIfUnauthorized(String jsonLine) {
        if (expectedToken == null) {
            return null;
        }
        try {
            CommandRequest request = codec.decodeRequest(jsonLine);
            requireAuthorized(request);
            return null;
        } catch (SecurityException e) {
            LOG.log(Level.WARNING, "Rejected unauthenticated command; closing connection");
            try {
                return codec.encode(new CommandResponse(safeRequestId(jsonLine), false, null, e.getMessage()));
            } catch (Exception encode) {
                return "{\"success\":false,\"error\":\"Unauthorized\"}";
            }
        } catch (Exception parse) {
            return null; // let handle() produce the normal parse error
        }
    }

    private String safeRequestId(String jsonLine) {
        try {
            return codec.decodeRequest(jsonLine).requestId();
        } catch (Exception e) {
            return null;
        }
    }

    /** Masks the token value in a raw request line before it is logged. */
    static String redactToken(String jsonLine) {
        if (jsonLine == null) {
            return null;
        }
        return jsonLine.replaceAll("(\"token\"\\s*:\\s*\")[^\"]*(\")", "$1<redacted>$2");
    }

    /**
     * Rejects commands that do not carry the expected auth token (when one is
     * configured), using a constant-time comparison. When no token is
     * configured (tests/embedding) every command is allowed.
     */
    private void requireAuthorized(CommandRequest request) {
        if (expectedToken == null) {
            return;
        }
        String provided = request.token();
        if (provided == null
            || !java.security.MessageDigest.isEqual(
                    expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    provided.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new SecurityException("Unauthorized: missing or invalid agent token");
        }
    }

    private Object dispatch(CommandRequest request) throws Exception {
        return switch (request.type()) {
            case PING -> "pong";
            case TAKE_SNAPSHOT -> invokeOnEdt(() -> scanner.takeSnapshot(request.params()));
            case LIST_WINDOWS -> invokeOnEdt(scanner::listWindows);
            case SELECT_WINDOW -> invokeOnEdt(() -> scanner.selectWindow(request.params()));
            case RESIZE_WINDOW -> invokeOnEdt(() -> scanner.resizeWindow(request.params()));
            case MOVE_WINDOW -> invokeOnEdt(() -> scanner.moveWindow(request.params()));
            case SET_WINDOW_STATE -> invokeOnEdt(() -> scanner.setWindowState(request.params()));
            case CLOSE_WINDOW -> invokeOnEdt(() -> scanner.closeWindow(request.params()));
            case GET_COMPONENT_DETAILS -> invokeOnEdt(() -> scanner.getComponentDetails(request.params()));
            case FIND_COMPONENT -> invokeOnEdt(() -> scanner.findComponent(request.params()));
            case GET_TABLE_DATA -> invokeOnEdt(() -> scanner.getTableData(request.params()));
            case GET_LIST_ITEMS -> invokeOnEdt(() -> scanner.getListItems(request.params()));
            case TAKE_SCREENSHOT -> scanner.takeScreenshot(request.params());
            case CLICK -> actionOnEdt(() -> scanner.click(request.params()));
            case HOVER -> scanner.hover(request.params());
            case FOCUS -> invokeOnEdt(() -> scanner.focus(request.params()));
            case TYPE_TEXT -> scanner.typeText(request.params());
            case FILL -> invokeOnEdt(() -> scanner.fill(request.params()));
            case SELECT_OPTION -> invokeOnEdt(() -> scanner.selectOption(request.params()));
            case SELECT_TREE_NODE -> invokeOnEdt(() -> scanner.selectTreeNode(request.params()));
            case SELECT_TABLE_CELL -> invokeOnEdt(() -> scanner.selectTableCell(request.params()));
            case SELECT_MENU_ITEM -> actionOnEdt(() -> scanner.selectMenuItem(request.params()));
            case SELECT_CONTEXT_MENU_ITEM -> actionOffEdt(() -> scanner.selectContextMenuItem(request.params()));
            case LIST_DIALOGS -> invokeOnEdt(scanner::listDialogs);
            case HANDLE_DIALOG -> actionOnEdt(() -> scanner.handleDialog(request.params()));
            case GET_CLIPBOARD -> invokeOnEdt(scanner::getClipboard);
            case SET_CLIPBOARD -> invokeOnEdt(() -> scanner.setClipboard(request.params()));
            case PRESS_KEY -> scanner.pressKey(request.params());
            case DRAG -> scanner.drag(request.params());
            case SCROLL -> invokeOnEdt(() -> scanner.scroll(request.params()));
            case WAIT_FOR -> scanner.waitFor(request.params());
            case EVALUATE_JAVA -> {
                if (!evaluateEnabled) {
                    throw new SecurityException("evaluate_java is disabled. "
                        + "Enable it explicitly with swing.mcp.evaluate.enabled=true if you trust the client.");
                }
                yield scanner.evaluateJava(request.params());
            }
        };
    }

    @FunctionalInterface
    private interface EdtCallable<T> {
        T call() throws Exception;
    }

    /**
     * Runs a query callable on the EDT and waits for its result, with a hard
     * timeout so a wedged EDT can never hang the agent forever. Note that a
     * modal dialog does <em>not</em> wedge the EDT (modal dialogs run a nested
     * event pump), so queries keep working while a dialog is open.
     */
    private <T> T invokeOnEdt(EdtCallable<T> callable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return callable.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(EDT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("The Event Dispatch Thread did not respond within "
                + EDT_TIMEOUT_MS + " ms. The application UI may be blocked or busy.");
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    /**
     * Runs an action callable on the EDT, waiting only a short bounded time.
     * If the action opens a modal dialog (and thus does not return), a pending
     * result describing the open dialogs is returned instead of blocking.
     */
    private Object actionOnEdt(EdtCallable<?> callable) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        return awaitActionOrPending(latch, result, error);
    }

    /**
     * Runs an action callable on a worker thread (for actions that drive the
     * EDT internally, e.g. context menus), waiting only a short bounded time.
     */
    private Object actionOffEdt(EdtCallable<?> callable) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        }, "swing-mcp-action");
        worker.setDaemon(true);
        worker.start();
        return awaitActionOrPending(latch, result, error);
    }

    private Object awaitActionOrPending(CountDownLatch latch,
                                        AtomicReference<Object> result,
                                        AtomicReference<Exception> error) throws Exception {
        if (latch.await(ACTION_WAIT_MS, TimeUnit.MILLISECONDS)) {
            if (error.get() != null) {
                throw error.get();
            }
            return result.get();
        }
        return pendingResult();
    }

    /**
     * Builds the result returned when an action did not complete within the
     * bounded wait — almost always because it opened a modal dialog.
     */
    private Map<String, Object> pendingResult() {
        List<Map<String, Object>> dialogs = probeDialogs();
        boolean modalOpen = dialogs.stream()
            .anyMatch(d -> Boolean.TRUE.equals(d.get("modal")));
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("status", "pending");
        pending.put("modalDialogOpen", modalOpen);
        pending.put("dialogs", dialogs);
        pending.put("note", "The action has not completed yet"
            + (modalOpen ? " because it opened a modal dialog" : "")
            + ". Use list_dialogs and handle_dialog to inspect and dismiss any open dialog; "
            + "the action will finish once the dialog is closed.");
        return pending;
    }

    /** Collects dialog info on the EDT with a bounded wait; empty on failure. */
    private List<Map<String, Object>> probeDialogs() {
        AtomicReference<List<Map<String, Object>>> ref = new AtomicReference<>(List.of());
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                ref.set(scanner.listDialogs());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to probe dialogs", e);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(DIALOG_PROBE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }
}
