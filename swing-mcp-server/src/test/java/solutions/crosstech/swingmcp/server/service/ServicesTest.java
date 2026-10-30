package solutions.crosstech.swingmcp.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.config.SwingMcpProperties;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import solutions.crosstech.swingmcp.server.session.AgentConnection;
import solutions.crosstech.swingmcp.server.session.AttachedAppSession;
import solutions.crosstech.swingmcp.server.session.FakeAgentServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the command-forwarding services end-to-end against a
 * {@link FakeAgentServer} speaking the real line protocol.
 */
class ServicesTest {

    private final ConcurrentLinkedQueue<CommandType> received = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CommandRequest> requests = new ConcurrentLinkedQueue<>();
    private FakeAgentServer server;
    private SessionRegistry registry;
    private SwingMcpProperties properties;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeAgentServer(req -> {
            received.add(req.type());
            requests.add(req);
            if (req.type() == CommandType.TAKE_SCREENSHOT) {
                return new CommandResponse(req.requestId(), true, screenshotPayload(), null);
            }
            return new CommandResponse(req.requestId(), true, req.type().name(), null);
        });
        AgentConnection connection = AgentConnection.connect(server.port(), 5000);
        registry = new SessionRegistry();
        registry.set(new AttachedAppSession(ProcessHandle.current().pid(), connection, server.port()));
        properties = new SwingMcpProperties();
        properties.setScreenshotDir(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.clear();
        server.close();
    }

    @Test
    void snapshotServiceSendsCommands() {
        SnapshotService service = new SnapshotService(registry);
        assertEquals("TAKE_SNAPSHOT", service.takeSnapshot(null));
        assertEquals("TAKE_SNAPSHOT", service.takeSnapshot(null, "VISIBLE_ONLY"));
        assertEquals("GET_COMPONENT_DETAILS", service.componentDetails("comp-1"));
        assertEquals("FIND_COMPONENT", service.findComponent("Save", "TEXT"));
        assertEquals("GET_TABLE_DATA", service.tableData("comp-2", 0, 10));
        assertEquals("GET_LIST_ITEMS", service.listItems("comp-3", null, null));
        assertTrue(received.containsAll(java.util.List.of(
            CommandType.TAKE_SNAPSHOT, CommandType.GET_COMPONENT_DETAILS,
            CommandType.FIND_COMPONENT, CommandType.GET_TABLE_DATA,
            CommandType.GET_LIST_ITEMS)));
    }

    @Test
    void snapshotServiceForwardsScopeParams() {
        SnapshotService service = new SnapshotService(registry);
        service.takeSnapshot(null, null, "comp-7", 2, 50);
        assertEquals(Map.of("uid", "comp-7", "maxDepth", 2, "maxNodes", 50), requests.poll().params());
        service.takeSnapshot(1, "VISIBLE_ONLY");
        assertEquals(Map.of("windowIndex", 1, "filter", "VISIBLE_ONLY"), requests.poll().params());
    }

    @Test
    void windowServiceSendsCommands() {
        WindowService service = new WindowService(registry);
        service.listWindows();
        service.selectWindow(0);
        service.resizeWindow(800, 600);
        service.moveWindow(100, 50);
        service.setWindowState("MAXIMIZED");
        service.closeWindow();
        assertTrue(received.containsAll(java.util.List.of(
            CommandType.LIST_WINDOWS, CommandType.SELECT_WINDOW,
            CommandType.RESIZE_WINDOW, CommandType.MOVE_WINDOW,
            CommandType.SET_WINDOW_STATE, CommandType.CLOSE_WINDOW)));
    }

    @Test
    void interactionServiceSendsCommands() {
        InteractionService service = new InteractionService(registry);
        service.click("comp-1", null, null);
        service.fill("comp-2", "hello");
        service.selectOption("comp-3", 1, null);
        service.selectTreeNode("comp-4", "Root > Leaf");
        service.selectTableCell("comp-5", 1, 2);
        service.selectMenuItem("File > Exit");
        service.pressKey("ENTER");
        service.drag("comp-6", "comp-7");
        service.scroll("comp-8", "DOWN", 3);
        service.hover("comp-9");
        service.focus("comp-10");
        service.typeText("hello", "comp-10");
        service.selectContextMenuItem("comp-11", "Copy");
        service.mouseWheel("comp-12", -2, 10, 20, "CTRL", null);
        assertEquals(14, received.size());
        assertTrue(received.containsAll(java.util.List.of(
            CommandType.HOVER, CommandType.FOCUS,
            CommandType.TYPE_TEXT, CommandType.SELECT_CONTEXT_MENU_ITEM,
            CommandType.MOUSE_WHEEL)));
    }

    @Test
    void selectTableCellForwardsMultiSelectParams() {
        InteractionService service = new InteractionService(registry);
        service.selectTableCell("comp-5", 3, null, true, null, null);
        service.selectTableCell("comp-5", null, 1, null, null, java.util.List.of(0, 2));
        CommandRequest toggle = requests.poll();
        assertEquals(Map.of("uid", "comp-5", "row", 3, "toggle", true), toggle.params());
        CommandRequest rows = requests.poll();
        assertEquals(Map.of("uid", "comp-5", "col", 1, "rows", java.util.List.of(0, 2)), rows.params());
    }

    @Test
    void keyCommandsForwardUidAndRobot() {
        InteractionService service = new InteractionService(registry);
        service.pressKey("ENTER", "comp-4", null);
        assertEquals(Map.of("keys", "ENTER", "uid", "comp-4"), requests.poll().params());
        service.typeText("abc", null, true);
        assertEquals(Map.of("text", "abc", "robot", true), requests.poll().params());
    }

    @Test
    void pointerCommandsForwardOffsetsModifiersAndRobot() {
        InteractionService service = new InteractionService(registry);
        service.click("comp-1", null, null, 10, 20, "CTRL", true);
        assertEquals(Map.of("uid", "comp-1", "x", 10, "y", 20, "modifiers", "CTRL", "robot", true),
            requests.poll().params());
        service.hover("comp-2", 3, null, null);
        assertEquals(Map.of("uid", "comp-2", "x", 3), requests.poll().params());
        service.drag("comp-3", null, 1, 2, 30, 40, null);
        assertEquals(Map.of("fromUid", "comp-3", "fromX", 1, "fromY", 2, "toX", 30, "toY", 40),
            requests.poll().params());
        service.mouseWheel("comp-4", -2, 5, null, "SHORTCUT", true);
        assertEquals(Map.of("uid", "comp-4", "rotation", -2, "x", 5, "modifiers", "SHORTCUT", "robot", true),
            requests.poll().params());
        service.mouseWheel("comp-4", 1, null, null, " ", null);
        assertEquals(Map.of("uid", "comp-4", "rotation", 1), requests.poll().params());
    }

    @Test
    void dialogServiceSendsCommands() {
        DialogService service = new DialogService(registry);
        service.listDialogs();
        service.handleDialog("OK", null, null);
        service.handleDialog(null, "/tmp/file.txt", 1);
        assertTrue(received.containsAll(java.util.List.of(
            CommandType.LIST_DIALOGS, CommandType.HANDLE_DIALOG)));
    }

    @Test
    void clipboardServiceSendsCommands() {
        ClipboardService service = new ClipboardService(registry);
        service.getClipboard();
        service.setClipboard("hello");
        assertTrue(received.containsAll(java.util.List.of(
            CommandType.GET_CLIPBOARD, CommandType.SET_CLIPBOARD)));
    }

    @Test
    void waitServiceSendsCommand() {
        WaitService service = new WaitService(registry);
        assertEquals("WAIT_FOR", service.waitFor("WINDOW_TITLE", null, "Demo", 1000L));
        assertTrue(received.contains(CommandType.WAIT_FOR));
    }

    /** A wait longer than the tool timeout is not cut short by the server. */
    @Test
    void waitLongerThanTheToolTimeoutIsAwaited() throws Exception {
        try (FakeAgentServer slow = new FakeAgentServer(req -> {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new CommandResponse(req.requestId(), true, "MET", null);
            })) {
            SessionRegistry slowRegistry = new SessionRegistry();
            slowRegistry.set(new AttachedAppSession(ProcessHandle.current().pid(),
                AgentConnection.connect(slow.port(), 200), slow.port()));
            try {
                assertEquals("MET", new WaitService(slowRegistry).waitFor("EDT_IDLE", null, null, 1000L));
            } finally {
                slowRegistry.clear();
            }
        }
    }

    @Test
    void missingRequiredParametersAreNamed() {
        InteractionService interaction = new InteractionService(registry);
        assertMissing("text", () -> interaction.fill("comp-1", null));
        assertMissing("uid", () -> interaction.fill(null, "x"));
        assertMissing("uid", () -> interaction.focus(null));
        assertMissing("text", () -> interaction.typeText(null, null));
        assertMissing("path", () -> interaction.selectMenuItem(null));
        assertMissing("path", () -> interaction.selectTreeNode("comp-1", null));
        assertMissing("path", () -> interaction.selectContextMenuItem("comp-1", null));
        assertMissing("keys", () -> interaction.pressKey(null));
        assertMissing("text", () -> new ClipboardService(registry).setClipboard(null));
        assertMissing("uid", () -> new SnapshotService(registry).componentDetails(null));
        assertMissing("query", () -> new SnapshotService(registry).findComponent(null, null));
        assertMissing("state", () -> new WindowService(registry).setWindowState(null));
        assertMissing("conditionType", () -> new WaitService(registry).waitFor(null, null, null, null));
        properties.getEvaluate().setEnabled(true);
        assertMissing("code", () -> new EvaluateService(registry, properties).evaluate(null));
        assertTrue(received.isEmpty(), "nothing may be sent: " + received);
    }

    private static void assertMissing(String name, org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertEquals("Missing required parameter: " + name, e.getMessage());
    }

    @Test
    void toolTimeoutMustBePositiveAndFitAnInt() {
        SwingMcpProperties p = new SwingMcpProperties();
        assertThrows(IllegalArgumentException.class, () -> p.setToolTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> p.setToolTimeoutMs(-1));
        assertThrows(IllegalArgumentException.class, () -> p.setToolTimeoutMs(Integer.MAX_VALUE + 1L));
        p.setToolTimeoutMs(1);
        assertEquals(1, p.getToolTimeoutMs());
    }

    @Test
    void evaluateServiceIsDisabledByDefault() {
        EvaluateService service = new EvaluateService(registry, properties);
        assertThrows(IllegalStateException.class, () -> service.evaluate("1+1"));
        assertTrue(received.isEmpty());
    }

    @Test
    void evaluateServiceSendsWhenEnabled() {
        properties.getEvaluate().setEnabled(true);
        EvaluateService service = new EvaluateService(registry, properties);
        assertEquals("EVALUATE_JAVA", service.evaluate("1+1"));
        assertTrue(received.contains(CommandType.EVALUATE_JAVA));
    }

    @Test
    void screenshotServiceWritesPngFile() throws Exception {
        ScreenshotService service = new ScreenshotService(registry, properties);
        Map<String, Object> result = service.screenshot(null);
        Path path = Path.of(String.valueOf(result.get("path")));
        assertTrue(Files.isRegularFile(path));
        assertTrue(Files.size(path) > 0);
        assertEquals("image/png", result.get("mimeType"));
        assertFalse(result.containsKey("imageBase64"));
    }

    @Test
    void screenshotServiceReturnsInlineImageWhenRequested() {
        ScreenshotService service = new ScreenshotService(registry, properties);
        Map<String, Object> result = service.screenshot(null, true);
        assertTrue(result.containsKey("imageBase64"));
        assertTrue(String.valueOf(result.get("imageBase64")).length() > 0);
    }

    private static Map<String, Object> screenshotPayload() {
        try {
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return Map.of(
                "imageBase64", Base64.getEncoder().encodeToString(baos.toByteArray()),
                "mimeType", "image/png",
                "width", 4,
                "height", 4);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
