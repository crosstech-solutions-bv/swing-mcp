package solutions.crosstech.swingmcp.agent;

import solutions.crosstech.swingmcp.agent.toolkit.ToolkitRegistry;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.spi.UiToolkit;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles incoming JSON line commands from the MCP server and dispatches them
 * to the UI toolkit that owns the component in question.
 *
 * <p>Dispatch is toolkit-agnostic: {@link ToolkitRegistry} routes each command
 * to Swing or JavaFX based on the uid it carries, so the same command set drives
 * either, and both at once in a mixed application. Threading and the modal-dialog
 * protocol belong to the toolkit, because the rules differ between them.</p>
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

    private final JsonCodec codec;
    private final ToolkitRegistry toolkits;
    private final boolean evaluateEnabled;
    private final String expectedToken;
    private final Runnable onShutdown;

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
        this(codec, evaluateEnabled, expectedToken, (Runnable) null);
    }

    /**
     * @param onShutdown run on {@code SHUTDOWN}: stops the agent's socket server
     *                   (never the target JVM); null makes {@code SHUTDOWN} a no-op
     */
    public CommandHandler(JsonCodec codec, boolean evaluateEnabled, String expectedToken, Runnable onShutdown) {
        this(codec, evaluateEnabled, expectedToken, new ToolkitRegistry(), onShutdown);
    }

    /** Test seam: inject a registry with stub toolkits. */
    CommandHandler(JsonCodec codec, boolean evaluateEnabled, String expectedToken,
                   ToolkitRegistry toolkits) {
        this(codec, evaluateEnabled, expectedToken, toolkits, null);
    }

    CommandHandler(JsonCodec codec, boolean evaluateEnabled, String expectedToken,
                   ToolkitRegistry toolkits, Runnable onShutdown) {
        this.codec = codec;
        this.evaluateEnabled = evaluateEnabled;
        this.expectedToken = expectedToken;
        this.toolkits = toolkits;
        this.onShutdown = onShutdown;
    }

    /** The toolkits discovered in this JVM; used by the agent for its banner. */
    public ToolkitRegistry toolkits() {
        return toolkits;
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
        Map<String, Object> p = request.params();
        // Toolkit-independent commands first: resolving a toolkit can scan every
        // UI thread's windows, and fails for a uid no toolkit issued.
        switch (request.type()) {
            case PING:
                return "pong";
            case LIST_WINDOWS:
                return toolkits.listAllWindows();
            case SHUTDOWN:
                if (onShutdown != null) {
                    onShutdown.run();
                }
                return "stopped";
            default:
                break;
        }
        UiToolkit t = toolkits.forParams(p);
        return switch (request.type()) {
            case PING, LIST_WINDOWS, SHUTDOWN -> throw new IllegalStateException("handled above");

            // Scene inspection
            case TAKE_SNAPSHOT -> t.takeSnapshot(p);
            case GET_COMPONENT_DETAILS -> t.getComponentDetails(p);
            case FIND_COMPONENT -> t.findComponent(p);
            case GET_TABLE_DATA -> t.getTableData(p);
            case GET_LIST_ITEMS -> t.getListItems(p);
            case LIST_DIALOGS -> t.listDialogs();
            case TAKE_SCREENSHOT -> t.takeScreenshot(p);

            // Interaction
            case CLICK -> t.click(p);
            case HOVER -> t.hover(p);
            case FOCUS -> t.focus(p);
            case TYPE_TEXT -> t.typeText(p);
            case FILL -> t.fill(p);
            case SELECT_OPTION -> t.selectOption(p);
            case SELECT_TREE_NODE -> t.selectTreeNode(p);
            case SELECT_TABLE_CELL -> t.selectTableCell(p);
            case SELECT_MENU_ITEM -> t.selectMenuItem(p);
            case SELECT_CONTEXT_MENU_ITEM -> t.selectContextMenuItem(p);
            case HANDLE_DIALOG -> t.handleDialog(p);
            case PRESS_KEY -> t.pressKey(p);
            case DRAG -> t.drag(p);
            case SCROLL -> t.scroll(p);
            case MOUSE_WHEEL -> t.mouseWheel(p);
            case WAIT_FOR -> t.waitFor(p);

            // Windows
            case SELECT_WINDOW -> t.selectWindow(p);
            case RESIZE_WINDOW -> t.resizeWindow(p);
            case MOVE_WINDOW -> t.moveWindow(p);
            case SET_WINDOW_STATE -> t.setWindowState(p);
            case CLOSE_WINDOW -> t.closeWindow(p);

            // Misc
            case GET_CLIPBOARD -> t.getClipboard();
            case SET_CLIPBOARD -> t.setClipboard(p);
            case EVALUATE_JAVA -> {
                if (!evaluateEnabled) {
                    throw new SecurityException("evaluate_java is disabled. "
                        + "Enable it explicitly with swing.mcp.evaluate.enabled=true if you trust the client.");
                }
                yield t.evaluateJava(p);
            }
        };
    }
}
