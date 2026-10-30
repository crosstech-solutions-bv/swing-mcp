package solutions.crosstech.swingmcp.demofx;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import solutions.crosstech.swingmcp.agent.toolkit.JavaFxToolkit;
import solutions.crosstech.swingmcp.common.dto.ComponentDescriptor;
import solutions.crosstech.swingmcp.common.dto.SnapshotNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real JavaFX demo application through {@link JavaFxToolkit}.
 *
 * <p>This is the test that makes the JavaFX claim credible: it starts a genuine
 * scene graph, then reads and manipulates it through exactly the operations the
 * MCP commands call, with no mocks in between.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoFxToolkitIT {

    private static JavaFxToolkit toolkit;

    @BeforeAll
    static void startUi() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(20, TimeUnit.SECONDS), "JavaFX toolkit did not start");

        CountDownLatch shown = new CountDownLatch(1);
        Platform.runLater(() -> {
            new DemoFxApp().start(new Stage());
            shown.countDown();
        });
        assertTrue(shown.await(20, TimeUnit.SECONDS), "demo window did not open");
        toolkit = new JavaFxToolkit();
    }

    @AfterAll
    static void stopUi() {
        Platform.exit();
    }

    // ---- helpers ----------------------------------------------------------

    /** Resolves a control's uid the way a client does: find_component by id. */
    private String uidOf(String id) throws Exception {
        List<Map<String, Object>> hits = toolkit.findComponent(Map.of("name", id));
        assertFalse(hits.isEmpty(), "no component with id " + id);
        return String.valueOf(hits.get(0).get("uid"));
    }

    private static void flatten(List<ComponentDescriptor> in, List<ComponentDescriptor> out) {
        if (in == null) {
            return;
        }
        for (ComponentDescriptor d : in) {
            out.add(d);
            flatten(d.children(), out);
        }
    }

    private List<ComponentDescriptor> allNodes(SnapshotNode snapshot) {
        List<ComponentDescriptor> flat = new ArrayList<>();
        flatten(snapshot.components(), flat);
        return flat;
    }

    /** Runs a query on the FX thread and waits for it. */
    private static <T> T fx(Callable<T> work) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.call());
            } catch (Exception e) {
                err.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX thread did not respond");
        if (err.get() != null) {
            throw err.get();
        }
        return out.get();
    }

    /** Polls until {@code condition} holds, failing after five seconds. */
    private static void waitUntil(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.call()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for: " + what);
            Thread.sleep(50);
        }
    }

    private static ListView<?> logList() throws Exception {
        return fx(() -> (ListView<?>) Window.getWindows().get(0).getScene().lookup("#logList"));
    }

    /** Index of the first visible row of the Canvas tab's log list, or -1 before it has laid out. */
    private static int firstVisibleLogLine() throws Exception {
        ListView<?> list = logList();
        return fx(() -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
            return flow == null || flow.getFirstVisibleCell() == null ? -1 : flow.getFirstVisibleCell().getIndex();
        });
    }

    private void showCanvasTab() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Canvas"));
        waitUntil("logList laid out", () -> firstVisibleLogLine() >= 0);
    }

    private String canvasState() throws Exception {
        return String.valueOf(toolkit.getComponentDetails(Map.of("uid", uidOf("canvasState"))).get("text"));
    }

    // ---- tests ------------------------------------------------------------

    @Test
    @DisplayName("the toolkit reports itself live and owns the fx- uid space")
    void availability() {
        assertTrue(toolkit.isAvailable());
        assertEquals("javafx", toolkit.id());
        assertEquals("fx-", toolkit.uidPrefix());
    }

    @Test
    @DisplayName("snapshot returns the window and its controls")
    void snapshotFindsControls() throws Exception {
        SnapshotNode snapshot = toolkit.takeSnapshot(Map.of());
        assertEquals("Swing MCP JavaFX Demo", snapshot.windowTitle());
        List<ComponentDescriptor> nodes = allNodes(snapshot);
        List<String> ids = nodes.stream().map(ComponentDescriptor::name).filter(java.util.Objects::nonNull).toList();
        assertTrue(ids.contains("nameField"), "expected nameField in " + ids);
        assertTrue(ids.contains("submitButton"), "expected submitButton in " + ids);
        assertTrue(ids.contains("statusLabel"), "expected statusLabel in " + ids);
        assertTrue(nodes.stream().allMatch(d -> d.uid().startsWith("fx-")));
    }

    @Test
    @DisplayName("a snapshot can start at a node, stop at a depth, and reports tooltips (#21)")
    void snapshotScope() throws Exception {
        SnapshotNode shallow = toolkit.takeSnapshot(Map.of("maxDepth", 1));
        assertEquals(Boolean.TRUE, shallow.truncated());
        assertTrue(allNodes(shallow).stream().anyMatch(d -> d.omittedChildren() != null),
            "a node cut off by maxDepth must say how many children it has");

        String submit = uidOf("submitButton");
        SnapshotNode subtree = toolkit.takeSnapshot(Map.of("uid", submit));
        assertEquals(1, subtree.components().size());
        ComponentDescriptor root = subtree.components().get(0);
        assertEquals(submit, root.uid());
        assertEquals("Submit the form", root.tooltip());
        assertEquals("Submit the form", toolkit.getComponentDetails(Map.of("uid", submit)).get("tooltip"));
    }

    @Test
    @DisplayName("the server's VISIBLE_ONLY filter drops invisible nodes (#21)")
    void visibleOnlyFilter() throws Exception {
        String status = uidOf("statusLabel");
        toolkit.invokeOnUiThread(() -> {
            javafx.stage.Window.getWindows().get(0).getScene().lookup("#statusLabel").setVisible(false);
            return null;
        }, 5000);
        try {
            List<ComponentDescriptor> all = allNodes(toolkit.takeSnapshot(Map.of("filter", "ALL")));
            assertTrue(all.stream().anyMatch(d -> d.uid().equals(status)), "ALL keeps the hidden label");
            List<ComponentDescriptor> visible = allNodes(toolkit.takeSnapshot(Map.of("filter", "VISIBLE_ONLY")));
            assertTrue(visible.stream().noneMatch(d -> d.uid().equals(status)), "VISIBLE_ONLY must drop it");
        } finally {
            toolkit.invokeOnUiThread(() -> {
                javafx.stage.Window.getWindows().get(0).getScene().lookup("#statusLabel").setVisible(true);
                return null;
            }, 5000);
        }
    }

    @Test
    @DisplayName("skin internals are filtered out of the snapshot")
    void snapshotExcludesSkinInternals() throws Exception {
        SnapshotNode snapshot = toolkit.takeSnapshot(Map.of());
        List<ComponentDescriptor> nodes = allNodes(snapshot);
        // LabeledText, and the Path/Text nodes a TextField uses for its caret and
        // selection, are rendering details. A model must never see them.
        List<String> classes = nodes.stream().map(ComponentDescriptor::componentClass).toList();
        assertFalse(classes.contains("LabeledText"), "skin internals leaked: " + classes);
        assertFalse(classes.contains("CheckBoxSkin"), "skin internals leaked: " + classes);
        // A Button must appear as one node, not as a subtree.
        ComponentDescriptor submit = nodes.stream()
            .filter(d -> "submitButton".equals(d.name())).findFirst().orElseThrow();
        assertTrue(submit.children() == null || submit.children().isEmpty(),
            "Button should be a leaf but had " + submit.children());
    }

    @Test
    @DisplayName("a button carries its label as text")
    void controlsExposeTheirValues() throws Exception {
        Map<String, Object> details = toolkit.getComponentDetails(Map.of("uid", uidOf("submitButton")));
        assertEquals("Submit", details.get("text"));
        assertEquals("Button", details.get("componentClass"));
        assertEquals(Boolean.TRUE, details.get("enabled"));
    }

    @Test
    @DisplayName("fill writes into a text field and the value reads back")
    void fillAndReadBack() throws Exception {
        String uid = uidOf("nameField");
        toolkit.fill(Map.of("uid", uid, "text", "Tinus"));
        assertEquals("Tinus", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"));
    }

    @Test
    @DisplayName("filling a spinner commits the value, not just the editor text")
    void fillSpinnerCommitsValue() throws Exception {
        // Regression: the editor showed 42 while the spinner's value stayed 1, so the
        // application's submit handler read the wrong number.
        String uid = uidOf("quantitySpinner");
        toolkit.fill(Map.of("uid", uid, "text", "42"));
        assertEquals("42", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"));
    }

    @Test
    @DisplayName("filling a non-text control fails with a message naming the type")
    void fillRejectsWrongControl() throws Exception {
        String uid = uidOf("submitButton");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> toolkit.fill(Map.of("uid", uid, "text", "x")));
        assertTrue(e.getMessage().contains("Button"), e.getMessage());
    }

    @Test
    @DisplayName("combo box options are listed and selectable by text")
    void selectComboOption() throws Exception {
        String uid = uidOf("countryCombo");
        Map<String, Object> items = toolkit.getListItems(Map.of("uid", uid));
        assertEquals(3, items.get("itemCount"));
        toolkit.selectOption(Map.of("uid", uid, "text", "South Africa"));
        assertEquals("South Africa", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"));
    }

    @Test
    @DisplayName("a check box toggles and reports its state")
    void toggleCheckBox() throws Exception {
        String uid = uidOf("subscribeCheck");
        assertEquals("unselected", toolkit.getComponentDetails(Map.of("uid", uid)).get("selectionState"));
        toolkit.selectOption(Map.of("uid", uid));
        assertEquals("selected", toolkit.getComponentDetails(Map.of("uid", uid)).get("selectionState"));
    }

    @Test
    @DisplayName("clicking submit runs the application's own handler")
    void clickRunsTheHandler() throws Exception {
        toolkit.fill(Map.of("uid", uidOf("nameField"), "text", "Ada"));
        toolkit.selectOption(Map.of("uid", uidOf("countryCombo"), "text", "Netherlands"));
        toolkit.click(Map.of("uid", uidOf("submitButton")));
        String result = String.valueOf(
            toolkit.getComponentDetails(Map.of("uid", uidOf("formResult"))).get("text"));
        assertTrue(result.startsWith("submitted: Ada"), result);
        assertTrue(result.contains("Netherlands"), result);
    }

    @Test
    @DisplayName("table data comes back as rows and columns, not pixels")
    void readTable() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Data"));
        Map<String, Object> data = toolkit.getTableData(Map.of("uid", uidOf("partsTable")));
        assertEquals(List.of("SKU", "Name", "Quantity"), data.get("columns"));
        assertEquals(3, data.get("rowCount"));
        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) data.get("rows");
        assertEquals("SKU-1", rows.get(0).get(0));
        assertEquals("Bearing", rows.get(0).get(1));
        assertEquals("48", rows.get(1).get(2));
    }

    @Test
    @DisplayName("table rows can be toggled, extended and set as a whole (#18)")
    void multiSelectTableRows() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Data"));
        String table = uidOf("partsTable");
        toolkit.selectTableCell(Map.of("uid", table, "row", 0));
        String toggled = toolkit.selectTableCell(Map.of("uid", table, "row", 2, "toggle", true));
        assertTrue(toggled.contains("rows [0, 2]"), toggled);
        String untoggled = toolkit.selectTableCell(Map.of("uid", table, "row", 0, "toggle", true));
        assertTrue(untoggled.contains("rows [2]"), untoggled);
        toolkit.selectTableCell(Map.of("uid", table, "row", 0));
        String extended = toolkit.selectTableCell(Map.of("uid", table, "row", 2, "extend", true));
        assertTrue(extended.contains("rows [0, 1, 2]"), extended);
        String rows = toolkit.selectTableCell(Map.of("uid", table, "rows", List.of(1, 2)));
        assertTrue(rows.contains("rows [1, 2]"), rows);
    }

    @Test
    @DisplayName("press_key and type_text accept a target uid (#23)")
    void keysGoToTheGivenUid() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Form"));
        String uid = uidOf("nameField");
        toolkit.fill(Map.of("uid", uid, "text", ""));
        toolkit.typeText(Map.of("uid", uid, "text", "Ada"));
        assertEquals("Ada", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"));
        Object pressed = toolkit.pressKey(Map.of("uid", uid, "keys", "HOME"));
        assertTrue(String.valueOf(pressed).contains("HOME"), String.valueOf(pressed));
    }

    @Test
    @DisplayName("a click lands at x/y inside the node with modifiers held (#19)")
    void clickAtPointWithModifiers() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Data"));
        String uid = uidOf("cityList");
        List<javafx.scene.input.MouseEvent> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        javafx.event.EventHandler<javafx.scene.input.MouseEvent> recorder = seen::add;
        javafx.scene.Node list = toolkit.invokeOnUiThread(() -> {
            javafx.scene.Node n = javafx.stage.Window.getWindows().get(0).getScene().lookup("#cityList");
            n.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, recorder);
            return n;
        }, 5000);
        try {
            toolkit.click(Map.of("uid", uid, "x", 12, "y", 9, "modifiers", "CTRL+SHIFT"));
            long deadline = System.currentTimeMillis() + 5000;
            while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(seen.isEmpty(), "no click reached the list");
            javafx.scene.input.MouseEvent click = seen.get(0);
            assertEquals(12, click.getX(), 0.5);
            assertEquals(9, click.getY(), 0.5);
            assertTrue(click.isControlDown() && click.isShiftDown(), "modifiers held");
            assertThrows(IllegalArgumentException.class,
                () -> toolkit.hover(Map.of("uid", uid, "x", 100_000, "y", 0)));
        } finally {
            toolkit.invokeOnUiThread(() -> {
                list.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, recorder);
                return null;
            }, 5000);
        }
    }

    @Test
    @DisplayName("list items are readable and selectable")
    void readAndSelectList() throws Exception {
        String uid = uidOf("cityList");
        Map<String, Object> items = toolkit.getListItems(Map.of("uid", uid));
        assertEquals(4, items.get("itemCount"));
        toolkit.selectOption(Map.of("uid", uid, "text", "Utrecht"));
        assertEquals("index 2", toolkit.getComponentDetails(Map.of("uid", uid)).get("selectionState"));
    }

    @Test
    @DisplayName("a tree node is selectable by path")
    void selectTreeNodeByPath() throws Exception {
        String uid = uidOf("regionTree");
        toolkit.selectTreeNode(Map.of("uid", uid, "path", "Regions > Europe > Belgium"));
        assertEquals("Belgium", toolkit.getComponentDetails(Map.of("uid", uid)).get("selectionState"));
    }

    @Test
    @DisplayName("an unknown uid is rejected with advice, not a null pointer")
    void unknownUid() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> toolkit.getComponentDetails(Map.of("uid", "fx-999999")));
        assertTrue(e.getMessage().contains("fresh snapshot"), e.getMessage());
    }

    @Test
    @DisplayName("windows are listed with the toolkit that owns them")
    void listWindows() throws Exception {
        List<Map<String, Object>> windows = toolkit.listWindows();
        assertFalse(windows.isEmpty());
        assertEquals("javafx", windows.get(0).get("toolkit"));
        assertEquals("Swing MCP JavaFX Demo", windows.get(0).get("title"));
    }

    @Test
    @DisplayName("a screenshot is produced from the scene graph")
    void screenshot() throws Exception {
        Map<String, Object> shot = toolkit.takeScreenshot(Map.of());
        assertNotNull(shot.get("imageBase64"));
        assertEquals("image/png", shot.get("mimeType"));
        assertTrue(((Integer) shot.get("width")) > 0);
    }

    @Test
    @DisplayName("waitFor succeeds once the expected text is present")
    void waitForText() throws Exception {
        toolkit.fill(Map.of("uid", uidOf("nameField"), "text", "Grace"));
        toolkit.click(Map.of("uid", uidOf("submitButton")));
        assertTrue(toolkit.waitFor(Map.of("text", "submitted: Grace", "timeoutMs", 3000)).startsWith("Condition met"));
    }

    @Test
    @DisplayName("typeText inserts each character exactly once")
    void typeTextInsertsOnce() throws Exception {
        // Regression: an earlier version dispatched KEY_TYPED *and* appended the
        // character, so "abc" came out as "aabbcc". The control's own handler
        // does the inserting; the toolkit must not.
        String uid = uidOf("nameField");
        toolkit.fill(Map.of("uid", uid, "text", ""));
        toolkit.typeText(Map.of("uid", uid, "text", "abc"));
        assertEquals("abc", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"));
    }

    @Test
    @DisplayName("a modal Alert reports pending, is listed with its real text, and is dismissable")
    void modalDialogRoundTrip() throws Exception {
        // The whole fire-and-poll protocol on JavaFX: the menu action opens an
        // Alert via showAndWait, which nests an event loop and never returns, so
        // the toolkit must come back with a pending result instead of hanging.
        Object result = toolkit.selectMenuItem(Map.of("path", "Help > About"));
        assertTrue(result instanceof Map, "expected a pending map, got " + result);
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) result;
        assertEquals("pending", pending.get("status"));
        assertEquals(Boolean.TRUE, pending.get("modalDialogOpen"));

        List<Map<String, Object>> dialogs = toolkit.listDialogs();
        assertEquals(1, dialogs.size(), "dialogs: " + dialogs);
        Map<String, Object> about = dialogs.get(0);
        assertEquals("About", about.get("title"));
        assertEquals(Boolean.TRUE, about.get("modal"));
        // The message must be what the user sees, read from the DialogPane, not
        // whichever Label the graph walk happens to hit first.
        assertEquals("Used for testing the swing-mcp server.", about.get("message"));
        assertEquals("Swing MCP JavaFX Demo", about.get("header"));
        assertEquals(List.of("OK"), about.get("buttons"));

        assertEquals("Pressed OK", toolkit.handleDialog(Map.of("button", "OK")));
        assertTrue(toolkit.listDialogs().isEmpty(), "dialog should be gone");
    }

    @Test
    @DisplayName("commands a toolkit cannot serve say so clearly")
    void unsupportedCommandsExplainThemselves() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
            () -> toolkit.evaluateJava(Map.of("code", "1+1")));
        assertTrue(e.getMessage().contains("javafx"), e.getMessage());
    }

    @Test
    @DisplayName("mouse_wheel zooms a custom canvas, and modifiers pan it instead")
    void mouseWheelDrivesCustomCanvas() throws Exception {
        showCanvasTab();
        String canvas = uidOf("graphCanvas");
        String before = canvasState();
        assertTrue(before.startsWith("Canvas zoom "), before);
        int zoom = Integer.parseInt(before.replaceAll("Canvas zoom (\\d+)%.*", "$1"));

        String result = String.valueOf(toolkit.mouseWheel(Map.of("uid", canvas, "rotation", -2)));
        assertTrue(result.contains("2 notch"), result);
        assertTrue(canvasState().startsWith("Canvas zoom " + (zoom + 20) + "%"), canvasState());

        String zoomed = canvasState();
        toolkit.mouseWheel(Map.of("uid", canvas, "rotation", 1, "x", 10, "y", 10, "modifiers", "CTRL"));
        assertTrue(canvasState().startsWith("Canvas zoom " + (zoom + 20) + "%"), "CTRL must pan, not zoom");
        assertFalse(canvasState().equals(zoomed), "CTRL+wheel moved the view: " + canvasState());
    }

    @Test
    @DisplayName("scroll moves a ListView, which has no enclosing ScrollPane")
    void scrollFallsBackToWheelOnListView() throws Exception {
        showCanvasTab();
        ListView<?> list = logList();
        fx(() -> {
            list.scrollTo(0);
            return null;
        });
        waitUntil("logList at the top", () -> firstVisibleLogLine() == 0);
        String result = String.valueOf(toolkit.scroll(Map.of("uid", uidOf("logList"), "direction", "DOWN", "amount", 5)));
        assertTrue(result.contains("mouse-wheel"), result);
        waitUntil("logList scrolled down", () -> firstVisibleLogLine() > 0);
    }

    @Test
    @DisplayName("mouse_wheel fires ScrollEvents like a physical wheel's, at the point, with SHORTCUT")
    void mouseWheelEventsMatchAPhysicalWheel() throws Exception {
        showCanvasTab();
        String canvas = uidOf("graphCanvas");
        List<ScrollEvent> events = new CopyOnWriteArrayList<>();
        Node node = fx(() -> Window.getWindows().get(0).getScene().lookup("#graphCanvas"));
        EventHandler<ScrollEvent> filter = events::add;
        fx(() -> {
            node.addEventFilter(ScrollEvent.SCROLL, filter);
            return null;
        });
        try {
            toolkit.mouseWheel(Map.of("uid", canvas, "rotation", 2, "x", 30, "y", 40, "modifiers", "SHORTCUT"));
            assertEquals(2, events.size(), "one event per notch");
            for (ScrollEvent e : events) {
                // As JavaFX reports one notch of a physical wheel: 40 px, multiplier 40, no gesture totals.
                assertEquals(-40, e.getDeltaY(), 0.001, "positive rotation scrolls down");
                assertEquals(0, e.getDeltaX(), 0.001);
                assertEquals(40, e.getMultiplierY(), 0.001);
                assertEquals(0, e.getTotalDeltaY(), 0.001);
                assertEquals(30, e.getX(), 0.5);
                assertEquals(40, e.getY(), 0.5);
                assertTrue(e.isShortcutDown(), "SHORTCUT held");
            }
        } finally {
            fx(() -> {
                node.removeEventFilter(ScrollEvent.SCROLL, filter);
                return null;
            });
        }
    }

    @Test
    @DisplayName("scroll without an amount moves three notches, the same default as with a scroll pane")
    void scrollDefaultsToThreeUnits() throws Exception {
        showCanvasTab();
        String before = canvasState();
        int zoom = Integer.parseInt(before.replaceAll("Canvas zoom (\\d+)%.*", "$1"));
        String result = String.valueOf(toolkit.scroll(Map.of("uid", uidOf("graphCanvas"), "direction", "UP")));
        assertTrue(result.contains("by 3 mouse-wheel notch(es)"), result);
        assertTrue(canvasState().startsWith("Canvas zoom " + (zoom + 30) + "%"), canvasState());
    }

    @Test
    @DisplayName("mouse_wheel rejects a point outside the component, an unknown modifier and zero notches")
    void mouseWheelValidatesInput() throws Exception {
        showCanvasTab();
        String canvas = uidOf("graphCanvas");
        assertThrows(IllegalArgumentException.class,
            () -> toolkit.mouseWheel(Map.of("uid", canvas, "rotation", 1, "x", 99_999, "y", 1)));
        assertThrows(IllegalArgumentException.class,
            () -> toolkit.mouseWheel(Map.of("uid", canvas, "rotation", 1, "modifiers", "HYPER")));
        assertThrows(IllegalArgumentException.class,
            () -> toolkit.mouseWheel(Map.of("uid", canvas, "rotation", 0)));
    }

    // ---- ranges, paths and uids ---------------------------------------------

    @Test
    @DisplayName("table endRow is inclusive, as on the wire, and truncated means rows were left out")
    @SuppressWarnings("unchecked")
    void tableRangeIsInclusive() throws Exception {
        String uid = uidOf("partsTable");
        Map<String, Object> firstTwo = toolkit.getTableData(Map.of("uid", uid, "startRow", 0, "endRow", 1));
        List<List<String>> rows = (List<List<String>>) firstTwo.get("rows");
        assertEquals(2, rows.size(), "rows 0..1: " + rows);
        assertEquals("SKU-2", rows.get(1).get(0));
        assertEquals(Boolean.TRUE, firstTwo.get("truncated"));

        Map<String, Object> all = toolkit.getTableData(Map.of("uid", uid, "startRow", 0, "endRow", 2));
        assertEquals(3, ((List<?>) all.get("rows")).size());
        assertNull(all.get("truncated"), "the last row was returned");

        Map<String, Object> rest = toolkit.getTableData(Map.of("uid", uid, "startRow", 1));
        rows = (List<List<String>>) rest.get("rows");
        assertEquals(List.of("SKU-2", "SKU-3"), rows.stream().map(r -> r.get(0)).toList());
        assertNull(rest.get("truncated"));

        Map<String, Object> capped = toolkit.getTableData(Map.of("uid", uid, "maxRows", 1));
        assertEquals(1, ((List<?>) capped.get("rows")).size(), "maxRows still caps an open range");
        assertEquals(Boolean.TRUE, capped.get("truncated"));
    }

    @Test
    @DisplayName("list endIndex is inclusive, as on the wire")
    void listRangeIsInclusive() throws Exception {
        String uid = uidOf("cityList");
        Map<String, Object> middle = toolkit.getListItems(Map.of("uid", uid, "startIndex", 1, "endIndex", 2));
        assertEquals(List.of("Rotterdam", "Utrecht"), middle.get("items"));
        assertEquals(Boolean.TRUE, middle.get("truncated"));
        Map<String, Object> last = toolkit.getListItems(Map.of("uid", uid, "startIndex", 3, "endIndex", 3));
        assertEquals(List.of("Cape Town"), last.get("items"));
        assertNull(last.get("truncated"));
    }

    @Test
    @DisplayName("a tree path uses ' > ' like Swing, and a slash is part of a name")
    void treePathUsesSwingSeparator() throws Exception {
        String uid = uidOf("regionTree");
        toolkit.selectTreeNode(Map.of("uid", uid, "path", "Europe>Netherlands"));
        assertEquals("Netherlands", toolkit.getComponentDetails(Map.of("uid", uid)).get("selectionState"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> toolkit.selectTreeNode(Map.of("uid", uid, "path", "Regions/Europe/Belgium")));
        assertTrue(e.getMessage().contains("Regions/Europe/Belgium"), e.getMessage());
    }

    @Test
    @DisplayName("a node detached and re-attached keeps a uid that still resolves")
    void reattachedNodeKeepsItsUid() throws Exception {
        String uid = uidOf("statusLabel");
        javafx.scene.layout.BorderPane root = fx(() -> (javafx.scene.layout.BorderPane) mainStage().getScene().getRoot());
        Node status = fx(root::getBottom);
        fx(() -> {
            root.setBottom(null);
            return null;
        });
        try {
            toolkit.takeSnapshot(Map.of()); // prunes the detached label
            assertThrows(IllegalArgumentException.class, () -> toolkit.getComponentDetails(Map.of("uid", uid)));
        } finally {
            fx(() -> {
                root.setBottom(status);
                return null;
            });
        }
        assertEquals("Ready", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"),
            "the old uid must resolve once the node is back");
        assertTrue(allNodes(toolkit.takeSnapshot(Map.of())).stream().anyMatch(d -> d.uid().equals(uid)),
            "a snapshot must hand out the same uid again");
        assertEquals(uid, uidOf("statusLabel"));
    }

    @Test
    @DisplayName("a menu item keeps one uid across snapshots")
    void menuItemUidIsStable() throws Exception {
        String first = menuUid("About");
        assertEquals(first, menuUid("About"));
        assertEquals("MenuItem", toolkit.getComponentDetails(Map.of("uid", first)).get("componentClass"));
    }

    private String menuUid(String text) throws Exception {
        return allNodes(toolkit.takeSnapshot(Map.of())).stream()
            .filter(d -> text.equals(d.text()) && "MenuItem".equals(d.componentClass()))
            .map(ComponentDescriptor::uid).findFirst().orElseThrow();
    }

    // ---- keys -----------------------------------------------------------------

    @Test
    @DisplayName("press_key types printable keys, honouring SHIFT, and not with SHORTCUT held")
    void pressKeyTypesCharacters() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Form"));
        String uid = uidOf("nameField");
        toolkit.fill(Map.of("uid", uid, "text", ""));
        List<KeyEvent> seen = new CopyOnWriteArrayList<>();
        Node field = fx(() -> mainStage().getScene().lookup("#nameField"));
        EventHandler<KeyEvent> recorder = seen::add;
        fx(() -> {
            field.addEventFilter(KeyEvent.ANY, recorder);
            return null;
        });
        try {
            toolkit.pressKey(Map.of("uid", uid, "keys", "A"));
            toolkit.pressKey(Map.of("uid", uid, "keys", "SHIFT+b"));
            toolkit.pressKey(Map.of("uid", uid, "keys", "1"));
            assertEquals("aB1", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"));
            List<KeyCode> pressed = codes(seen, KeyEvent.KEY_PRESSED);
            assertTrue(pressed.containsAll(List.of(KeyCode.A, KeyCode.SHIFT, KeyCode.B, KeyCode.DIGIT1)), "" + pressed);

            seen.clear();
            toolkit.pressKey(Map.of("uid", uid, "keys", "SHORTCUT+A"));
            assertEquals("aB1", toolkit.getComponentDetails(Map.of("uid", uid)).get("text"),
                "a shortcut chord must not type");
            assertTrue(seen.stream().anyMatch(e -> e.getEventType() == KeyEvent.KEY_PRESSED
                && e.getCode() == KeyCode.A && e.isShortcutDown()), "SHORTCUT held: " + seen);
            assertTrue(seen.stream().noneMatch(e -> e.getEventType() == KeyEvent.KEY_TYPED), "" + seen);
        } finally {
            fx(() -> {
                field.removeEventFilter(KeyEvent.ANY, recorder);
                return null;
            });
        }
    }

    @Test
    @DisplayName("press_key accepts the key names Swing accepts")
    void pressKeyAcceptsSwingKeyNames() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Form"));
        String uid = uidOf("nameField");
        List<KeyEvent> seen = new CopyOnWriteArrayList<>();
        Node field = fx(() -> mainStage().getScene().lookup("#nameField"));
        EventHandler<KeyEvent> recorder = seen::add;
        fx(() -> {
            field.addEventFilter(KeyEvent.KEY_PRESSED, recorder);
            return null;
        });
        try {
            for (String key : List.of("ESC", "RETURN", "PGUP", "PGDN", "DEL", "BACKSPACE", "F5", "Page Up", "OPTION+X")) {
                toolkit.pressKey(Map.of("uid", uid, "keys", key));
            }
            assertEquals(List.of(KeyCode.ESCAPE, KeyCode.ENTER, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN, KeyCode.DELETE,
                KeyCode.BACK_SPACE, KeyCode.F5, KeyCode.PAGE_UP, KeyCode.ALT, KeyCode.X), codes(seen, KeyEvent.KEY_PRESSED));
            assertTrue(seen.get(seen.size() - 1).isAltDown(), "OPTION is ALT");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> toolkit.pressKey(Map.of("uid", uid, "keys", "CTRL+HYPERKEY")));
            assertTrue(e.getMessage().contains("HYPERKEY"), e.getMessage());
        } finally {
            fx(() -> {
                field.removeEventFilter(KeyEvent.KEY_PRESSED, recorder);
                return null;
            });
        }
    }

    private static List<KeyCode> codes(List<KeyEvent> events, javafx.event.EventType<KeyEvent> type) {
        return events.stream().filter(e -> e.getEventType() == type).map(KeyEvent::getCode).toList();
    }

    @Test
    @DisplayName("type_text that opens a modal dialog comes back pending instead of blocking")
    void typeTextOpeningDialogIsPending() throws Exception {
        toolkit.selectOption(Map.of("uid", uidOf("tabs"), "text", "Form"));
        String uid = uidOf("nameField");
        toolkit.fill(Map.of("uid", uid, "text", ""));
        Node field = fx(() -> mainStage().getScene().lookup("#nameField"));
        EventHandler<KeyEvent> shout = e -> {
            if ("!".equals(e.getCharacter())) {
                Alert alert = new Alert(Alert.AlertType.WARNING, "No shouting");
                alert.setTitle("Typed");
                alert.initOwner(mainStage());
                alert.showAndWait();
            }
        };
        fx(() -> {
            field.addEventHandler(KeyEvent.KEY_TYPED, shout);
            return null;
        });
        try {
            Map<String, Object> pending = pending(toolkit.typeText(Map.of("uid", uid, "text", "hi!")));
            assertEquals(Boolean.TRUE, pending.get("modalDialogOpen"));
            assertTrue(dialogTitles(pending).contains("Typed"), "" + pending);
            assertEquals("Pressed OK", toolkit.handleDialog(Map.of("button", "OK")));
            waitUntil("typing finished", () -> "hi!".equals(
                toolkit.getComponentDetails(Map.of("uid", uid)).get("text")));
        } finally {
            fx(() -> {
                field.removeEventHandler(KeyEvent.KEY_TYPED, shout);
                return null;
            });
        }
    }

    // ---- windows and dialogs ----------------------------------------------------

    private static Stage mainStage() {
        return (Stage) Window.getWindows().get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pending(Object result) {
        assertTrue(result instanceof Map, "expected a pending map, got " + result);
        Map<String, Object> pending = (Map<String, Object>) result;
        assertEquals("pending", pending.get("status"));
        return pending;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dialogTitles(Map<String, Object> pending) {
        return ((List<Map<String, Object>>) pending.get("dialogs")).stream().map(d -> d.get("title")).toList();
    }

    private int windowIndex(String title) throws Exception {
        for (Map<String, Object> w : toolkit.listWindows()) {
            if (title.equals(w.get("title"))) {
                return (Integer) w.get("index");
            }
        }
        throw new AssertionError("no window titled " + title);
    }

    private static Stage showEditor(EventHandler<javafx.stage.WindowEvent> onCloseRequest) throws Exception {
        return fx(() -> {
            Stage s = new Stage();
            s.setTitle("Editor");
            s.setScene(new Scene(new VBox(new Label("unsaved document")), 240, 120));
            s.setOnCloseRequest(onCloseRequest);
            s.show();
            return s;
        });
    }

    @Test
    @DisplayName("close_window runs onCloseRequest, and a consumed request keeps the window open")
    void closeWindowHonoursVeto() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Stage editor = showEditor(e -> {
            attempts.incrementAndGet();
            e.consume();
        });
        try {
            Object vetoed = toolkit.closeWindow(Map.of("windowIndex", windowIndex("Editor")));
            assertTrue(String.valueOf(vetoed).contains("vetoed"), String.valueOf(vetoed));
            assertEquals(1, attempts.get(), "onCloseRequest must run");
            assertTrue(fx(editor::isShowing));

            fx(() -> {
                editor.setOnCloseRequest(e -> attempts.incrementAndGet());
                return null;
            });
            assertEquals("Closed Editor", toolkit.closeWindow(Map.of("windowIndex", windowIndex("Editor"))));
            assertEquals(2, attempts.get());
            assertFalse(fx(editor::isShowing));
        } finally {
            fx(() -> {
                editor.close();
                return null;
            });
        }
    }

    @Test
    @DisplayName("close_window with a save-changes prompt comes back pending and obeys the answer")
    void closeWindowWithPromptIsPending() throws Exception {
        ButtonType discard = new ButtonType("Discard");
        ButtonType cancel = new ButtonType("Cancel");
        AtomicReference<Stage> ref = new AtomicReference<>();
        Stage editor = showEditor(e -> {
            Alert prompt = new Alert(Alert.AlertType.CONFIRMATION, "Save changes?", discard, cancel);
            prompt.setTitle("Unsaved changes");
            prompt.initOwner(ref.get());
            if (prompt.showAndWait().orElse(cancel) != discard) {
                e.consume();
            }
        });
        ref.set(editor);
        try {
            Map<String, Object> pending = pending(toolkit.closeWindow(Map.of("windowIndex", windowIndex("Editor"))));
            assertEquals(Boolean.TRUE, pending.get("modalDialogOpen"));
            assertTrue(dialogTitles(pending).contains("Unsaved changes"), "" + pending);
            assertEquals("Pressed Cancel", toolkit.handleDialog(Map.of("button", "Cancel")));
            waitUntil("prompt gone", () -> toolkit.listDialogs().isEmpty());
            assertTrue(fx(editor::isShowing), "Cancel keeps the window open");

            pending(toolkit.closeWindow(Map.of("windowIndex", windowIndex("Editor"))));
            assertEquals("Pressed Discard", toolkit.handleDialog(Map.of("button", "Discard")));
            waitUntil("editor closed", () -> !fx(editor::isShowing));
        } finally {
            fx(() -> {
                editor.setOnCloseRequest(null);
                editor.close();
                return null;
            });
        }
    }

    @Test
    @DisplayName("handle_dialog acts on the topmost of two nested modal dialogs")
    void handleDialogTargetsTopmost() throws Exception {
        AtomicInteger outerPressed = new AtomicInteger();
        Stage outer = fx(() -> {
            Stage s = new Stage();
            s.initOwner(mainStage());
            s.initModality(Modality.WINDOW_MODAL);
            s.setTitle("Outer");
            Button ok = new Button("OK");
            ok.setOnAction(e -> {
                outerPressed.incrementAndGet();
                s.close();
            });
            s.setScene(new Scene(new VBox(ok), 240, 120));
            s.show();
            return s;
        });
        AtomicReference<Alert> inner = new AtomicReference<>();
        try {
            openInner(outer, inner);
            assertEquals("Pressed OK", toolkit.handleDialog(Map.of("button", "OK")));
            waitUntil("inner dismissed", () -> !fx(() -> showing(inner.get())));
            assertEquals(0, outerPressed.get(), "the OK of the dialog underneath was pressed");
            assertTrue(fx(outer::isShowing));

            openInner(outer, inner);
            assertEquals("Closed dialog", toolkit.handleDialog(Map.of()));
            waitUntil("inner closed", () -> !fx(() -> showing(inner.get())));
            assertTrue(fx(outer::isShowing), "without a button only the topmost dialog closes");

            assertEquals("Closed dialog", toolkit.handleDialog(Map.of()));
            waitUntil("outer closed", () -> !fx(outer::isShowing));
            assertEquals(0, outerPressed.get());
        } finally {
            fx(() -> {
                if (inner.get() != null && showing(inner.get())) {
                    ((Stage) inner.get().getDialogPane().getScene().getWindow()).close();
                }
                outer.close();
                return null;
            });
        }
    }

    private static void openInner(Stage owner, AtomicReference<Alert> inner) throws Exception {
        inner.set(null);
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Nested");
            alert.setTitle("Inner");
            alert.initOwner(owner);
            inner.set(alert);
            alert.showAndWait();
        });
        waitUntil("inner dialog shown", () -> fx(() -> inner.get() != null && showing(inner.get())));
    }

    /** Whether a dialog's window is on screen; read from the window, which handle_dialog may close directly. */
    private static boolean showing(Alert alert) {
        Scene sc = alert.getDialogPane().getScene();
        return sc != null && sc.getWindow() != null && sc.getWindow().isShowing();
    }
}
