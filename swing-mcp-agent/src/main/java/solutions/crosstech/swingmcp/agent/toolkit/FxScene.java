package solutions.crosstech.swingmcp.agent.toolkit;

import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;
import solutions.crosstech.swingmcp.agent.ClassNames;
import solutions.crosstech.swingmcp.common.dto.ComponentDescriptor;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks a JavaFX scene graph into the same descriptor shape Swing produces, and
 * keeps the uid registry that lets a client address a node across calls.
 *
 * <h2>Why this is not a straight port of the Swing scanner</h2>
 * <p>A Swing component tree is close to what a user sees: a {@code JButton} is
 * one node. A JavaFX scene graph is not — every {@code Control} is rendered by a
 * skin that is itself a sub-graph, so one button is a {@code Button} containing
 * a {@code LabeledText}, and one text field is a {@code TextField} containing
 * {@code Pane}, {@code Path} and {@code Text} nodes for its caret and selection
 * highlight. Handing that raw tree to a model is both far larger and far less
 * meaningful than the Swing equivalent.</p>
 *
 * <p>The rule applied here: <strong>a {@code Control} is a leaf.</strong> Its
 * value is read through its own API rather than by descending into its skin.
 * The exceptions are the controls that genuinely hold user content —
 * {@link ScrollPane}, {@link TabPane}, {@link SplitPane}, {@link TitledPane},
 * {@link Accordion}, {@link ToolBar}, {@link ButtonBar} — whose content is
 * traversed explicitly, so nothing a user can see is lost. Anything from
 * {@code javafx.scene.control.skin} or {@code com.sun.javafx} is skipped
 * outright as a rendering detail.</p>
 */
final class FxScene {

    static final String UID_PREFIX = "fx-";

    private final AtomicInteger uidCounter = new AtomicInteger(0);
    /** Live nodes only: pruned, so a detached node is not kept alive by the registry. */
    private final ConcurrentHashMap<String, Node> uidToNode = new ConcurrentHashMap<>();
    /** Every node ever seen, weakly: a node keeps its uid across detach and re-attach. */
    private final Map<Node, String> nodeToUid = new WeakHashMap<>();
    private final ConcurrentHashMap<String, WeakReference<MenuItem>> uidToMenuItem = new ConcurrentHashMap<>();
    private final Map<MenuItem, String> menuItemToUid = new WeakHashMap<>();

    // ---- uid registry -----------------------------------------------------

    synchronized String uidFor(Node node) {
        String existing = nodeToUid.get(node);
        if (existing != null) {
            // prune() may have dropped it while the node was detached; seeing it
            // again means it is back in a scene, so it must resolve again.
            uidToNode.putIfAbsent(existing, node);
            return existing;
        }
        String uid = UID_PREFIX + uidCounter.incrementAndGet();
        nodeToUid.put(node, uid);
        uidToNode.put(uid, node);
        return uid;
    }

    synchronized String uidFor(MenuItem item) {
        String existing = menuItemToUid.get(item);
        if (existing != null) {
            uidToMenuItem.putIfAbsent(existing, new WeakReference<>(item));
            return existing;
        }
        String uid = UID_PREFIX + uidCounter.incrementAndGet();
        menuItemToUid.put(item, uid);
        uidToMenuItem.put(uid, new WeakReference<>(item));
        return uid;
    }

    Node node(String uid) {
        Node n = uidToNode.get(uid);
        if (n == null) {
            n = reattached(uid);
        }
        if (n == null) {
            throw new IllegalArgumentException("Unknown uid: " + uid
                + ". Take a fresh snapshot — uids are invalidated when the scene is rebuilt.");
        }
        return n;
    }

    /**
     * A pruned node that has since gone back into a live scene, re-registered;
     * null if there is none. Only reached on a miss, so the scan is rare.
     */
    private synchronized Node reattached(String uid) {
        for (Map.Entry<Node, String> e : nodeToUid.entrySet()) {
            if (uid.equals(e.getValue()) && e.getKey() != null && e.getKey().getScene() != null) {
                uidToNode.put(uid, e.getKey());
                return e.getKey();
            }
        }
        return null;
    }

    MenuItem menuItem(String uid) {
        WeakReference<MenuItem> ref = uidToMenuItem.get(uid);
        MenuItem m = ref == null ? null : ref.get();
        if (m == null) {
            throw new IllegalArgumentException("Unknown menu uid: " + uid);
        }
        return m;
    }

    boolean isMenuUid(String uid) {
        WeakReference<MenuItem> ref = uidToMenuItem.get(uid);
        return ref != null && ref.get() != null;
    }

    /**
     * Drops entries whose node is no longer attached to a live scene, and menu
     * items that have been collected. A dropped node keeps its uid in the weak
     * reverse map, so it resolves again if it is re-attached.
     */
    synchronized void prune() {
        uidToNode.entrySet().removeIf(e -> e.getValue().getScene() == null);
        uidToMenuItem.entrySet().removeIf(e -> e.getValue().get() == null);
    }

    // ---- walking ----------------------------------------------------------

    /** Mutable per-snapshot budget capping node count and depth. */
    static final class Budget {
        private int remaining;
        private final int maxDepth;
        boolean truncated;

        Budget(int maxNodes, int maxDepth) {
            this.remaining = maxNodes;
            this.maxDepth = maxDepth;
        }

        boolean take() {
            if (remaining <= 0) {
                truncated = true;
                return false;
            }
            remaining--;
            return true;
        }

        boolean deeper(int depth) {
            return depth < maxDepth;
        }
    }

    /** True for nodes that exist only to render a control. */
    static boolean isSkinInternal(Node node) {
        Class<?> c = node.getClass();
        String pkg = c.getPackageName();
        return pkg.startsWith("javafx.scene.control.skin")
            || pkg.startsWith("com.sun.javafx")
            || pkg.startsWith("com.sun.glass");
    }

    /**
     * Content-bearing controls whose children are real UI rather than skin, and
     * therefore must be traversed even though a {@code Control} is normally a leaf.
     */
    static List<Node> contentOf(Control control) {
        List<Node> out = new ArrayList<>();
        if (control instanceof ScrollPane sp) {
            addIfPresent(out, sp.getContent());
        } else if (control instanceof TitledPane tp) {
            addIfPresent(out, tp.getContent());
        } else if (control instanceof TabPane tabs) {
            for (Tab t : tabs.getTabs()) {
                addIfPresent(out, t.getContent());
            }
        } else if (control instanceof SplitPane sp) {
            out.addAll(sp.getItems());
        } else if (control instanceof Accordion acc) {
            out.addAll(acc.getPanes());
        } else if (control instanceof ToolBar tb) {
            out.addAll(tb.getItems());
        } else if (control instanceof ButtonBar bb) {
            out.addAll(bb.getButtons());
        }
        return out;
    }

    private static void addIfPresent(List<Node> out, Node n) {
        if (n != null) {
            out.add(n);
        }
    }

    /** Describes the children of {@code parent}, which sit at {@code depth}. */
    List<ComponentDescriptor> scanChildren(Parent parent, boolean visibleOnly, Budget budget, int depth) {
        List<ComponentDescriptor> out = new ArrayList<>();
        for (Node child : parent.getChildrenUnmodifiable()) {
            ComponentDescriptor d = describe(child, visibleOnly, budget, depth);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    ComponentDescriptor describe(Node node, boolean visibleOnly, Budget budget, int depth) {
        if (isSkinInternal(node)) {
            return null;
        }
        if (visibleOnly && !node.isVisible()) {
            return null;
        }
        if (!budget.take()) {
            return null;
        }

        List<ComponentDescriptor> children;
        Integer omitted = null;
        if (node instanceof Control control) {
            // A Control is a leaf: its value comes from its API, not its skin.
            List<Node> content = contentOf(control);
            children = new ArrayList<>();
            if (!content.isEmpty() && budget.deeper(depth)) {
                for (Node c : content) {
                    ComponentDescriptor d = describe(c, visibleOnly, budget, depth + 1);
                    if (d != null) {
                        children.add(d);
                    }
                }
            } else if (!content.isEmpty()) {
                omitted = content.size();
            }
            if (control instanceof MenuBar bar) {
                children.addAll(describeMenus(bar.getMenus()));
            }
        } else if (node instanceof Parent p) {
            // Same depth rule as for Control content and the Swing scanner:
            // children at depth + 1 are included while depth < maxDepth.
            if (budget.deeper(depth)) {
                children = scanChildren(p, visibleOnly, budget, depth + 1);
            } else {
                children = List.of();
                long below = p.getChildrenUnmodifiable().stream().filter(c -> !isSkinInternal(c)).count();
                omitted = below > 0 ? (int) below : null;
            }
        } else {
            children = List.of();
        }
        if (omitted != null) {
            // Cut by maxDepth: say how much is below so the client can drill in by uid.
            budget.truncated = true;
        }

        String text = textOf(node);
        String tooltip = tooltipOf(node);
        Bounds b = node.getBoundsInParent();
        return new ComponentDescriptor(
            uidFor(node),
            ClassNames.displayName(node.getClass()),
            node.getId(),
            text,
            tooltip != null && tooltip.equals(text) ? null : tooltip,
            !node.isDisabled(),
            node.isVisible(),
            node.isFocusTraversable(),
            (int) b.getMinX(), (int) b.getMinY(),
            (int) b.getWidth(), (int) b.getHeight(),
            selectionStateOf(node),
            children.isEmpty() ? null : children,
            omitted
        );
    }

    /** The tooltip text of a control, if it has one. */
    static String tooltipOf(Node node) {
        if (node instanceof Control c && c.getTooltip() != null) {
            String tip = c.getTooltip().getText();
            return tip == null || tip.isBlank() ? null : tip;
        }
        return null;
    }

    /** Menus are not Nodes, so they get their own descriptors. */
    private List<ComponentDescriptor> describeMenus(List<? extends MenuItem> items) {
        List<ComponentDescriptor> out = new ArrayList<>();
        for (MenuItem item : items) {
            List<ComponentDescriptor> kids = item instanceof Menu m
                ? describeMenus(m.getItems())
                : List.of();
            out.add(new ComponentDescriptor(
                uidFor(item),
                ClassNames.displayName(item.getClass()),
                item.getId(),
                item.getText(),
                !item.isDisable(),
                item.isVisible(),
                false,
                0, 0, 0, 0,
                null,
                kids.isEmpty() ? null : kids
            ));
        }
        return out;
    }

    // ---- value extraction -------------------------------------------------

    /** The user-visible text of a node, read through the control's own API. */
    static String textOf(Node node) {
        if (node instanceof Labeled l) {
            return l.getText();
        }
        if (node instanceof TextInputControl t) {
            return t.getText();
        }
        if (node instanceof ComboBox<?> cb) {
            Object v = cb.getValue();
            if (v == null && cb.isEditable() && cb.getEditor() != null) {
                return cb.getEditor().getText();
            }
            return v == null ? null : String.valueOf(v);
        }
        if (node instanceof ChoiceBox<?> cb) {
            return cb.getValue() == null ? null : String.valueOf(cb.getValue());
        }
        if (node instanceof DatePicker dp) {
            return dp.getValue() == null ? null : String.valueOf(dp.getValue());
        }
        if (node instanceof ComboBoxBase<?> cbb) {
            return cbb.getValue() == null ? null : String.valueOf(cbb.getValue());
        }
        if (node instanceof Spinner<?> sp) {
            return sp.getValue() == null ? null : String.valueOf(sp.getValue());
        }
        if (node instanceof Slider s) {
            return String.valueOf(s.getValue());
        }
        if (node instanceof ProgressIndicator pi) {
            return String.valueOf(pi.getProgress());
        }
        if (node instanceof javafx.scene.text.Text t) {
            return t.getText();
        }
        return null;
    }

    /** Selection or check state, where the control has one. */
    static String selectionStateOf(Node node) {
        if (node instanceof CheckBox cb) {
            return cb.isIndeterminate() ? "indeterminate" : (cb.isSelected() ? "selected" : "unselected");
        }
        if (node instanceof ToggleButton tb) {
            return tb.isSelected() ? "selected" : "unselected";
        }
        if (node instanceof ListView<?> lv) {
            int i = lv.getSelectionModel().getSelectedIndex();
            return i < 0 ? "none" : "index " + i;
        }
        if (node instanceof TableView<?> tv) {
            int i = tv.getSelectionModel().getSelectedIndex();
            return i < 0 ? "none" : "row " + i;
        }
        if (node instanceof TreeView<?> tv) {
            TreeItem<?> item = tv.getSelectionModel().getSelectedItem();
            return item == null ? "none" : String.valueOf(item.getValue());
        }
        if (node instanceof TabPane tp) {
            Tab t = tp.getSelectionModel().getSelectedItem();
            return t == null ? "none" : t.getText();
        }
        return null;
    }

    // ---- tabular reads ----------------------------------------------------

    /**
     * Rows {@code startRow..endRow} of the table, zero-based and inclusive as on
     * the wire (and in the Swing scanner), clamped to what exists. {@code truncated}
     * is set when rows exist past the last one returned.
     */
    static Map<String, Object> tableData(TableView<?> table, int startRow, int endRow) {
        List<String> columns = new ArrayList<>();
        for (TableColumn<?, ?> c : table.getColumns()) {
            columns.add(c.getText());
        }
        List<List<String>> rows = new ArrayList<>();
        int total = table.getItems().size();
        // Exclusive bound of the rows returned; written so endRow = MAX_VALUE cannot overflow.
        int limit = endRow < 0 ? 0 : Math.min(total - 1, endRow) + 1;
        for (int r = Math.min(startRow, total); r < limit; r++) {
            List<String> row = new ArrayList<>();
            for (TableColumn<?, ?> c : table.getColumns()) {
                Object v = cellValue(c, r);
                row.add(v == null ? null : String.valueOf(v));
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("rowCount", total);
        out.put("startRow", Math.min(startRow, total));
        if (limit < total) {
            out.put("truncated", true);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object cellValue(TableColumn<?, ?> column, int rowIndex) {
        try {
            TableColumn<Object, ?> c = (TableColumn<Object, ?>) column;
            javafx.beans.value.ObservableValue<?> ov = c.getCellObservableValue(rowIndex);
            return ov == null ? null : ov.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /** Items {@code start..end} of the list, zero-based and inclusive, clamped to what exists. */
    static Map<String, Object> listItems(ListView<?> list, int start, int end) {
        ObservableList<?> items = list.getItems();
        List<String> values = new ArrayList<>();
        int limit = end < 0 ? 0 : Math.min(items.size() - 1, end) + 1;
        for (int i = Math.min(start, items.size()); i < limit; i++) {
            Object v = items.get(i);
            values.add(v == null ? null : String.valueOf(v));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", values);
        out.put("itemCount", items.size());
        out.put("selectedIndex", list.getSelectionModel().getSelectedIndex());
        if (limit < items.size()) {
            out.put("truncated", true);
        }
        return out;
    }

    // ---- windows ----------------------------------------------------------

    static String titleOf(Window w) {
        if (w instanceof Stage s) {
            return s.getTitle() == null ? "" : s.getTitle();
        }
        return ClassNames.displayName(w.getClass());
    }

    static boolean isModal(Window w) {
        return w instanceof Stage s && s.getModality() != javafx.stage.Modality.NONE;
    }

    static Scene sceneOf(Window w) {
        return w.getScene();
    }
}
