package solutions.crosstech.swingmcp.agent.toolkit;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import solutions.crosstech.swingmcp.agent.ClassNames;
import solutions.crosstech.swingmcp.common.dto.ComponentDescriptor;
import solutions.crosstech.swingmcp.common.dto.SnapshotNode;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JavaFX implementation of the agent's toolkit interface.
 *
 * <p>Loaded reflectively by {@link ToolkitRegistry} and only when JavaFX is
 * actually present, so this class — and every {@code javafx.*} type it names —
 * is never resolved inside a Swing-only application.</p>
 *
 * <p>Interaction is done through each control's own API ({@code fire()},
 * {@code setText}, selection models) rather than by synthesising native input.
 * That is deliberate: it is deterministic, works when the window is not
 * focused, and cannot be thrown off by DPI scaling or a screensaver. Where a
 * control has no programmatic equivalent, a JavaFX event is dispatched to the
 * node instead, which still runs the application's own handlers.</p>
 */
public class JavaFxToolkit extends AbstractUiToolkit {

    private static final int DEFAULT_MAX_NODES = 2000;
    private static final int DEFAULT_MAX_ROWS = 200;
    /**
     * Pixels per wheel notch, and the multiplier a physical wheel's ScrollEvent
     * reports with it: JavaFX's own default on every desktop platform.
     */
    private static final double WHEEL_NOTCH_PX = 40;

    private final FxScene scene = new FxScene();
    private volatile Window activeWindow;

    @Override
    public String id() {
        return "javafx";
    }

    @Override
    public String uidPrefix() {
        return FxScene.UID_PREFIX;
    }

    @Override
    public boolean isAvailable() {
        try {
            // Touching Window.getWindows() before the toolkit starts is safe and
            // simply yields an empty list, which is exactly the "not live" answer.
            return Platform.isFxApplicationThread() || !Window.getWindows().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isUiThread() {
        return Platform.isFxApplicationThread();
    }

    @Override
    public void postToUiThread(Runnable work) {
        Platform.runLater(work);
    }

    @Override
    protected String uiThreadName() {
        return "JavaFX Application Thread";
    }

    // ---- window helpers ---------------------------------------------------

    private List<Window> windows() {
        List<Window> out = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Target window: an explicit index, else the last-selected, else the focused one. */
    private Window targetWindow(Map<String, Object> params) {
        List<Window> all = windows();
        if (all.isEmpty()) {
            return null;
        }
        Object idx = params == null ? null : params.get("windowIndex");
        if (idx != null) {
            int i = ((Number) idx).intValue();
            if (i < 0 || i >= all.size()) {
                throw new IllegalArgumentException("windowIndex " + i + " out of range; "
                    + all.size() + " window(s) open");
            }
            return all.get(i);
        }
        if (activeWindow != null && activeWindow.isShowing()) {
            return activeWindow;
        }
        for (Window w : all) {
            if (w.isFocused()) {
                return w;
            }
        }
        return all.get(all.size() - 1);
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p == null ? null : p.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String required(Map<String, Object> p, String key) {
        String v = str(p, key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return v;
    }

    private static int intOr(Map<String, Object> p, String key, int fallback) {
        Object v = p == null ? null : p.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private Node nodeParam(Map<String, Object> p) {
        return scene.node(required(p, "uid"));
    }

    // ---- scene inspection -------------------------------------------------

    @Override
    public SnapshotNode takeSnapshot(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            scene.prune();
            // Wire contract (SnapshotService.takeSnapshot): an optional uid limits the
            // snapshot to that node's subtree; filter is VISIBLE_ONLY from the server.
            String uid = str(p, "uid");
            Node root = uid == null || uid.isBlank() ? null : scene.node(uid);
            Window win = root == null ? targetWindow(p)
                : root.getScene() == null ? null : root.getScene().getWindow();
            if (win == null) {
                return new SnapshotNode("No window", "null", 0, 0, 0, 0, List.of());
            }
            String filter = str(p, "filter");
            boolean visibleOnly = "VISIBLE_ONLY".equalsIgnoreCase(filter) || "VISIBLE".equalsIgnoreCase(filter);
            FxScene.Budget budget = new FxScene.Budget(
                Math.max(1, intOr(p, "maxNodes", DEFAULT_MAX_NODES)),
                intOr(p, "maxDepth", Integer.MAX_VALUE));
            if (root == null) {
                Scene sc = FxScene.sceneOf(win);
                root = sc == null ? null : sc.getRoot();
            }
            ComponentDescriptor top = root == null ? null : scene.describe(root, visibleOnly, budget, 0);
            List<ComponentDescriptor> children = top == null ? List.of() : List.of(top);
            return new SnapshotNode(
                FxScene.titleOf(win),
                ClassNames.displayName(win.getClass()),
                (int) win.getX(), (int) win.getY(),
                (int) win.getWidth(), (int) win.getHeight(),
                children,
                budget.truncated ? Boolean.TRUE : null);
        });
    }

    @Override
    public List<Map<String, Object>> listWindows() throws Exception {
        return onUi(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            List<Window> all = windows();
            for (int i = 0; i < all.size(); i++) {
                Window w = all.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i);
                m.put("title", FxScene.titleOf(w));
                m.put("windowClass", ClassNames.displayName(w.getClass()));
                m.put("toolkit", id());
                m.put("focused", w.isFocused());
                m.put("modal", FxScene.isModal(w));
                m.put("x", (int) w.getX());
                m.put("y", (int) w.getY());
                m.put("width", (int) w.getWidth());
                m.put("height", (int) w.getHeight());
                out.add(m);
            }
            return out;
        });
    }

    @Override
    public Map<String, Object> getComponentDetails(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            String uid = required(p, "uid");
            if (scene.isMenuUid(uid)) {
                MenuItem item = scene.menuItem(uid);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uid", uid);
                m.put("componentClass", ClassNames.displayName(item.getClass()));
                m.put("text", item.getText());
                m.put("enabled", !item.isDisable());
                return m;
            }
            Node n = scene.node(uid);
            Bounds b = n.getBoundsInParent();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uid", uid);
            m.put("componentClass", ClassNames.displayName(n.getClass()));
            m.put("id", n.getId());
            m.put("text", FxScene.textOf(n));
            if (FxScene.tooltipOf(n) != null) {
                m.put("tooltip", FxScene.tooltipOf(n));
            }
            m.put("enabled", !n.isDisabled());
            m.put("visible", n.isVisible());
            m.put("focused", n.isFocused());
            m.put("focusable", n.isFocusTraversable());
            m.put("selectionState", FxScene.selectionStateOf(n));
            m.put("styleClass", new ArrayList<>(n.getStyleClass()));
            m.put("x", (int) b.getMinX());
            m.put("y", (int) b.getMinY());
            m.put("width", (int) b.getWidth());
            m.put("height", (int) b.getHeight());
            if (n instanceof TextInputControl t) {
                m.put("editable", t.isEditable());
                m.put("promptText", t.getPromptText());
            }
            if (n instanceof ComboBox<?> cb) {
                m.put("itemCount", cb.getItems().size());
                m.put("editable", cb.isEditable());
            }
            return m;
        });
    }

    @Override
    public List<Map<String, Object>> findComponent(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Window win = targetWindow(p);
            Scene sc = win == null ? null : FxScene.sceneOf(win);
            if (sc == null || sc.getRoot() == null) {
                return List.<Map<String, Object>>of();
            }
            // Wire contract (SnapshotService.findComponent): {query, by}, where by is
            // NAME, TEXT, CLASS or ANY — the same contract the Swing scanner honours.
            // The older name/text/componentClass form is still accepted for callers
            // that use the toolkit directly.
            String query = str(p, "query");
            String by = str(p, "by") == null ? "ANY" : str(p, "by").toUpperCase();
            String byId = str(p, "name");
            String byText = str(p, "text");
            String byType = str(p, "componentClass");
            if (query != null) {
                boolean any = "ANY".equals(by);
                byId = any || "NAME".equals(by) ? query : null;
                byText = any || "TEXT".equals(by) ? query : null;
                byType = any || "CLASS".equals(by) ? query : null;
            }
            List<Map<String, Object>> hits = new ArrayList<>();
            collectMatches(sc.getRoot(), byId, byText, byType, query != null && "ANY".equals(by), hits);
            return hits;
        });
    }

    private void collectMatches(Node node, String byId, String byText, String byType, boolean anyOf,
                                List<Map<String, Object>> hits) {
        String pkg = node.getClass().getPackageName();
        if (pkg.startsWith("javafx.scene.control.skin") || pkg.startsWith("com.sun.")) {
            return;
        }
        boolean idHit = byId != null && byId.equalsIgnoreCase(node.getId());
        String t = FxScene.textOf(node);
        boolean textHit = byText != null && t != null && t.toLowerCase().contains(byText.toLowerCase());
        boolean typeHit = byType != null && ClassNames.matches(node.getClass(), byType, false);
        boolean match;
        if (anyOf) {
            // by=ANY: a hit on any field, as the Swing scanner does.
            match = idHit || textHit || typeHit;
        } else {
            // Explicit fields: every supplied criterion must hold.
            match = (byId == null || idHit) && (byText == null || textHit) && (byType == null || typeHit)
                && (byId != null || byText != null || byType != null);
        }
        if (match) {
            Bounds b = node.getBoundsInParent();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uid", scene.uidFor(node));
            m.put("componentClass", ClassNames.displayName(node.getClass()));
            m.put("id", node.getId());
            m.put("text", FxScene.textOf(node));
            if (FxScene.tooltipOf(node) != null) {
                m.put("tooltip", FxScene.tooltipOf(node));
            }
            m.put("enabled", !node.isDisabled());
            m.put("visible", node.isVisible());
            m.put("x", (int) b.getMinX());
            m.put("y", (int) b.getMinY());
            m.put("width", (int) b.getWidth());
            m.put("height", (int) b.getHeight());
            hits.add(m);
        }
        // find must reach content that a snapshot does not descend into: a
        // Control is a leaf in the tree view, but the nodes inside a ScrollPane,
        // TabPane or SplitPane are real UI and must still be findable. Uses the
        // same contentOf() definition as the walker so the two cannot drift.
        if (node instanceof javafx.scene.control.Control control) {
            for (Node child : FxScene.contentOf(control)) {
                collectMatches(child, byId, byText, byType, anyOf, hits);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectMatches(child, byId, byText, byType, anyOf, hits);
            }
        }
    }

    @Override
    public Map<String, Object> getTableData(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Node n = nodeParam(p);
            if (!(n instanceof TableView<?> table)) {
                throw new IllegalArgumentException("Component " + str(p, "uid")
                    + " is a " + ClassNames.displayName(n.getClass()) + ", not a TableView");
            }
            // Wire contract (DataService.getTableData): startRow/endRow are a
            // zero-based inclusive range, as in the Swing scanner. With no endRow,
            // at most maxRows rows are returned from startRow.
            int startRow = Math.max(0, intOr(p, "startRow", 0));
            int endRow = intOr(p, "endRow", -1);
            return FxScene.tableData(table, startRow,
                endRow < 0 ? startRow + intOr(p, "maxRows", DEFAULT_MAX_ROWS) - 1 : endRow);
        });
    }

    @Override
    public Map<String, Object> getListItems(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Node n = nodeParam(p);
            if (n instanceof ListView<?> list) {
                // startIndex/endIndex: zero-based and inclusive, as for tables.
                int start = Math.max(0, intOr(p, "startIndex", 0));
                int end = intOr(p, "endIndex", -1);
                return FxScene.listItems(list, start,
                    end < 0 ? start + intOr(p, "maxItems", DEFAULT_MAX_ROWS) - 1 : end);
            }
            if (n instanceof ComboBox<?> cb) {
                Map<String, Object> m = new LinkedHashMap<>();
                List<String> items = new ArrayList<>();
                for (Object o : cb.getItems()) {
                    items.add(o == null ? null : String.valueOf(o));
                }
                m.put("items", items);
                m.put("itemCount", items.size());
                m.put("selectedIndex", cb.getSelectionModel().getSelectedIndex());
                return m;
            }
            if (n instanceof ChoiceBox<?> cb) {
                Map<String, Object> m = new LinkedHashMap<>();
                List<String> items = new ArrayList<>();
                for (Object o : cb.getItems()) {
                    items.add(o == null ? null : String.valueOf(o));
                }
                m.put("items", items);
                m.put("itemCount", items.size());
                m.put("selectedIndex", cb.getSelectionModel().getSelectedIndex());
                return m;
            }
            throw new IllegalArgumentException("Component " + str(p, "uid")
                + " is a " + ClassNames.displayName(n.getClass()) + ", not a ListView, ComboBox or ChoiceBox");
        });
    }

    @Override
    public List<Map<String, Object>> listDialogs() throws Exception {
        return onUi(this::listDialogsOnUiThread);
    }

    @Override
    protected List<Map<String, Object>> listDialogsOnUiThread() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Window> all = windows();
        for (int i = 0; i < all.size(); i++) {
            Window w = all.get(i);
            if (!(w instanceof Stage stage) || stage.getOwner() == null && !FxScene.isModal(w)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("title", FxScene.titleOf(w));
            m.put("modal", FxScene.isModal(w));
            m.put("toolkit", id());
            Scene sc = FxScene.sceneOf(w);
            if (sc != null && sc.getRoot() instanceof javafx.scene.control.DialogPane pane) {
                // A javafx.scene.control.Dialog (Alert, TextInputDialog, ...) has a
                // DialogPane root that knows its own text and buttons. Read those
                // directly: walking the graph for the first Label finds the pane's
                // internal placeholder, not what the user sees.
                List<String> buttons = new ArrayList<>();
                for (javafx.scene.control.ButtonType bt : pane.getButtonTypes()) {
                    buttons.add(bt.getText());
                }
                m.put("buttons", buttons);
                m.put("message", pane.getContentText());
                if (pane.getHeaderText() != null && !pane.getHeaderText().isBlank()) {
                    m.put("header", pane.getHeaderText());
                }
            } else if (sc != null && sc.getRoot() != null) {
                // A hand-built modal Stage: fall back to what can be seen.
                List<String> buttons = new ArrayList<>();
                collectButtonLabels(sc.getRoot(), buttons);
                m.put("buttons", buttons);
                m.put("message", firstText(sc.getRoot()));
            }
            out.add(m);
        }
        return out;
    }

    private void collectButtonLabels(Node node, List<String> out) {
        if (node instanceof ButtonBase b && b.getText() != null && !b.getText().isBlank()) {
            out.add(b.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectButtonLabels(child, out);
            }
        }
    }

    private String firstText(Node node) {
        if (node instanceof javafx.scene.control.Label l && l.getText() != null && !l.getText().isBlank()) {
            return l.getText();
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String t = firstText(child);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * Wire contract (ScreenshotService.screenshot): the PNG comes back as
     * {@code imageBase64} with a {@code mimeType}, like the Swing scanner's. A node
     * snapshot is always rendered, so the {@code mode} parameter does not apply.
     */
    @Override
    public Map<String, Object> takeScreenshot(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Window win = targetWindow(p);
            Scene sc = win == null ? null : FxScene.sceneOf(win);
            if (sc == null) {
                throw new IllegalStateException("No JavaFX window to capture");
            }
            String uid = str(p, "uid");
            Node target = uid == null ? sc.getRoot() : scene.node(uid);
            WritableImage image = target.snapshot(new SnapshotParameters(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(toBufferedImage(image), "png", out);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("imageBase64", Base64.getEncoder().encodeToString(out.toByteArray()));
            m.put("mimeType", "image/png");
            m.put("width", (int) image.getWidth());
            m.put("height", (int) image.getHeight());
            m.put("mode", "PAINT");
            return m;
        });
    }

    /**
     * Converts a JavaFX image to an AWT one by reading pixels directly.
     *
     * <p>Deliberately avoids {@code javafx.embed.swing.SwingFXUtils}: that lives
     * in the {@code javafx.swing} module, which a JavaFX application has no
     * reason to ship. Depending on it would make screenshots fail on exactly the
     * pure-JavaFX applications this toolkit exists to serve.</p>
     */
    private static java.awt.image.BufferedImage toBufferedImage(WritableImage image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        java.awt.image.BufferedImage out =
            new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        if (reader == null) {
            throw new IllegalStateException("Snapshot produced no readable pixels");
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return out;
    }

    // ---- interaction ------------------------------------------------------

    /**
     * Wire contract (InteractionService.click): {@code uid}, optional {@code button}
     * (LEFT/RIGHT/MIDDLE), {@code clickType} (SINGLE/DOUBLE), {@code x}/{@code y}
     * relative to the node's top-left corner and {@code modifiers} such as
     * "CTRL+SHIFT". A plain left click on a button is {@code fire()}; anything
     * else is dispatched as mouse events to the node. {@code robot} does not
     * apply: events are always delivered to the node.
     */
    @Override
    public Object click(Map<String, Object> p) throws Exception {
        String uid = required(p, "uid");
        String buttonName = str(p, "button") == null ? "LEFT" : str(p, "button").toUpperCase();
        int clicks = "DOUBLE".equalsIgnoreCase(str(p, "clickType")) ? 2 : 1;
        Modifiers mods = Modifiers.parse(str(p, "modifiers"));
        boolean atPoint = p.get("x") != null || p.get("y") != null;
        return action(() -> {
            if (scene.isMenuUid(uid)) {
                scene.menuItem(uid).fire();
                return "Fired menu item " + uid;
            }
            Node n = scene.node(uid);
            if (n.isDisabled()) {
                throw new IllegalStateException("Component " + uid + " is disabled");
            }
            if (n instanceof ButtonBase b && "LEFT".equals(buttonName) && mods.none() && !atPoint) {
                b.fire();
                return "Clicked " + uid;
            }
            javafx.geometry.Point2D at = pointIn(n, p);
            dispatchClick(n, at, mouseButton(buttonName), clicks, mods);
            return "Dispatched click to " + uid + " at (" + (int) at.getX() + "," + (int) at.getY() + ")";
        });
    }

    /** Modifier keys held during a pointer action. */
    private record Modifiers(boolean shift, boolean ctrl, boolean alt, boolean meta) {
        static Modifiers parse(String text) {
            boolean shift = false, ctrl = false, alt = false, meta = false;
            boolean mac = isMac();
            for (String part : (text == null ? "" : text).toUpperCase().split("[+,\\s]+")) {
                switch (part) {
                    case "" -> { }
                    case "CTRL", "CONTROL" -> ctrl = true;
                    case "SHIFT" -> shift = true;
                    case "ALT", "OPTION" -> alt = true;
                    case "META", "CMD", "COMMAND" -> meta = true;
                    case "SHORTCUT" -> {
                        meta |= mac;
                        ctrl |= !mac;
                    }
                    default -> throw new IllegalArgumentException("Unknown modifier: " + part
                        + ". Use CTRL, SHIFT, ALT, META (Cmd), or SHORTCUT, combined with '+'");
                }
            }
            return new Modifiers(shift, ctrl, alt, meta);
        }

        boolean none() {
            return !shift && !ctrl && !alt && !meta;
        }
    }

    private static MouseButton mouseButton(String name) {
        return switch (name) {
            case "RIGHT" -> MouseButton.SECONDARY;
            case "MIDDLE" -> MouseButton.MIDDLE;
            default -> MouseButton.PRIMARY;
        };
    }

    /** The node-local point a pointer command targets: x/y, or the center for any missing. */
    private static javafx.geometry.Point2D pointIn(Node n, Map<String, Object> p) {
        Bounds b = n.getBoundsInLocal();
        double x = p.get("x") instanceof Number nx ? b.getMinX() + nx.doubleValue() : b.getCenterX();
        double y = p.get("y") instanceof Number ny ? b.getMinY() + ny.doubleValue() : b.getCenterY();
        if (!b.contains(x, y)) {
            throw new IllegalArgumentException("Point (" + (int) (x - b.getMinX()) + "," + (int) (y - b.getMinY())
                + ") is outside the component, which is " + (int) b.getWidth() + "x" + (int) b.getHeight()
                + "; x/y are relative to its top-left corner");
        }
        return new javafx.geometry.Point2D(x - b.getMinX(), y - b.getMinY());
    }

    /**
     * Synthesises a press/release/click sequence on a node. The event carries
     * scene coordinates: JavaFX derives each handler's local x/y from them.
     */
    private void dispatchClick(Node n, javafx.geometry.Point2D at, MouseButton button, int clicks, Modifiers mods) {
        Bounds b = n.getBoundsInLocal();
        javafx.geometry.Point2D scenePt = n.localToScene(b.getMinX() + at.getX(), b.getMinY() + at.getY());
        javafx.geometry.Point2D screenPt = n.localToScreen(b.getMinX() + at.getX(), b.getMinY() + at.getY());
        double sx = screenPt == null ? scenePt.getX() : screenPt.getX();
        double sy = screenPt == null ? scenePt.getY() : screenPt.getY();
        boolean primary = button == MouseButton.PRIMARY;
        boolean middle = button == MouseButton.MIDDLE;
        boolean secondary = button == MouseButton.SECONDARY;
        for (int count = 1; count <= clicks; count++) {
            for (javafx.event.EventType<MouseEvent> type :
                List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                boolean down = type == MouseEvent.MOUSE_PRESSED;
                Event.fireEvent(n, new MouseEvent(type, scenePt.getX(), scenePt.getY(), sx, sy, button, count,
                    mods.shift(), mods.ctrl(), mods.alt(), mods.meta(),
                    primary && down, middle && down, secondary && down,
                    true, secondary && type != MouseEvent.MOUSE_CLICKED, true, null));
            }
        }
    }

    @Override
    public String fill(Map<String, Object> p) throws Exception {
        String text = str(p, "text") == null ? "" : str(p, "text");
        return onUi(() -> {
            Node n = nodeParam(p);
            if (n instanceof TextInputControl t) {
                if (!t.isEditable()) {
                    throw new IllegalStateException("Component is not editable");
                }
                t.setText(text);
                t.positionCaret(text.length());
                return "Filled " + n.getId() + " with " + text.length() + " character(s)";
            }
            if (n instanceof ComboBox<?> cb && cb.isEditable()) {
                cb.getEditor().setText(text);
                return "Filled editable combo box";
            }
            if (n instanceof Spinner<?> sp) {
                // Setting the editor text alone leaves the spinner's value untouched —
                // and Spinner.commitValue() is a no-op unless the spinner is editable.
                // Go through the value factory's own converter so the value the
                // application reads is what was asked for, editable or not.
                return fillSpinner(sp, text);
            }
            throw new IllegalArgumentException("Component " + str(p, "uid") + " is a "
                + ClassNames.displayName(n.getClass()) + " and cannot be filled with text");
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String fillSpinner(Spinner<?> spinner, String text) {
        javafx.scene.control.SpinnerValueFactory factory = spinner.getValueFactory();
        if (factory == null) {
            throw new IllegalStateException("Spinner has no value factory");
        }
        javafx.util.StringConverter converter = factory.getConverter();
        Object value;
        try {
            value = converter != null ? converter.fromString(text) : text;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Spinner cannot accept \"" + text + "\": " + e.getMessage());
        }
        factory.setValue(value);
        return "Filled spinner with " + spinner.getValue();
    }

    /**
     * Types character by character so the application's own key handlers and
     * validators fire, which {@link #fill} deliberately bypasses.
     *
     * <p>Only the {@code KEY_TYPED} event is dispatched. A {@code TextInputControl}
     * inserts the character itself in response to that event, exactly as it does
     * for a physical keystroke — inserting it here as well would put every
     * character in twice, which is what an earlier version of this method did.</p>
     *
     * <p>Fire-and-poll, like the other actions: a key handler may open a modal
     * dialog, which never returns on the FX thread. The wait grows with the text,
     * as the Swing toolkit's does, so a long text is not reported pending.</p>
     */
    @Override
    public Object typeText(Map<String, Object> p) throws Exception {
        String text = required(p, "text");
        String uid = str(p, "uid");
        long waitMs = Math.min(UI_TIMEOUT_MS, ACTION_WAIT_MS + 20L * text.length());
        return actionOffUi(() -> invokeOnUiThread(() -> {
            Node n = keyTarget(uid, p);
            for (char c : text.toCharArray()) {
                String s = String.valueOf(c);
                Event.fireEvent(n, new KeyEvent(KeyEvent.KEY_TYPED, s, s, KeyCode.UNDEFINED,
                    false, false, false, false));
            }
            return "Typed " + text.length() + " character(s)";
        }, Long.MAX_VALUE), waitMs);
    }

    /**
     * Where key events go: {@code uid} (focused first) when given, else the focus
     * owner of the target window's scene, else its root.
     */
    private Node keyTarget(String uid, Map<String, Object> p) {
        if (uid != null && !uid.isBlank()) {
            Node n = scene.node(uid);
            n.requestFocus();
            return n;
        }
        Window win = targetWindow(p);
        Scene sc = win == null ? null : FxScene.sceneOf(win);
        if (sc == null) {
            throw new IllegalStateException("No JavaFX window");
        }
        return sc.getFocusOwner() != null ? sc.getFocusOwner() : sc.getRoot();
    }

    @Override
    public String focus(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Node n = nodeParam(p);
            n.requestFocus();
            return "Focused " + str(p, "uid");
        });
    }

    /** Wire contract (InteractionService.hover): {@code uid}, optional {@code x}/{@code y}. */
    @Override
    public String hover(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Node n = nodeParam(p);
            javafx.geometry.Point2D at = pointIn(n, p);
            Bounds b = n.getBoundsInLocal();
            javafx.geometry.Point2D scenePt = n.localToScene(b.getMinX() + at.getX(), b.getMinY() + at.getY());
            for (javafx.event.EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_MOVED)) {
                Event.fireEvent(n, new MouseEvent(type, scenePt.getX(), scenePt.getY(), 0, 0, MouseButton.NONE, 0,
                    false, false, false, false, false, false, false, true, false, false, null));
            }
            return "Hovered " + str(p, "uid") + " at (" + (int) at.getX() + "," + (int) at.getY() + ")";
        });
    }

    @Override
    public String selectOption(Map<String, Object> p) throws Exception {
        String text = str(p, "text");
        Integer index = p.get("index") instanceof Number n ? n.intValue() : null;
        return onUi(() -> {
            Node n = nodeParam(p);
            if (n instanceof ComboBox<?> cb) {
                return select(cb.getItems(), cb.getSelectionModel(), text, index, "combo box");
            }
            if (n instanceof ChoiceBox<?> cb) {
                return select(cb.getItems(), cb.getSelectionModel(), text, index, "choice box");
            }
            if (n instanceof ListView<?> lv) {
                return select(lv.getItems(), lv.getSelectionModel(), text, index, "list");
            }
            if (n instanceof TabPane tp) {
                for (int i = 0; i < tp.getTabs().size(); i++) {
                    if (text != null && text.equals(tp.getTabs().get(i).getText())) {
                        tp.getSelectionModel().select(i);
                        return "Selected tab " + text;
                    }
                }
                if (index != null) {
                    tp.getSelectionModel().select(index);
                    return "Selected tab " + index;
                }
                throw new IllegalArgumentException("No tab matching " + text);
            }
            if (n instanceof CheckBox cb) {
                cb.setSelected(!cb.isSelected());
                return "Toggled check box to " + cb.isSelected();
            }
            if (n instanceof ToggleButton tb) {
                tb.setSelected(!tb.isSelected());
                return "Toggled to " + tb.isSelected();
            }
            throw new IllegalArgumentException("Component " + str(p, "uid") + " is a "
                + ClassNames.displayName(n.getClass()) + " and has no selectable options");
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String select(List<?> items, javafx.scene.control.SelectionModel model,
                          String text, Integer index, String what) {
        if (index != null) {
            if (index < 0 || index >= items.size()) {
                throw new IllegalArgumentException("index " + index + " out of range; "
                    + items.size() + " item(s)");
            }
            model.select(index);
            return "Selected " + what + " item " + index;
        }
        if (text == null) {
            throw new IllegalArgumentException("Provide either text or index");
        }
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item != null && text.equals(String.valueOf(item))) {
                model.select(i);
                return "Selected " + what + " item " + text;
            }
        }
        throw new IllegalArgumentException("No " + what + " item matching " + text);
    }

    @Override
    public String selectTreeNode(Map<String, Object> p) throws Exception {
        String path = required(p, "path");
        return onUi(() -> {
            Node n = nodeParam(p);
            if (!(n instanceof TreeView<?> tree)) {
                throw new IllegalArgumentException("Component is not a TreeView");
            }
            // Same "A > B > C" path syntax as the Swing scanner and the menu tools.
            String[] parts = path.trim().split("\\s*>\\s*");
            TreeItem<?> current = tree.getRoot();
            if (current == null) {
                throw new IllegalStateException("Tree has no root");
            }
            int start = parts.length > 0 && String.valueOf(current.getValue()).equals(parts[0]) ? 1 : 0;
            for (int i = start; i < parts.length; i++) {
                current.setExpanded(true);
                TreeItem<?> next = null;
                for (TreeItem<?> child : current.getChildren()) {
                    if (String.valueOf(child.getValue()).equals(parts[i])) {
                        next = child;
                        break;
                    }
                }
                if (next == null) {
                    throw new IllegalArgumentException("No tree node named " + parts[i]
                        + " under " + current.getValue());
                }
                current = next;
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            javafx.scene.control.SelectionModel model = tree.getSelectionModel();
            model.select(tree.getRow((TreeItem) current));
            return "Selected tree node " + path;
        });
    }

    /**
     * Wire contract (InteractionService.selectTableCell): {@code row}/{@code col},
     * optionally {@code toggle} (Ctrl-click) or {@code extend} (Shift-click from the
     * focused cell), or {@code rows}, a list that becomes the whole selection.
     */
    @Override
    public String selectTableCell(Map<String, Object> p) throws Exception {
        int col = intOr(p, "col", 0);
        boolean toggle = Boolean.parseBoolean(str(p, "toggle"));
        boolean extend = Boolean.parseBoolean(str(p, "extend"));
        List<?> rows = p.get("rows") instanceof List<?> l ? l : null;
        if (rows != null && (toggle || extend)) {
            throw new IllegalArgumentException("rows replaces the whole selection; "
                + "it cannot be combined with toggle or extend");
        }
        if (rows == null && !(p.get("row") instanceof Number)) {
            throw new IllegalArgumentException("Provide row (optionally with toggle or extend) or rows");
        }
        return onUi(() -> {
            Node n = nodeParam(p);
            if (!(n instanceof TableView<?> table)) {
                throw new IllegalArgumentException("Component is not a TableView");
            }
            if (col < 0 || col >= table.getColumns().size()) {
                throw new IllegalArgumentException("col " + col + " out of range; "
                    + table.getColumns().size() + " column(s)");
            }
            table.getSelectionModel().setCellSelectionEnabled(true);
            @SuppressWarnings({"unchecked", "rawtypes"})
            javafx.scene.control.TableColumn column = table.getColumns().get(col);
            @SuppressWarnings({"unchecked", "rawtypes"})
            javafx.scene.control.TableView.TableViewSelectionModel model = table.getSelectionModel();
            if (rows != null) {
                model.clearSelection();
                for (Object r : new java.util.LinkedHashSet<>(rows)) {
                    model.select(requireRow(table, ((Number) r).intValue()), column);
                }
            } else {
                int row = requireRow(table, intOr(p, "row", -1));
                if (toggle && model.isSelected(row, column)) {
                    model.clearSelection(row, column);
                } else if (toggle) {
                    model.select(row, column);
                } else if (extend) {
                    javafx.scene.control.TablePosition<?, ?> anchor = table.getFocusModel().getFocusedCell();
                    int from = anchor == null || anchor.getRow() < 0 ? row : anchor.getRow();
                    model.clearSelection();
                    model.selectRange(Math.min(from, row), column, Math.max(from, row), column);
                    // Keep the anchor focused so a further extend starts from it, as Shift-click does.
                    table.getFocusModel().focus(from, column);
                } else {
                    model.clearAndSelect(row, column);
                }
            }
            List<Integer> selected = new ArrayList<>(new java.util.TreeSet<Integer>(model.getSelectedIndices()));
            String result = "Table selection: rows " + selected + ", column " + col;
            if (model.getSelectionMode() == javafx.scene.control.SelectionMode.SINGLE
                && (toggle || extend || rows != null && rows.size() > 1)) {
                result += " (this table allows only one selected row)";
            }
            return result;
        });
    }

    private static int requireRow(TableView<?> table, int row) {
        if (row < 0 || row >= table.getItems().size()) {
            throw new IllegalArgumentException("row " + row + " out of range; "
                + table.getItems().size() + " row(s)");
        }
        return row;
    }

    @Override
    public Object selectMenuItem(Map<String, Object> p) throws Exception {
        String path = required(p, "path");
        return action(() -> {
            String[] parts = path.split("\\s*>\\s*");
            Window win = targetWindow(p);
            Scene sc = win == null ? null : FxScene.sceneOf(win);
            if (sc == null) {
                throw new IllegalStateException("No JavaFX window");
            }
            javafx.scene.control.MenuBar bar = findMenuBar(sc.getRoot());
            if (bar == null) {
                throw new IllegalStateException("No MenuBar in the active window");
            }
            List<? extends MenuItem> level = bar.getMenus();
            MenuItem found = null;
            for (String part : parts) {
                found = null;
                for (MenuItem item : level) {
                    if (part.equals(item.getText())) {
                        found = item;
                        break;
                    }
                }
                if (found == null) {
                    throw new IllegalArgumentException("No menu entry named " + part + " in " + path);
                }
                if (found instanceof javafx.scene.control.Menu m) {
                    level = m.getItems();
                }
            }
            found.fire();
            return "Selected menu item " + path;
        });
    }

    private javafx.scene.control.MenuBar findMenuBar(Node node) {
        if (node instanceof javafx.scene.control.MenuBar bar) {
            return bar;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.control.MenuBar bar = findMenuBar(child);
                if (bar != null) {
                    return bar;
                }
            }
        }
        return null;
    }

    /** Wire contract (InteractionService.selectContextMenuItem): {@code uid} and a {@code path} like "Copy" or "Edit > Copy". */
    @Override
    public Object selectContextMenuItem(Map<String, Object> p) throws Exception {
        String path = str(p, "path") != null ? str(p, "path") : required(p, "text");
        return action(() -> {
            Node n = nodeParam(p);
            javafx.scene.control.ContextMenu menu = contextMenuOf(n);
            if (menu == null) {
                throw new IllegalStateException("Component has no context menu");
            }
            List<? extends MenuItem> level = menu.getItems();
            MenuItem found = null;
            for (String part : path.split("\\s*>\\s*")) {
                found = null;
                for (MenuItem item : level) {
                    if (part.equals(item.getText())) {
                        found = item;
                        break;
                    }
                }
                if (found == null) {
                    throw new IllegalArgumentException("No context menu item named " + part + " in " + path);
                }
                if (found instanceof javafx.scene.control.Menu m) {
                    level = m.getItems();
                }
            }
            found.fire();
            return "Selected context menu item " + path;
        });
    }

    private javafx.scene.control.ContextMenu contextMenuOf(Node n) {
        if (n instanceof javafx.scene.control.Control c && c.getContextMenu() != null) {
            return c.getContextMenu();
        }
        return null;
    }

    @Override
    public Object handleDialog(Map<String, Object> p) throws Exception {
        String button = str(p, "button");
        return action(() -> {
            // Topmost first: windows() is in the order they were shown, so the
            // last modal one is the dialog the user is looking at — a nested
            // dialog, not the one blocked underneath it.
            List<Window> all = windows();
            for (int i = all.size() - 1; i >= 0; i--) {
                Window w = all.get(i);
                if (!FxScene.isModal(w)) {
                    continue;
                }
                Scene sc = FxScene.sceneOf(w);
                if (sc == null) {
                    continue;
                }
                if (button == null) {
                    ((Stage) w).close();
                    return "Closed dialog";
                }
                ButtonBase target = findButton(sc.getRoot(), button);
                if (target != null) {
                    target.fire();
                    return "Pressed " + button;
                }
            }
            throw new IllegalStateException(button == null
                ? "No modal dialog is open"
                : "No modal dialog with a button labelled " + button);
        });
    }

    private ButtonBase findButton(Node node, String label) {
        if (node instanceof ButtonBase b && label.equalsIgnoreCase(b.getText())) {
            return b;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ButtonBase b = findButton(child, label);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * Wire contract (InteractionService.pressKey): {@code keys} is a chord such as
     * {@code "ENTER"}, {@code "TAB"} or {@code "CTRL+A"}, matching the Swing scanner:
     * the same modifier and key names (SHORTCUT, OPTION, ESC, RETURN, PGUP, PGDN,
     * DEL, ...), any {@link KeyCode} name, or a single character.
     *
     * <p>The chord is dispatched as the keyboard produces it: modifiers pressed in
     * order, then each key pressed, typed (when it produces a character and no
     * Ctrl/Alt/Meta is held) and released, then the modifiers released. Without the
     * {@code KEY_TYPED} a text control would insert nothing. As in Swing, a letter
     * types lower case unless SHIFT is part of the chord.</p>
     */
    @Override
    public Object pressKey(Map<String, Object> p) throws Exception {
        String keys = str(p, "keys") != null ? str(p, "keys") : required(p, "key");
        String uid = str(p, "uid");
        List<Key> chord = parseKeyChord(keys);
        // action, not onUi: a key may open a modal dialog and never return on the FX thread.
        return action(() -> {
            Node target = keyTarget(uid, p);
            boolean shift = false, ctrl = false, alt = false, meta = false;
            List<Key> modifiers = chord.stream().filter(Key::isModifier).toList();
            for (Key mod : modifiers) {
                shift |= mod.code() == KeyCode.SHIFT;
                ctrl |= mod.code() == KeyCode.CONTROL;
                alt |= mod.code() == KeyCode.ALT;
                meta |= mod.code() == KeyCode.META;
                fireKey(target, KeyEvent.KEY_PRESSED, mod.code(), "", shift, ctrl, alt, meta);
            }
            for (Key key : chord) {
                if (key.isModifier()) {
                    continue;
                }
                String ch = key.ch();
                if (ch != null && ch.length() == 1 && Character.isLetter(ch.charAt(0))) {
                    ch = shift ? ch.toUpperCase() : ch.toLowerCase();
                }
                fireKey(target, KeyEvent.KEY_PRESSED, key.code(), ch, shift, ctrl, alt, meta);
                if (ch != null && !ctrl && !alt && !meta) {
                    Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, ch, ch, KeyCode.UNDEFINED,
                        shift, ctrl, alt, meta));
                }
                fireKey(target, KeyEvent.KEY_RELEASED, key.code(), ch, shift, ctrl, alt, meta);
            }
            for (int i = modifiers.size() - 1; i >= 0; i--) {
                KeyCode code = modifiers.get(i).code();
                shift &= code != KeyCode.SHIFT;
                ctrl &= code != KeyCode.CONTROL;
                alt &= code != KeyCode.ALT;
                meta &= code != KeyCode.META;
                fireKey(target, KeyEvent.KEY_RELEASED, code, "", shift, ctrl, alt, meta);
            }
            return "Key pressed: " + keys;
        });
    }

    private static void fireKey(Node target, javafx.event.EventType<KeyEvent> type, KeyCode code, String text,
                                boolean shift, boolean ctrl, boolean alt, boolean meta) {
        Event.fireEvent(target, new KeyEvent(type, KeyEvent.CHAR_UNDEFINED, text == null ? "" : text, code,
            shift, ctrl, alt, meta));
    }

    /** A key of a chord: its key code and the character it types, or null if none. */
    record Key(KeyCode code, String ch) {
        boolean isModifier() {
            return code == KeyCode.SHIFT || code == KeyCode.CONTROL || code == KeyCode.ALT || code == KeyCode.META;
        }
    }

    /**
     * Parses a chord such as {@code "CTRL+SHIFT+S"}, {@code "ENTER"}, {@code "F5"}
     * or {@code "PAGE_DOWN"} into keys, accepting the same names as the Swing
     * scanner's parser.
     */
    static List<Key> parseKeyChord(String keys) {
        List<Key> chord = new ArrayList<>();
        for (String raw : keys.split("\\+")) {
            String part = raw.trim();
            String name = part.toUpperCase();
            chord.add(switch (name) {
                case "CTRL", "CONTROL" -> new Key(KeyCode.CONTROL, null);
                case "ALT", "OPTION" -> new Key(KeyCode.ALT, null);
                case "SHIFT" -> new Key(KeyCode.SHIFT, null);
                case "META", "CMD", "COMMAND" -> new Key(KeyCode.META, null);
                // KeyEvent.isShortcutDown() follows from this: Cmd on macOS, Ctrl elsewhere.
                case "SHORTCUT" -> new Key(isMac() ? KeyCode.META : KeyCode.CONTROL, null);
                case "ENTER", "RETURN" -> new Key(KeyCode.ENTER, "\r");
                case "ESCAPE", "ESC" -> new Key(KeyCode.ESCAPE, "\u001b");
                case "TAB" -> new Key(KeyCode.TAB, "\t");
                case "SPACE" -> new Key(KeyCode.SPACE, " ");
                case "BACKSPACE", "BACK_SPACE" -> new Key(KeyCode.BACK_SPACE, "\b");
                case "DELETE", "DEL" -> new Key(KeyCode.DELETE, "\u007f");
                case "PGUP" -> new Key(KeyCode.PAGE_UP, null);
                case "PGDN" -> new Key(KeyCode.PAGE_DOWN, null);
                default -> part.length() == 1 ? new Key(keyCodeForChar(part.charAt(0)), part)
                    : new Key(namedKeyCode(part, keys), null);
            });
        }
        if (chord.isEmpty()) {
            throw new IllegalArgumentException("No key in chord: " + keys);
        }
        return chord;
    }

    /** The key that types {@code c} on a US layout; UNDEFINED (still typed) for any other character. */
    private static KeyCode keyCodeForChar(char c) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
            return KeyCode.valueOf(String.valueOf(Character.toUpperCase(c)));
        }
        if (c >= '0' && c <= '9') {
            return KeyCode.valueOf("DIGIT" + c);
        }
        return switch (c) {
            case ',' -> KeyCode.COMMA;
            case '.' -> KeyCode.PERIOD;
            case '/' -> KeyCode.SLASH;
            case ';' -> KeyCode.SEMICOLON;
            case '=' -> KeyCode.EQUALS;
            case '-' -> KeyCode.MINUS;
            case '[' -> KeyCode.OPEN_BRACKET;
            case ']' -> KeyCode.CLOSE_BRACKET;
            case '\\' -> KeyCode.BACK_SLASH;
            case '\'' -> KeyCode.QUOTE;
            case '`' -> KeyCode.BACK_QUOTE;
            default -> KeyCode.UNDEFINED;
        };
    }

    /** A {@link KeyCode} by enum name (F5, HOME, PAGE_UP) or display name ("Page Up"). */
    private static KeyCode namedKeyCode(String name, String keys) {
        try {
            return KeyCode.valueOf(name.toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            KeyCode byDisplayName = KeyCode.getKeyCode(name);
            if (byDisplayName != null) {
                return byDisplayName;
            }
            throw new IllegalArgumentException("Unknown key \"" + name + "\" in \"" + keys
                + "\". Use names such as ENTER, TAB, ESCAPE, F5, HOME, PAGE_DOWN, a single character, "
                + "and modifiers CTRL, SHIFT, ALT, META, SHORTCUT joined with '+'");
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    @Override
    public String drag(Map<String, Object> p) {
        throw unsupported("drag", "JavaFX drag-and-drop is application-defined; "
            + "use the control's own API, or click the source and target in sequence");
    }

    /**
     * Moves the enclosing {@code ScrollPane} by {@code amount} tenths of its range,
     * or, for a node with none (ListView, TableView, TreeView and custom controls
     * scroll themselves on ScrollEvents), sends {@code amount} wheel notches.
     * {@code amount} defaults to {@value UiToolkit#DEFAULT_SCROLL_AMOUNT} either way.
     */
    @Override
    public Object scroll(Map<String, Object> p) throws Exception {
        String direction = str(p, "direction") == null ? "DOWN" : str(p, "direction").toUpperCase();
        if (!List.of("UP", "DOWN", "LEFT", "RIGHT").contains(direction)) {
            throw new IllegalArgumentException("Unknown direction: " + direction);
        }
        int amount = intOr(p, "amount", DEFAULT_SCROLL_AMOUNT);
        return action(() -> {
            Node n = nodeParam(p);
            javafx.scene.control.ScrollPane pane = n instanceof javafx.scene.control.ScrollPane sp
                ? sp : enclosingScrollPane(n);
            if (pane == null) {
                boolean horizontal = direction.equals("LEFT") || direction.equals("RIGHT");
                int rotation = direction.equals("DOWN") || direction.equals("RIGHT") ? amount : -amount;
                requireWheelNotches(rotation);
                Bounds b = n.getBoundsInLocal();
                Node target = fireWheel(n, new Point2D(b.getWidth() / 2, b.getHeight() / 2), rotation,
                    new Modifiers(horizontal, false, false, false));
                return "Scrolled " + direction + " by " + amount + " mouse-wheel notch(es) on "
                    + ClassNames.displayName(target.getClass());
            }
            double step = amount * 0.1;
            switch (direction) {
                case "UP" -> pane.setVvalue(clamp(pane.getVvalue() - step));
                case "DOWN" -> pane.setVvalue(clamp(pane.getVvalue() + step));
                case "LEFT" -> pane.setHvalue(clamp(pane.getHvalue() - step));
                default -> pane.setHvalue(clamp(pane.getHvalue() + step));
            }
            return "Scrolled " + direction + " by " + amount;
        });
    }

    /**
     * Wire contract (InteractionService.mouseWheel): {@code uid}, {@code rotation}
     * (notches, positive = down, as in AWT), optional {@code x}/{@code y} relative
     * to the node's top-left corner (default its center) and {@code modifiers}
     * such as "CTRL+SHIFT". {@code robot} does not apply: events are always
     * delivered to the node.
     */
    @Override
    public Object mouseWheel(Map<String, Object> p) throws Exception {
        String uid = required(p, "uid");
        int rotation = intOr(p, "rotation", 0);
        String modifiers = str(p, "modifiers") == null ? "" : str(p, "modifiers");
        Modifiers mods = Modifiers.parse(modifiers);
        return action(() -> {
            Node n = scene.node(uid);
            if (n.isDisabled()) {
                throw new IllegalStateException("Component " + uid + " is disabled");
            }
            requireWheelNotches(rotation);
            Point2D at = pointIn(n, p);
            Node target = fireWheel(n, at, rotation, mods);
            return "Mouse wheel rotated " + rotation + " notch(es) at (" + (int) at.getX() + "," + (int) at.getY()
                + ") of " + uid + (modifiers.isBlank() ? "" : " with " + modifiers.toUpperCase())
                + "; delivered to " + ClassNames.displayName(target.getClass());
        });
    }

    private static void requireWheelNotches(int rotation) {
        if (rotation == 0 || Math.abs(rotation) > MAX_WHEEL_NOTCHES) {
            throw new IllegalArgumentException("Wheel notches must be between 1 and " + MAX_WHEEL_NOTCHES
                + " in either direction, got " + rotation);
        }
    }

    /**
     * Fires one {@link ScrollEvent} per notch at the deepest node under {@code at}
     * (relative to {@code n}'s top-left corner), so it bubbles up as a real one
     * would. The event matches what a physical wheel produces: 40 px per notch with
     * multiplier 40, no gesture totals, and Shift moving the delta to the horizontal
     * axis. Returns the node it was fired at.
     */
    private Node fireWheel(Node n, Point2D at, int rotation, Modifiers mods) {
        Bounds b = n.getBoundsInLocal();
        Point2D scenePt = n.localToScene(b.getMinX() + at.getX(), b.getMinY() + at.getY());
        Point2D screenPt = n.localToScreen(b.getMinX() + at.getX(), b.getMinY() + at.getY());
        double sx = screenPt == null ? scenePt.getX() : screenPt.getX();
        double sy = screenPt == null ? scenePt.getY() : screenPt.getY();
        Node target = deepestAt(n, scenePt.getX(), scenePt.getY());
        // AWT counts positive notches as "down"; JavaFX deltas are positive "up".
        double delta = -Integer.signum(rotation) * WHEEL_NOTCH_PX;
        double dx = mods.shift() ? delta : 0;
        double dy = mods.shift() ? 0 : delta;
        for (int i = 0; i < Math.abs(rotation); i++) {
            Event.fireEvent(target, new ScrollEvent(ScrollEvent.SCROLL,
                scenePt.getX(), scenePt.getY(), sx, sy,
                mods.shift(), mods.ctrl(), mods.alt(), mods.meta(),
                false, false, dx, dy, 0, 0, WHEEL_NOTCH_PX, WHEEL_NOTCH_PX,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
        }
        return target;
    }

    /** The deepest visible, mouse-receiving descendant of {@code n} at a scene point. */
    private static Node deepestAt(Node n, double sceneX, double sceneY) {
        if (n instanceof Parent parent) {
            List<Node> kids = parent.getChildrenUnmodifiable();
            for (int i = kids.size() - 1; i >= 0; i--) {
                Node kid = kids.get(i);
                if (kid.isVisible() && !kid.isMouseTransparent()
                    && kid.contains(kid.sceneToLocal(sceneX, sceneY))) {
                    return deepestAt(kid, sceneX, sceneY);
                }
            }
        }
        return n;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private javafx.scene.control.ScrollPane enclosingScrollPane(Node n) {
        for (Node cur = n.getParent(); cur != null; cur = cur.getParent()) {
            if (cur instanceof javafx.scene.control.ScrollPane sp) {
                return sp;
            }
        }
        return null;
    }

    /**
     * Wire contract (WaitService.waitFor): {@code conditionType} is one of
     * WINDOW_TITLE, COMPONENT_TEXT, COMPONENT_VISIBLE, COMPONENT_ENABLED,
     * COMPONENT_EXISTS, COMPONENT_GONE, WINDOW_COUNT, EDT_IDLE — with {@code uid}
     * and/or {@code expectedValue} as the condition needs, matching the Swing
     * scanner. EDT_IDLE waits for one JavaFX pulse.
     */
    @Override
    public String waitFor(Map<String, Object> p) throws Exception {
        long timeoutMs = intOr(p, "timeoutMs", 5000);
        String cond = str(p, "conditionType");
        if (cond == null) {
            // Direct-call form used before the wire contract was honoured.
            cond = str(p, "name") != null ? "COMPONENT_EXISTS" : "COMPONENT_TEXT";
        }
        final String condition = cond.toUpperCase();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Boolean met = onUi(() -> checkCondition(condition, p));
            if (Boolean.TRUE.equals(met)) {
                return "Condition met: " + condition;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Timeout waiting for condition: " + condition + " after " + timeoutMs + "ms");
    }

    private Boolean checkCondition(String condition, Map<String, Object> p) {
        String expected = str(p, "expectedValue");
        String uid = str(p, "uid");
        switch (condition) {
            case "WINDOW_TITLE" -> {
                for (Window w : windows()) {
                    if (expected != null && FxScene.titleOf(w).contains(expected)) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }
            case "COMPONENT_TEXT" -> {
                if (uid != null) {
                    String t = FxScene.textOf(scene.node(uid));
                    return t != null && expected != null && t.contains(expected);
                }
                String needle = expected != null ? expected : str(p, "text");
                Window win = targetWindow(p);
                Scene sc = win == null ? null : FxScene.sceneOf(win);
                return sc != null && sc.getRoot() != null && matchExists(sc.getRoot(), null, needle);
            }
            case "COMPONENT_VISIBLE" -> {
                return uid != null && scene.node(uid).isVisible();
            }
            case "COMPONENT_ENABLED" -> {
                return uid != null && !scene.node(uid).isDisabled();
            }
            case "COMPONENT_EXISTS", "COMPONENT_GONE" -> {
                String needle = expected != null ? expected : str(p, "name");
                Window win = targetWindow(p);
                Scene sc = win == null ? null : FxScene.sceneOf(win);
                boolean exists = sc != null && sc.getRoot() != null
                    && (matchExists(sc.getRoot(), needle, null) || matchExists(sc.getRoot(), null, needle));
                return "COMPONENT_EXISTS".equals(condition) == exists;
            }
            case "WINDOW_COUNT" -> {
                return expected != null && windows().size() == Integer.parseInt(expected);
            }
            case "EDT_IDLE" -> {
                // We are on the FX thread inside onUi; reaching here means a pulse ran.
                return Boolean.TRUE;
            }
            default -> throw new IllegalArgumentException("Unknown condition type: " + condition);
        }
    }

    private Boolean matchExists(Node node, String nodeId, String text) {
        if (nodeId != null && nodeId.equals(node.getId())) {
            return Boolean.TRUE;
        }
        if (text != null) {
            String t = FxScene.textOf(node);
            if (t != null && t.contains(text)) {
                return Boolean.TRUE;
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (Boolean.TRUE.equals(matchExists(child, nodeId, text))) {
                    return Boolean.TRUE;
                }
            }
        }
        return Boolean.FALSE;
    }

    // ---- windows ----------------------------------------------------------

    /** Wire contract (WindowService.selectWindow): {@code index} into list_windows. */
    @Override
    public String selectWindow(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Map<String, Object> q = new LinkedHashMap<>(p);
            if (q.get("index") != null && q.get("windowIndex") == null) {
                q.put("windowIndex", q.get("index"));
            }
            Window w = targetWindow(q);
            if (w == null) {
                throw new IllegalStateException("No JavaFX window");
            }
            activeWindow = w;
            if (w instanceof Stage s) {
                s.requestFocus();
            }
            return "Selected window " + FxScene.titleOf(w);
        });
    }

    @Override
    public String resizeWindow(Map<String, Object> p) throws Exception {
        int width = intOr(p, "width", -1);
        int height = intOr(p, "height", -1);
        return onUi(() -> {
            Window w = targetWindow(p);
            if (width > 0) {
                w.setWidth(width);
            }
            if (height > 0) {
                w.setHeight(height);
            }
            return "Resized to " + (int) w.getWidth() + "x" + (int) w.getHeight();
        });
    }

    @Override
    public String moveWindow(Map<String, Object> p) throws Exception {
        int x = intOr(p, "x", Integer.MIN_VALUE);
        int y = intOr(p, "y", Integer.MIN_VALUE);
        return onUi(() -> {
            Window w = targetWindow(p);
            if (x != Integer.MIN_VALUE) {
                w.setX(x);
            }
            if (y != Integer.MIN_VALUE) {
                w.setY(y);
            }
            return "Moved to " + (int) w.getX() + "," + (int) w.getY();
        });
    }

    @Override
    public String setWindowState(Map<String, Object> p) throws Exception {
        String state = required(p, "state").toUpperCase();
        return onUi(() -> {
            Window w = targetWindow(p);
            if (!(w instanceof Stage s)) {
                throw new IllegalStateException("Window is not a Stage and has no state");
            }
            switch (state) {
                case "MAXIMIZED" -> s.setMaximized(true);
                case "MINIMIZED", "ICONIFIED" -> s.setIconified(true);
                case "NORMAL", "RESTORED" -> {
                    s.setIconified(false);
                    s.setMaximized(false);
                }
                case "FULLSCREEN" -> s.setFullScreen(true);
                default -> throw new IllegalArgumentException("Unknown window state: " + state);
            }
            return "Window state set to " + state;
        });
    }

    @Override
    public Object closeWindow(Map<String, Object> p) throws Exception {
        return action(() -> {
            Window w = targetWindow(p);
            if (w == null) {
                throw new IllegalStateException("No JavaFX window");
            }
            // Exactly what the window manager's close button does: fire
            // WINDOW_CLOSE_REQUEST, and the window hides itself unless a handler
            // consumes it. Stage.close() would skip onCloseRequest altogether, so
            // a "save changes?" prompt or a veto would never run. A handler that
            // calls showAndWait keeps this action open, giving a pending result.
            Event.fireEvent(w, new WindowEvent(w, WindowEvent.WINDOW_CLOSE_REQUEST));
            if (w.isShowing()) {
                return "Close of " + FxScene.titleOf(w) + " was vetoed by the application; the window is still open";
            }
            return "Closed " + FxScene.titleOf(w);
        });
    }

    // ---- misc -------------------------------------------------------------

    @Override
    public Map<String, Object> getClipboard() throws Exception {
        return onUi(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", Clipboard.getSystemClipboard().getString());
            return m;
        });
    }

    @Override
    public String setClipboard(Map<String, Object> p) throws Exception {
        String text = required(p, "text");
        return onUi(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
            return "Clipboard set (" + text.length() + " characters)";
        });
    }

    @Override
    public String evaluateJava(Map<String, Object> p) {
        throw unsupported("evaluate_java", "arbitrary evaluation is only wired for Swing targets");
    }
}
