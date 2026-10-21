package solutions.crosstech.swingmcp.agent.toolkit;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
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
import javafx.stage.Stage;
import javafx.stage.Window;
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
            Window win = targetWindow(p);
            if (win == null) {
                return new SnapshotNode("No window", "null", 0, 0, 0, 0, List.of());
            }
            boolean visibleOnly = "VISIBLE".equalsIgnoreCase(str(p, "filter"));
            FxScene.Budget budget = new FxScene.Budget(
                Math.max(1, intOr(p, "maxNodes", DEFAULT_MAX_NODES)),
                intOr(p, "maxDepth", Integer.MAX_VALUE));
            Scene sc = FxScene.sceneOf(win);
            List<ComponentDescriptor> children = sc == null || sc.getRoot() == null
                ? List.of()
                : List.of(scene.describe(sc.getRoot(), visibleOnly, budget, 0));
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
            int startRow = Math.max(0, intOr(p, "startRow", 0));
            int endRow = intOr(p, "endRow", -1);
            return FxScene.tableData(table, startRow, endRow < 0 ? startRow + intOr(p, "maxRows", DEFAULT_MAX_ROWS) : endRow);
        });
    }

    @Override
    public Map<String, Object> getListItems(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Node n = nodeParam(p);
            if (n instanceof ListView<?> list) {
                int start = Math.max(0, intOr(p, "startIndex", 0));
                int end = intOr(p, "endIndex", -1);
                return FxScene.listItems(list, start, end < 0 ? start + intOr(p, "maxItems", DEFAULT_MAX_ROWS) : end);
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

    @Override
    public Object click(Map<String, Object> p) throws Exception {
        String uid = required(p, "uid");
        return action(() -> {
            if (scene.isMenuUid(uid)) {
                scene.menuItem(uid).fire();
                return "Fired menu item " + uid;
            }
            Node n = scene.node(uid);
            if (n.isDisabled()) {
                throw new IllegalStateException("Component " + uid + " is disabled");
            }
            if (n instanceof ButtonBase b) {
                b.fire();
                return "Clicked " + uid;
            }
            dispatchClick(n);
            return "Dispatched click to " + uid;
        });
    }

    /** Synthesises a press/release/click sequence on a node that has no fire(). */
    private void dispatchClick(Node n) {
        Bounds b = n.getBoundsInLocal();
        double x = b.getWidth() / 2;
        double y = b.getHeight() / 2;
        for (javafx.event.EventType<MouseEvent> type :
            List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            Event.fireEvent(n, new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, true, null));
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
     */
    @Override
    public String typeText(Map<String, Object> p) throws Exception {
        String text = required(p, "text");
        return onUi(() -> {
            Node n = keyTarget(str(p, "uid"), p);
            for (char c : text.toCharArray()) {
                String s = String.valueOf(c);
                Event.fireEvent(n, new KeyEvent(KeyEvent.KEY_TYPED, s, s, KeyCode.UNDEFINED,
                    false, false, false, false));
            }
            return "Typed " + text.length() + " character(s)";
        });
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

    @Override
    public String hover(Map<String, Object> p) throws Exception {
        return onUi(() -> {
            Node n = nodeParam(p);
            Bounds b = n.getBoundsInLocal();
            Event.fireEvent(n, new MouseEvent(MouseEvent.MOUSE_ENTERED,
                b.getWidth() / 2, b.getHeight() / 2, 0, 0, MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, false, null));
            return "Hovered " + str(p, "uid");
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
            String[] parts = path.split("/");
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
            for (Window w : windows()) {
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
     * {@code "ENTER"}, {@code "TAB"} or {@code "CTRL+A"}, matching the Swing scanner.
     */
    @Override
    public Object pressKey(Map<String, Object> p) throws Exception {
        String keys = str(p, "keys") != null ? str(p, "keys") : required(p, "key");
        String uid = str(p, "uid");
        // action, not onUi: a key may open a modal dialog and never return on the FX thread.
        return action(() -> {
            boolean ctrl = false, shift = false, alt = false, meta = false;
            KeyCode code = null;
            for (String part : keys.toUpperCase().split("\\+")) {
                switch (part.trim()) {
                    case "CTRL", "CONTROL" -> ctrl = true;
                    case "SHIFT" -> shift = true;
                    case "ALT" -> alt = true;
                    case "META", "CMD", "COMMAND" -> meta = true;
                    default -> code = KeyCode.valueOf(part.trim().replace(' ', '_'));
                }
            }
            if (code == null) {
                throw new IllegalArgumentException("No key in chord: " + keys);
            }
            Node target = keyTarget(uid, p);
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, ctrl, alt, meta));
            return "Key pressed: " + keys;
        });
    }

    @Override
    public String drag(Map<String, Object> p) {
        throw unsupported("drag", "JavaFX drag-and-drop is application-defined; "
            + "use the control's own API, or click the source and target in sequence");
    }

    @Override
    public String scroll(Map<String, Object> p) throws Exception {
        String direction = str(p, "direction") == null ? "DOWN" : str(p, "direction").toUpperCase();
        double amount = intOr(p, "amount", 1) * 0.1;
        return onUi(() -> {
            Node n = nodeParam(p);
            javafx.scene.control.ScrollPane pane = n instanceof javafx.scene.control.ScrollPane sp
                ? sp : enclosingScrollPane(n);
            if (pane == null) {
                throw new IllegalArgumentException("Component " + str(p, "uid")
                    + " is not inside a ScrollPane");
            }
            switch (direction) {
                case "UP" -> pane.setVvalue(clamp(pane.getVvalue() - amount));
                case "DOWN" -> pane.setVvalue(clamp(pane.getVvalue() + amount));
                case "LEFT" -> pane.setHvalue(clamp(pane.getHvalue() - amount));
                case "RIGHT" -> pane.setHvalue(clamp(pane.getHvalue() + amount));
                default -> throw new IllegalArgumentException("Unknown direction: " + direction);
            }
            return "Scrolled " + direction;
        });
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
            if (w instanceof Stage s) {
                s.close();
                return "Closed " + FxScene.titleOf(w);
            }
            w.hide();
            return "Hid " + FxScene.titleOf(w);
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
