package solutions.crosstech.swingmcp.agent;

import solutions.crosstech.swingmcp.common.dto.ComponentDescriptor;
import solutions.crosstech.swingmcp.common.dto.SnapshotNode;
import solutions.crosstech.swingmcp.common.enums.ComponentStateFilter;
import solutions.crosstech.swingmcp.common.enums.ScrollDirection;
import solutions.crosstech.swingmcp.common.enums.WindowState;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;

/**
 * Scans Swing component trees and performs interactions on behalf of the agent.
 * Maintains a UID registry mapping string UIDs to {@link Component} instances
 * via weak references so that garbage-collected components are automatically
 * removed.
 */
public class ComponentScanner {

    private final AtomicInteger uidCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Component> uidToComponent = new ConcurrentHashMap<>();
    private final Map<Component, String> componentToUid = new WeakHashMap<>();
    private Window activeWindow;

    /**
     * Takes a snapshot of the current active window's component tree.
     *
     * @param params optional params (may contain "windowIndex")
     * @return a {@link SnapshotNode} with full component tree
     */
    /** Default cap on the number of component nodes returned by a single snapshot. */
    private static final int DEFAULT_MAX_NODES = 2000;

    public SnapshotNode takeSnapshot(Map<String, Object> params) {
        Window win = getTargetWindow(params);
        if (win == null) {
            return new SnapshotNode("No window", "null", 0, 0, 0, 0, List.of());
        }
        ComponentStateFilter filter = ComponentStateFilter.valueOf(
            getString(params, "filter", "ALL").toUpperCase());
        int maxNodes = params.containsKey("maxNodes") ? getInt(params, "maxNodes", DEFAULT_MAX_NODES) : DEFAULT_MAX_NODES;
        int maxDepth = params.containsKey("maxDepth") ? getInt(params, "maxDepth", Integer.MAX_VALUE) : Integer.MAX_VALUE;
        ScanBudget budget = new ScanBudget(Math.max(1, maxNodes), maxDepth);
        List<ComponentDescriptor> children = scanComponent(win, filter, budget, 0);
        Rectangle bounds = win.getBounds();
        return new SnapshotNode(
            getWindowTitle(win),
            win.getClass().getSimpleName(),
            bounds.x, bounds.y, bounds.width, bounds.height,
            children,
            budget.truncated ? Boolean.TRUE : null
        );
    }

    /** Mutable per-snapshot budget that caps node count and tree depth. */
    private static final class ScanBudget {
        private int remaining;
        private final int maxDepth;
        private boolean truncated;

        ScanBudget(int maxNodes, int maxDepth) {
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
    }

    /**
     * Lists all visible windows in the current JVM.
     *
     * @return list of window descriptors as maps
     */
    public List<Map<String, Object>> listWindows() {
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Window w : Window.getWindows()) {
            if (w.isVisible()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("index", index++);
                info.put("title", getWindowTitle(w));
                info.put("class", w.getClass().getSimpleName());
                Rectangle b = w.getBounds();
                info.put("x", b.x);
                info.put("y", b.y);
                info.put("width", b.width);
                info.put("height", b.height);
                info.put("focused", w.isFocused());
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Sets the active window by index.
     */
    public String selectWindow(Map<String, Object> params) {
        int index = getInt(params, "index", 0);
        List<Window> visible = getVisibleWindows();
        if (index < 0 || index >= visible.size()) {
            throw new IllegalArgumentException("Window index " + index + " out of range [0, " + (visible.size() - 1) + "]");
        }
        activeWindow = visible.get(index);
        activeWindow.toFront();
        activeWindow.requestFocus();
        return "Window selected: " + getWindowTitle(activeWindow);
    }

    /**
     * Resizes the active (or specified) window.
     */
    public String resizeWindow(Map<String, Object> params) {
        Window win = getTargetWindow(params);
        if (win == null) {
            throw new IllegalStateException("No active window");
        }
        int width = getInt(params, "width", win.getWidth());
        int height = getInt(params, "height", win.getHeight());
        win.setSize(width, height);
        return "Window resized to " + width + "x" + height;
    }

    /**
     * Closes the specified window by dispatching a WINDOW_CLOSING event.
     */
    public String closeWindow(Map<String, Object> params) {
        Window win = getTargetWindow(params);
        if (win == null) {
            throw new IllegalStateException("No active window");
        }
        win.dispatchEvent(new WindowEvent(win, WindowEvent.WINDOW_CLOSING));
        return "Window closing event dispatched";
    }

    /**
     * Returns detailed info for a component identified by UID.
     */
    public Map<String, Object> getComponentDetails(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        return describeComponent(comp, true);
    }

    /**
     * Takes a screenshot of the active window or a specific component.
     */
    public Map<String, Object> takeScreenshot(Map<String, Object> params) throws IOException, AWTException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot take screenshot in headless environment");
        }
        Component target;
        String uid = (String) params.get("uid");
        if (uid != null && !uid.isBlank()) {
            target = resolveUid(uid);
        } else {
            target = getTargetWindow(params);
        }
        if (target == null) {
            throw new IllegalStateException("No target component or window for screenshot");
        }
        Robot robot = new Robot();
        Rectangle bounds = new Rectangle(target.getLocationOnScreen(), target.getSize());
        BufferedImage img = robot.createScreenCapture(bounds);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageBase64", base64);
        result.put("mimeType", "image/png");
        result.put("width", img.getWidth());
        result.put("height", img.getHeight());
        return result;
    }

    /**
     * Clicks a component identified by UID.
     */
    public String click(Map<String, Object> params) throws AWTException {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        String buttonStr = getString(params, "button", "LEFT");
        String clickTypeStr = getString(params, "clickType", "SINGLE");

        if (comp instanceof AbstractButton btn) {
            btn.doClick();
            return "Clicked button: " + btn.getText();
        }

        if (!GraphicsEnvironment.isHeadless()) {
            Robot robot = new Robot();
            Point loc = comp.getLocationOnScreen();
            int cx = loc.x + comp.getWidth() / 2;
            int cy = loc.y + comp.getHeight() / 2;
            int mask = getMouseMask(buttonStr);
            robot.mouseMove(cx, cy);
            robot.mousePress(mask);
            robot.mouseRelease(mask);
            if ("DOUBLE".equalsIgnoreCase(clickTypeStr)) {
                robot.mousePress(mask);
                robot.mouseRelease(mask);
            }
        }
        return "Clicked component: " + uid;
    }

    /**
     * Fills a text component, spinner, or editable combo box with the given text.
     */
    public String fill(Map<String, Object> params) {
        String uid = getString(params, "uid");
        String text = getString(params, "text");
        Component comp = resolveUid(uid);

        if (comp instanceof JTextComponent tc) {
            tc.setText(text);
        } else if (comp instanceof JSpinner spinner) {
            spinner.setValue(parseSpinnerValue(spinner, text));
        } else if (comp instanceof JComboBox<?> combo && combo.isEditable()) {
            combo.setSelectedItem(text);
        } else {
            throw new IllegalArgumentException("Component " + uid + " does not support text input: " + comp.getClass().getSimpleName());
        }
        return "Filled: " + uid;
    }

    /**
     * Selects an option in a JList, JComboBox, or JTabbedPane by index or text.
     */
    @SuppressWarnings("unchecked")
    public String selectOption(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        String text = (String) params.get("text");
        Integer index = params.containsKey("index") ? getInt(params, "index", 0) : null;

        if (comp instanceof JComboBox<?> combo) {
            if (index != null) {
                combo.setSelectedIndex(index);
            } else {
                ((JComboBox<Object>) combo).setSelectedItem(text);
            }
        } else if (comp instanceof JList<?> list) {
            if (index != null) {
                list.setSelectedIndex(index);
            } else {
                int match = -1;
                List<String> available = new ArrayList<>();
                for (int i = 0; i < list.getModel().getSize(); i++) {
                    String item = String.valueOf(list.getModel().getElementAt(i));
                    available.add(item);
                    if (item.equals(text)) {
                        match = i;
                    }
                }
                if (match < 0) {
                    throw new IllegalArgumentException("No list item matches \"" + text
                        + "\" on " + uid + ". Available items: " + available);
                }
                list.setSelectedIndex(match);
            }
        } else if (comp instanceof JTabbedPane tabs) {
            if (index != null) {
                tabs.setSelectedIndex(index);
            } else {
                int match = -1;
                List<String> available = new ArrayList<>();
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    available.add(tabs.getTitleAt(i));
                    if (tabs.getTitleAt(i).equals(text)) {
                        match = i;
                    }
                }
                if (match < 0) {
                    throw new IllegalArgumentException("No tab titled \"" + text
                        + "\" on " + uid + ". Available tabs: " + available);
                }
                tabs.setSelectedIndex(match);
            }
        } else {
            throw new IllegalArgumentException("Component " + uid + " does not support option selection: " + comp.getClass().getSimpleName());
        }
        return "Option selected on: " + uid;
    }

    /**
     * Selects a tree node by path (e.g. "Root > Folder > Leaf").
     */
    public String selectTreeNode(Map<String, Object> params) {
        String uid = getString(params, "uid");
        String pathStr = getString(params, "path");
        Component comp = resolveUid(uid);

        if (!(comp instanceof JTree tree)) {
            throw new IllegalArgumentException("Component " + uid + " is not a JTree");
        }

        String[] parts = pathStr.split("\\s*>\\s*");
        TreePath treePath = findTreePath(tree, parts);
        if (treePath == null) {
            throw new IllegalArgumentException("Tree path not found: " + pathStr);
        }
        tree.setSelectionPath(treePath);
        tree.scrollPathToVisible(treePath);
        return "Tree node selected: " + pathStr;
    }

    /**
     * Selects a table cell by row and column.
     */
    public String selectTableCell(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        int row = getInt(params, "row", 0);
        int col = getInt(params, "col", 0);

        if (!(comp instanceof JTable table)) {
            throw new IllegalArgumentException("Component " + uid + " is not a JTable");
        }
        table.changeSelection(row, col, false, false);
        return "Table cell selected: [" + row + "," + col + "]";
    }

    /**
     * Selects a menu item by path (e.g. "File > Save").
     */
    public String selectMenuItem(Map<String, Object> params) {
        String pathStr = getString(params, "path");
        Window win = getTargetWindow(params);
        if (win == null) {
            throw new IllegalStateException("No active window");
        }
        String[] parts = pathStr.split("\\s*>\\s*");
        // Collect the menus whose popups get realized during lookup so they can
        // be hidden again afterwards (Issue #2: setPopupMenuVisible(true) in
        // findMenuItemInMenu otherwise leaks a lightweight popup into the
        // JLayeredPane that keeps reporting visible=true).
        List<javax.swing.JMenu> openedMenus = new ArrayList<>();
        JMenuItem item = findMenuItem(win, parts, openedMenus);
        if (item == null) {
            hideMenus(openedMenus, win);
            throw new IllegalArgumentException("Menu item not found: " + pathStr);
        }
        // Hide the realized popups and clear the selection path BEFORE the
        // click, so nothing leaks on screen or in the tree even while a modal
        // dialog opened by the action is blocking (Issue #1 Defect C, Issue #2).
        // Repeat in the finally as defense in case the action re-opened a menu.
        hideMenus(openedMenus, win);
        MenuSelectionManager.defaultManager().clearSelectedPath();
        try {
            item.doClick();
        } finally {
            hideMenus(openedMenus, win);
            MenuSelectionManager.defaultManager().clearSelectedPath();
        }
        return "Menu item clicked: " + pathStr;
    }

    /**
     * Sends a key press (e.g. "CTRL+S", "ENTER") using Robot.
     */
    public String pressKey(Map<String, Object> params) throws AWTException {
        String keys = getString(params, "keys");
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot press keys in headless environment");
        }
        Robot robot = new Robot();
        List<Integer> keyCodes = parseKeyChord(keys);
        for (int code : keyCodes) {
            robot.keyPress(code);
        }
        for (int i = keyCodes.size() - 1; i >= 0; i--) {
            robot.keyRelease(keyCodes.get(i));
        }
        return "Key pressed: " + keys;
    }

    /**
     * Drags from one component to another using Robot.
     */
    public String drag(Map<String, Object> params) throws AWTException {
        String fromUid = getString(params, "fromUid");
        String toUid = getString(params, "toUid");
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot drag in headless environment");
        }
        Component from = resolveUid(fromUid);
        Component to = resolveUid(toUid);
        Robot robot = new Robot();
        Point fromLoc = from.getLocationOnScreen();
        Point toLoc = to.getLocationOnScreen();
        robot.mouseMove(fromLoc.x + from.getWidth() / 2, fromLoc.y + from.getHeight() / 2);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseMove(toLoc.x + to.getWidth() / 2, toLoc.y + to.getHeight() / 2);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        return "Dragged from " + fromUid + " to " + toUid;
    }

    /**
     * Scrolls a scrollable component in the specified direction.
     */
    public String scroll(Map<String, Object> params) {
        String uid = getString(params, "uid");
        String directionStr = getString(params, "direction", "DOWN");
        int amount = getInt(params, "amount", 3);
        Component comp = resolveUid(uid);

        ScrollDirection direction = ScrollDirection.valueOf(directionStr.toUpperCase());
        JScrollPane scrollPane = findScrollPane(comp);
        if (scrollPane == null) {
            throw new IllegalArgumentException("No scroll pane found for: " + uid);
        }

        JScrollBar bar = (direction == ScrollDirection.UP || direction == ScrollDirection.DOWN)
            ? scrollPane.getVerticalScrollBar()
            : scrollPane.getHorizontalScrollBar();

        int delta = (direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT) ? amount : -amount;
        bar.setValue(bar.getValue() + delta * bar.getUnitIncrement());
        return "Scrolled " + direction + " by " + amount;
    }

    /**
     * Waits for a condition with polling. Params: conditionType, uid, expectedValue, timeoutMs.
     */
    public String waitFor(Map<String, Object> params) {
        String condType = getString(params, "conditionType");
        long timeoutMs = getLong(params, "timeoutMs", 5000L);
        long pollMs = 200L;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                boolean met = checkCondition(condType, params);
                if (met) {
                    return "Condition met: " + condType;
                }
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Wait interrupted", e);
            }
        }
        throw new RuntimeException("Timeout waiting for condition: " + condType + " after " + timeoutMs + "ms");
    }

    /**
     * Evaluates a Java snippet using JShell (if supported).
     */
    public String evaluateJava(Map<String, Object> params) {
        String code = getString(params, "code");
        Object jshell = null;
        try {
            Class<?> jshellClass = Class.forName("jdk.jshell.JShell");
            // Prefer in-process ("local") execution: it runs the snippet in this
            // JVM (so it can see the app's state) and, crucially, does not fork a
            // child JVM. Fall back to the default engine if the builder path fails.
            try {
                Object builder = jshellClass.getMethod("builder").invoke(null);
                builder = builder.getClass().getMethod("executionEngine", String.class)
                    .invoke(builder, "local");
                jshell = builder.getClass().getMethod("build").invoke(builder);
            } catch (Exception fallback) {
                jshell = jshellClass.getMethod("create").invoke(null);
            }
            Object events = jshellClass.getMethod("eval", String.class).invoke(jshell, code);
            return events.toString();
        } catch (Exception e) {
            throw new RuntimeException("JShell evaluation failed: " + e.getMessage(), e);
        } finally {
            // Always release the JShell instance; leaving it open leaks a JVM
            // (default engine) or interpreter state (local engine) per call.
            if (jshell != null) {
                try {
                    jshell.getClass().getMethod("close").invoke(jshell);
                } catch (Exception ignore) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * Finds components matching a query without taking a full snapshot.
     * Params: query (required), by (TEXT, NAME, TOOLTIP, CLASS, or ANY - default ANY).
     */
    public List<Map<String, Object>> findComponent(Map<String, Object> params) {
        String query = getString(params, "query");
        String by = getString(params, "by", "ANY").toUpperCase();
        String lowered = query.toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Window w : getVisibleWindows()) {
            collectMatches(w, lowered, by, matches);
        }
        return matches;
    }

    private void collectMatches(Component comp, String query, String by, List<Map<String, Object>> matches) {
        if (matchesQuery(comp, query, by)) {
            matches.add(describeComponent(comp, false));
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectMatches(child, query, by, matches);
            }
        }
    }

    private boolean matchesQuery(Component comp, String query, String by) {
        boolean any = "ANY".equals(by);
        if (any || "TEXT".equals(by)) {
            String text = extractText(comp);
            if (text != null && text.toLowerCase().contains(query)) {
                return true;
            }
        }
        if (any || "NAME".equals(by)) {
            String name = comp.getName();
            if (name != null && name.toLowerCase().contains(query)) {
                return true;
            }
        }
        if (any || "TOOLTIP".equals(by)) {
            if (comp instanceof JComponent jc) {
                String tip = jc.getToolTipText();
                if (tip != null && tip.toLowerCase().contains(query)) {
                    return true;
                }
            }
        }
        if (any || "CLASS".equals(by)) {
            if (comp.getClass().getSimpleName().toLowerCase().contains(query)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the model contents of a JTable.
     * Params: uid (required), startRow, endRow (optional zero-based inclusive range).
     */
    public Map<String, Object> getTableData(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        if (!(comp instanceof JTable table)) {
            throw new IllegalArgumentException("Component " + uid + " is not a JTable");
        }
        int rowCount = table.getRowCount();
        int colCount = table.getColumnCount();
        int startRow = Math.max(0, getInt(params, "startRow", 0));
        int endRow = Math.min(rowCount - 1, getInt(params, "endRow", rowCount - 1));
        List<String> columns = new ArrayList<>();
        for (int c = 0; c < colCount; c++) {
            columns.add(table.getColumnName(c));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (int r = startRow; r <= endRow; r++) {
            List<Object> row = new ArrayList<>();
            for (int c = 0; c < colCount; c++) {
                Object value = table.getValueAt(r, c);
                row.add(value == null ? null : String.valueOf(value));
            }
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", rowCount);
        result.put("columns", columns);
        result.put("rows", rows);
        return result;
    }

    /**
     * Extracts the model contents of a JList or the visible rows of a JTree.
     * Params: uid (required), startIndex, endIndex (optional zero-based inclusive range).
     */
    public Map<String, Object> getListItems(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        List<String> items = new ArrayList<>();
        int total;
        if (comp instanceof JList<?> list) {
            total = list.getModel().getSize();
            int start = Math.max(0, getInt(params, "startIndex", 0));
            int end = Math.min(total - 1, getInt(params, "endIndex", total - 1));
            for (int i = start; i <= end; i++) {
                items.add(String.valueOf(list.getModel().getElementAt(i)));
            }
        } else if (comp instanceof JTree tree) {
            total = tree.getRowCount();
            int start = Math.max(0, getInt(params, "startIndex", 0));
            int end = Math.min(total - 1, getInt(params, "endIndex", total - 1));
            for (int i = start; i <= end; i++) {
                TreePath path = tree.getPathForRow(i);
                StringBuilder sb = new StringBuilder();
                for (Object node : path.getPath()) {
                    if (sb.length() > 0) {
                        sb.append(" > ");
                    }
                    sb.append(node);
                }
                items.add(sb.toString());
            }
        } else {
            throw new IllegalArgumentException("Component " + uid + " is not a JList or JTree");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemCount", total);
        result.put("items", items);
        return result;
    }

    /**
     * Moves the mouse over a component to trigger hover effects and tooltips.
     */
    public String hover(Map<String, Object> params) throws AWTException {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot hover in headless environment");
        }
        Robot robot = new Robot();
        Point loc = comp.getLocationOnScreen();
        robot.mouseMove(loc.x + comp.getWidth() / 2, loc.y + comp.getHeight() / 2);
        return "Hovering over: " + uid;
    }

    /**
     * Gives keyboard focus to a component by UID.
     */
    public String focus(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        Window win = SwingUtilities.getWindowAncestor(comp);
        if (win != null) {
            win.toFront();
        }
        boolean requested = comp.requestFocusInWindow();
        if (!requested) {
            comp.requestFocus();
        }
        return "Focus requested: " + uid;
    }

    /**
     * Types text character-by-character into the focused component using key events.
     * Params: text (required), uid (optional - focus this component first).
     */
    public String typeText(Map<String, Object> params) throws Exception {
        String text = getString(params, "text");
        String uid = (String) params.get("uid");
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot type text in headless environment");
        }
        if (uid != null && !uid.isBlank()) {
            invokeAndWaitQuietly(() -> focus(Map.of("uid", uid)));
        }
        Robot robot = new Robot();
        robot.setAutoDelay(20);
        for (char ch : text.toCharArray()) {
            typeChar(robot, ch);
        }
        return "Typed " + text.length() + " characters";
    }

    private void typeChar(Robot robot, char ch) {
        // Shift detection assumes a US keyboard layout; other layouts may
        // require different modifier combinations for these characters.
        boolean upper = Character.isUpperCase(ch) || "~!@#$%^&*()_+{}|:\"<>?".indexOf(ch) >= 0;
        int code = KeyEvent.getExtendedKeyCodeForChar(ch);
        if (code == KeyEvent.VK_UNDEFINED) {
            throw new IllegalArgumentException("Cannot type character: " + ch);
        }
        if (upper) {
            robot.keyPress(KeyEvent.VK_SHIFT);
        }
        try {
            robot.keyPress(code);
            robot.keyRelease(code);
        } finally {
            if (upper) {
                robot.keyRelease(KeyEvent.VK_SHIFT);
            }
        }
    }

    /**
     * Opens the context menu of a component and clicks an item by path.
     * Params: uid (required), path (required, e.g. "Copy" or "Sub Menu > Item").
     */
    public String selectContextMenuItem(Map<String, Object> params) throws Exception {
        String uid = getString(params, "uid");
        String pathStr = getString(params, "path");
        Component comp = resolveUid(uid);
        String[] parts = pathStr.split("\\s*>\\s*");

        AtomicReference<JPopupMenu> popupRef = new AtomicReference<>();
        invokeAndWaitQuietly(() -> {
            JPopupMenu popup = findComponentPopupMenu(comp);
            if (popup != null) {
                popup.show(comp, comp.getWidth() / 2, comp.getHeight() / 2);
                popupRef.set(popup);
            } else {
                dispatchPopupTrigger(comp);
            }
            return null;
        });
        // Poll briefly for the popup to appear when triggered via mouse events.
        if (popupRef.get() == null) {
            long deadline = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < deadline) {
                boolean visible = Boolean.TRUE.equals(
                    invokeAndWaitQuietly(() -> findVisiblePopupMenu() != null));
                if (visible) {
                    break;
                }
                Thread.sleep(50);
            }
        }
        List<javax.swing.JMenu> openedSubmenus = new ArrayList<>();
        try {
            return invokeAndWaitQuietly(() -> {
                JPopupMenu popup = popupRef.get() != null ? popupRef.get() : findVisiblePopupMenu();
                if (popup == null) {
                    throw new IllegalStateException("No context menu appeared for: " + uid);
                }
                JMenuItem item = findPopupMenuItem(popup, parts, openedSubmenus);
                if (item == null) {
                    popup.setVisible(false);
                    throw new IllegalArgumentException("Context menu item not found: " + pathStr);
                }
                item.doClick();
                return "Context menu item clicked: " + pathStr;
            });
        } finally {
            invokeAndWaitQuietly(() -> {
                // Hide any submenu popups realized during lookup (Issue #2).
                for (int i = openedSubmenus.size() - 1; i >= 0; i--) {
                    openedSubmenus.get(i).setPopupMenuVisible(false);
                }
                JPopupMenu popup = popupRef.get() != null ? popupRef.get() : findVisiblePopupMenu();
                if (popup != null && popup.isVisible()) {
                    popup.setVisible(false);
                }
                sweepStalePopups(SwingUtilities.getWindowAncestor(comp));
                return null;
            });
        }
    }

    private JPopupMenu findComponentPopupMenu(Component comp) {
        Component current = comp;
        while (current != null) {
            if (current instanceof JComponent jc && jc.getComponentPopupMenu() != null) {
                return jc.getComponentPopupMenu();
            }
            current = current.getParent();
        }
        return null;
    }

    private void dispatchPopupTrigger(Component comp) {
        int x = comp.getWidth() / 2;
        int y = comp.getHeight() / 2;
        long now = System.currentTimeMillis();
        comp.dispatchEvent(new MouseEvent(comp, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, true, MouseEvent.BUTTON3));
        comp.dispatchEvent(new MouseEvent(comp, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, true, MouseEvent.BUTTON3));
        comp.dispatchEvent(new MouseEvent(comp, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 1, false, MouseEvent.BUTTON3));
    }

    private JPopupMenu findVisiblePopupMenu() {
        for (Window w : Window.getWindows()) {
            if (w.isVisible()) {
                JPopupMenu popup = findPopupIn(w);
                if (popup != null) {
                    return popup;
                }
            }
        }
        return null;
    }

    private JPopupMenu findPopupIn(Component comp) {
        if (comp instanceof JPopupMenu popup && popup.isVisible()) {
            return popup;
        }
        if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                JPopupMenu found = findPopupIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JMenuItem findPopupMenuItem(JPopupMenu popup, String[] path, List<javax.swing.JMenu> openedMenus) {
        for (javax.swing.MenuElement element : popup.getSubElements()) {
            if (element.getComponent() instanceof JMenuItem item && path[0].equals(item.getText())) {
                if (path.length == 1) {
                    return item;
                }
                if (item instanceof javax.swing.JMenu subMenu) {
                    String[] rest = new String[path.length - 1];
                    System.arraycopy(path, 1, rest, 0, rest.length);
                    return findMenuItemInMenu(subMenu, rest, openedMenus);
                }
            }
        }
        return null;
    }

    /**
     * Lists currently open dialogs with their index, type, title, message, and buttons.
     */
    public List<Map<String, Object>> listDialogs() {
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Window w : getVisibleWindows()) {
            if (w instanceof Dialog dialog) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("index", index);
                info.put("title", dialog.getTitle());
                info.put("class", dialog.getClass().getSimpleName());
                info.put("modal", dialog.isModal());
                JOptionPane pane = findDescendant(dialog, JOptionPane.class);
                JFileChooser chooser = findDescendant(dialog, JFileChooser.class);
                if (pane != null) {
                    info.put("type", "option");
                    Object message = pane.getMessage();
                    info.put("message", message == null ? null : String.valueOf(message));
                } else if (chooser != null) {
                    info.put("type", "fileChooser");
                } else {
                    info.put("type", "custom");
                }
                info.put("buttons", collectButtonTexts(dialog));
                result.add(info);
            }
            index++;
        }
        return result;
    }

    /**
     * Responds to an open dialog.
     * Params: button (text of the button to click), filePath (for JFileChooser),
     * windowIndex (optional index from list_dialogs / list_windows).
     */
    public String handleDialog(Map<String, Object> params) {
        Dialog dialog = resolveDialog(params);
        String filePath = (String) params.get("filePath");
        String button = (String) params.get("button");

        JFileChooser chooser = findDescendant(dialog, JFileChooser.class);
        if (filePath != null && !filePath.isBlank()) {
            if (chooser == null) {
                throw new IllegalArgumentException("Dialog does not contain a JFileChooser");
            }
            chooser.setSelectedFile(new java.io.File(filePath));
            chooser.approveSelection();
            return "File selected: " + filePath;
        }
        if (button == null || button.isBlank()) {
            throw new IllegalArgumentException("Provide either 'button' or 'filePath'");
        }
        if (chooser != null && "Cancel".equalsIgnoreCase(button)) {
            chooser.cancelSelection();
            return "File chooser cancelled";
        }
        AbstractButton target = findButtonByText(dialog, button);
        if (target == null) {
            throw new IllegalArgumentException("Button not found in dialog: " + button
                + ". Available: " + collectButtonTexts(dialog));
        }
        target.doClick();
        return "Dialog button clicked: " + button;
    }

    private Dialog resolveDialog(Map<String, Object> params) {
        List<Window> visible = getVisibleWindows();
        Integer index = params.containsKey("windowIndex") ? getInt(params, "windowIndex", 0) : null;
        if (index != null) {
            if (index < 0 || index >= visible.size() || !(visible.get(index) instanceof Dialog d)) {
                throw new IllegalArgumentException("No dialog at window index " + index);
            }
            return d;
        }
        for (Window w : visible) {
            if (w instanceof Dialog d) {
                return d;
            }
        }
        throw new IllegalStateException("No open dialog found");
    }

    private <T extends Component> T findDescendant(Component comp, Class<T> type) {
        if (type.isInstance(comp)) {
            return type.cast(comp);
        }
        if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                T found = findDescendant(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private List<String> collectButtonTexts(Component comp) {
        List<String> texts = new ArrayList<>();
        collectButtons(comp, texts);
        return texts;
    }

    private void collectButtons(Component comp, List<String> texts) {
        if (comp instanceof JButton btn && btn.getText() != null && !btn.getText().isBlank()) {
            texts.add(btn.getText());
        }
        if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                collectButtons(child, texts);
            }
        }
    }

    private AbstractButton findButtonByText(Component comp, String text) {
        if (comp instanceof AbstractButton btn && text.equals(btn.getText())) {
            return btn;
        }
        if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                AbstractButton found = findButtonByText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Moves the active (or specified) window to the given screen position.
     */
    public String moveWindow(Map<String, Object> params) {
        Window win = getTargetWindow(params);
        if (win == null) {
            throw new IllegalStateException("No active window");
        }
        int x = getInt(params, "x", win.getX());
        int y = getInt(params, "y", win.getY());
        win.setLocation(x, y);
        return "Window moved to (" + x + "," + y + ")";
    }

    /**
     * Changes the extended state of the active frame (MAXIMIZED, MINIMIZED, NORMAL).
     */
    public String setWindowState(Map<String, Object> params) {
        Window win = getTargetWindow(params);
        if (win == null) {
            throw new IllegalStateException("No active window");
        }
        if (!(win instanceof Frame frame)) {
            throw new IllegalArgumentException("Active window is not a Frame: " + win.getClass().getSimpleName());
        }
        WindowState state = WindowState.valueOf(getString(params, "state").toUpperCase());
        int extended = switch (state) {
            case MAXIMIZED -> Frame.MAXIMIZED_BOTH;
            case MINIMIZED -> Frame.ICONIFIED;
            case NORMAL -> Frame.NORMAL;
        };
        frame.setExtendedState(extended);
        return "Window state set to " + state;
    }

    /**
     * Reads the system clipboard of the target JVM as text.
     */
    public Map<String, Object> getClipboard() throws Exception {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Map<String, Object> result = new LinkedHashMap<>();
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            result.put("text", clipboard.getData(DataFlavor.stringFlavor));
        } else {
            result.put("text", null);
        }
        return result;
    }

    /**
     * Writes text to the system clipboard of the target JVM.
     */
    public String setClipboard(Map<String, Object> params) {
        String text = getString(params, "text");
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
        return "Clipboard set (" + text.length() + " characters)";
    }

    @FunctionalInterface
    private interface EdtAction<T> {
        T run() throws Exception;
    }

    private <T> T invokeAndWaitQuietly(EdtAction<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.run();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.run());
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private List<ComponentDescriptor> scanComponent(Component comp, ComponentStateFilter filter,
                                                    ScanBudget budget, int depth) {
        List<ComponentDescriptor> result = new ArrayList<>();
        List<ComponentDescriptor> children = new ArrayList<>();
        if (comp instanceof Container container && depth < budget.maxDepth) {
            for (Component child : container.getComponents()) {
                if (budget.remaining <= 0) {
                    budget.truncated = true;
                    break;
                }
                children.addAll(scanComponent(child, filter, budget, depth + 1));
            }
        }
        if (!matchesFilter(comp, filter) && children.isEmpty()) {
            return result;
        }
        if (!budget.take()) {
            // Node budget exhausted: keep any children already collected, drop this node's own descriptor.
            return children.isEmpty() ? result : children;
        }
        String uid = assignUid(comp);
        String text = extractText(comp);
        String selectionState = extractSelectionState(comp);
        Rectangle bounds = comp.getBounds();
        ComponentDescriptor desc = new ComponentDescriptor(
            uid,
            comp.getClass().getSimpleName(),
            comp.getName(),
            text,
            comp.isEnabled(),
            comp.isVisible(),
            comp.isFocusable(),
            bounds.x, bounds.y, bounds.width, bounds.height,
            selectionState,
            children.isEmpty() ? null : children
        );
        result.add(desc);
        return result;
    }

    private boolean matchesFilter(Component comp, ComponentStateFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case VISIBLE_ONLY -> isEffectivelyVisible(comp);
            case ENABLED_ONLY -> comp.isEnabled();
            case FOCUSABLE_ONLY -> comp.isFocusable();
        };
    }

    /**
     * Visibility for snapshot filtering. A {@link JPopupMenu} conventionally
     * reports {@code isVisible()==true} only while showing, but a popup that was
     * cancelled without being properly hidden can lie; use {@code isShowing()}
     * for popups so stale, non-painted popups are excluded (Issue #2).
     */
    private boolean isEffectivelyVisible(Component comp) {
        if (comp instanceof JPopupMenu popup) {
            return popup.isShowing();
        }
        return comp.isVisible();
    }

    private String assignUid(Component comp) {
        return componentToUid.computeIfAbsent(comp, c -> {
            String uid = "comp-" + uidCounter.incrementAndGet();
            uidToComponent.put(uid, c);
            return uid;
        });
    }

    private Component resolveUid(String uid) {
        Component comp = uidToComponent.get(uid);
        if (comp == null) {
            throw new IllegalArgumentException("Unknown component UID: " + uid + ". Please take a fresh snapshot.");
        }
        return comp;
    }

    private String extractText(Component comp) {
        if (comp instanceof javax.swing.JLabel lbl) {
            return lbl.getText();
        }
        if (comp instanceof AbstractButton btn) {
            return btn.getText();
        }
        if (comp instanceof JTextComponent tc) {
            return tc.getText();
        }
        if (comp instanceof JSpinner sp) {
            return String.valueOf(sp.getValue());
        }
        return null;
    }

    private String extractSelectionState(Component comp) {
        if (comp instanceof JComboBox<?> combo) {
            return String.valueOf(combo.getSelectedIndex());
        }
        if (comp instanceof JList<?> list) {
            return String.valueOf(list.getSelectedIndex());
        }
        if (comp instanceof JTabbedPane tabs) {
            return String.valueOf(tabs.getSelectedIndex());
        }
        if (comp instanceof JTable table) {
            return table.getSelectedRow() + "," + table.getSelectedColumn();
        }
        return null;
    }

    private Map<String, Object> describeComponent(Component comp, boolean detailed) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("uid", assignUid(comp));
        info.put("class", comp.getClass().getName());
        info.put("simpleName", comp.getClass().getSimpleName());
        info.put("name", comp.getName());
        info.put("text", extractText(comp));
        info.put("enabled", comp.isEnabled());
        info.put("visible", comp.isVisible());
        info.put("focusable", comp.isFocusable());
        Rectangle b = comp.getBounds();
        info.put("bounds", Map.of("x", b.x, "y", b.y, "width", b.width, "height", b.height));
        if (detailed) {
            info.put("selectionState", extractSelectionState(comp));
            javax.accessibility.AccessibleContext ac = comp.getAccessibleContext();
            if (ac != null) {
                info.put("accessibleName", ac.getAccessibleName());
                info.put("accessibleDescription", ac.getAccessibleDescription());
                if (ac.getAccessibleRole() != null) {
                    info.put("accessibleRole", ac.getAccessibleRole().toDisplayString());
                }
            }
        }
        return info;
    }

    /**
     * Resolves the window a command targets. An explicit {@code windowIndex}
     * (as returned by {@code list_windows}) always wins; otherwise the active
     * window, then the first visible window. Previously the index was silently
     * ignored, so e.g. {@code close_window(windowIndex=1)} could close the
     * main frame — a destructive mistake against the automated application.
     */
    private Window getTargetWindow(Map<String, Object> params) {
        if (params != null && params.get("windowIndex") != null) {
            int idx = getInt(params, "windowIndex", 0);
            List<Window> visible = getVisibleWindows();
            if (idx < 0 || idx >= visible.size()) {
                throw new IllegalArgumentException("windowIndex " + idx + " is out of range; "
                    + visible.size() + " visible window(s). Use list_windows for valid indices.");
            }
            return visible.get(idx);
        }
        if (activeWindow != null && activeWindow.isVisible()) {
            return activeWindow;
        }
        List<Window> visible = getVisibleWindows();
        return visible.isEmpty() ? null : visible.get(0);
    }

    private List<Window> getVisibleWindows() {
        List<Window> result = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isVisible()) {
                result.add(w);
            }
        }
        return result;
    }

    private String getWindowTitle(Window w) {
        if (w instanceof java.awt.Frame f) {
            return f.getTitle();
        }
        if (w instanceof java.awt.Dialog d) {
            return d.getTitle();
        }
        return w.getClass().getSimpleName();
    }

    private JScrollPane findScrollPane(Component comp) {
        if (comp instanceof JScrollPane sp) {
            return sp;
        }
        Component parent = comp.getParent();
        while (parent != null) {
            if (parent instanceof JScrollPane sp) {
                return sp;
            }
            parent = parent.getParent();
        }
        if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                JScrollPane sp = findScrollPane(child);
                if (sp != null) {
                    return sp;
                }
            }
        }
        return null;
    }

    private JMenuItem findMenuItem(Component comp, String[] path, List<javax.swing.JMenu> openedMenus) {
        if (path.length == 0) {
            return null;
        }
        if (comp instanceof javax.swing.JMenuBar menuBar) {
            for (int i = 0; i < menuBar.getMenuCount(); i++) {
                javax.swing.JMenu menu = menuBar.getMenu(i);
                if (menu != null && menu.getText().equals(path[0])) {
                    if (path.length == 1 && menu instanceof JMenuItem mi) {
                        return mi;
                    }
                    String[] rest = new String[path.length - 1];
                    System.arraycopy(path, 1, rest, 0, rest.length);
                    JMenuItem found = findMenuItemInMenu(menu, rest, openedMenus);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                JMenuItem found = findMenuItem(child, path, openedMenus);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JMenuItem findMenuItemInMenu(javax.swing.JMenu menu, String[] path, List<javax.swing.JMenu> openedMenus) {
        menu.setPopupMenuVisible(true);
        openedMenus.add(menu);
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item == null) {
                continue;
            }
            if (item.getText().equals(path[0])) {
                if (path.length == 1) {
                    return item;
                }
                if (item instanceof javax.swing.JMenu subMenu) {
                    String[] rest = new String[path.length - 1];
                    System.arraycopy(path, 1, rest, 0, rest.length);
                    return findMenuItemInMenu(subMenu, rest, openedMenus);
                }
            }
        }
        return null;
    }

    /**
     * Hides the popups realized while locating a menu item and sweeps the
     * window's layered pane for any leftover non-showing popup wrappers, so no
     * stale {@code JPopupMenu} lingers in the component tree (Issue #2).
     */
    private void hideMenus(List<javax.swing.JMenu> openedMenus, Window win) {
        // Hide in reverse order (deepest submenu first).
        for (int i = openedMenus.size() - 1; i >= 0; i--) {
            javax.swing.JMenu menu = openedMenus.get(i);
            menu.setPopupMenuVisible(false);
            JPopupMenu popup = menu.getPopupMenu();
            if (popup != null) {
                popup.setVisible(false);
            }
        }
        sweepStalePopups(win);
    }

    /**
     * Defensively removes wrapper containers holding a non-showing
     * {@link JPopupMenu} from the window's {@link javax.swing.JLayeredPane}.
     */
    private void sweepStalePopups(Window win) {
        if (!(win instanceof javax.swing.RootPaneContainer rpc)) {
            return;
        }
        javax.swing.JLayeredPane layeredPane = rpc.getLayeredPane();
        if (layeredPane == null) {
            return;
        }
        boolean removedAny = false;
        for (Component child : layeredPane.getComponents()) {
            JPopupMenu popup = child instanceof JPopupMenu p ? p : findDescendant(child, JPopupMenu.class);
            if (popup != null && !popup.isShowing()) {
                layeredPane.remove(child);
                removedAny = true;
            }
        }
        if (removedAny) {
            layeredPane.revalidate();
            layeredPane.repaint();
        }
    }

    private TreePath findTreePath(JTree tree, String[] parts) {
        Object root = tree.getModel().getRoot();
        if (!String.valueOf(root).equals(parts[0])) {
            return null;
        }
        List<Object> pathNodes = new ArrayList<>();
        pathNodes.add(root);
        Object current = root;
        for (int i = 1; i < parts.length; i++) {
            int childCount = tree.getModel().getChildCount(current);
            Object found = null;
            for (int j = 0; j < childCount; j++) {
                Object child = tree.getModel().getChild(current, j);
                if (String.valueOf(child).equals(parts[i])) {
                    found = child;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            pathNodes.add(found);
            current = found;
        }
        return new TreePath(pathNodes.toArray());
    }

    private boolean checkCondition(String condType, Map<String, Object> params) {
        return switch (condType.toUpperCase()) {
            case "WINDOW_TITLE" -> {
                String expected = getString(params, "expectedValue");
                yield getVisibleWindows().stream().anyMatch(w -> getWindowTitle(w).contains(expected));
            }
            case "COMPONENT_TEXT" -> {
                String uid = getString(params, "uid");
                String expected = getString(params, "expectedValue");
                try {
                    Component comp = resolveUid(uid);
                    String text = extractText(comp);
                    yield text != null && text.contains(expected);
                } catch (Exception e) {
                    yield false;
                }
            }
            case "COMPONENT_VISIBLE" -> {
                String uid = getString(params, "uid");
                try {
                    Component comp = resolveUid(uid);
                    yield comp.isVisible();
                } catch (Exception e) {
                    yield false;
                }
            }
            case "COMPONENT_ENABLED" -> {
                String uid = getString(params, "uid");
                try {
                    Component comp = resolveUid(uid);
                    yield comp.isEnabled();
                } catch (Exception e) {
                    yield false;
                }
            }
            case "COMPONENT_EXISTS" -> {
                String expected = getString(params, "expectedValue");
                yield componentMatchingQueryExists(expected);
            }
            case "COMPONENT_GONE" -> {
                String expected = getString(params, "expectedValue");
                yield !componentMatchingQueryExists(expected);
            }
            case "WINDOW_COUNT" -> {
                int expected = Integer.parseInt(getString(params, "expectedValue"));
                yield getVisibleWindows().size() == expected;
            }
            case "EDT_IDLE" -> {
                try {
                    SwingUtilities.invokeAndWait(() -> { });
                    yield true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    yield false;
                } catch (Exception e) {
                    yield false;
                }
            }
            default -> throw new IllegalArgumentException("Unknown condition type: " + condType);
        };
    }

    private boolean componentMatchingQueryExists(String query) {
        String lowered = query.toLowerCase();
        for (Window w : getVisibleWindows()) {
            List<Map<String, Object>> matches = new ArrayList<>();
            collectMatches(w, lowered, "ANY", matches);
            if (!matches.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int getMouseMask(String button) {
        return switch (button.toUpperCase()) {
            case "RIGHT" -> InputEvent.BUTTON3_DOWN_MASK;
            case "MIDDLE" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
    }

    private List<Integer> parseKeyChord(String keys) {
        List<Integer> codes = new ArrayList<>();
        for (String part : keys.split("\\+")) {
            codes.add(switch (part.trim().toUpperCase()) {
                case "CTRL" -> KeyEvent.VK_CONTROL;
                case "ALT" -> KeyEvent.VK_ALT;
                case "SHIFT" -> KeyEvent.VK_SHIFT;
                case "META" -> KeyEvent.VK_META;
                case "ENTER" -> KeyEvent.VK_ENTER;
                case "ESCAPE", "ESC" -> KeyEvent.VK_ESCAPE;
                case "TAB" -> KeyEvent.VK_TAB;
                case "SPACE" -> KeyEvent.VK_SPACE;
                case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE;
                case "DELETE", "DEL" -> KeyEvent.VK_DELETE;
                case "UP" -> KeyEvent.VK_UP;
                case "DOWN" -> KeyEvent.VK_DOWN;
                case "LEFT" -> KeyEvent.VK_LEFT;
                case "RIGHT" -> KeyEvent.VK_RIGHT;
                default -> {
                    String t = part.trim().toUpperCase();
                    int code = KeyEvent.getExtendedKeyCodeForChar(t.charAt(0));
                    yield code != KeyEvent.VK_UNDEFINED ? code : (int) t.charAt(0);
                }
            });
        }
        return codes;
    }

    private Object parseSpinnerValue(JSpinner spinner, String text) {
        javax.swing.SpinnerModel model = spinner.getModel();
        if (model instanceof javax.swing.SpinnerNumberModel) {
            return Double.parseDouble(text);
        }
        return text;
    }

    private String getString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return val.toString();
    }

    private String getString(Map<String, Object> params, String key, String defaultVal) {
        Object val = params.get(key);
        return val == null ? defaultVal : val.toString();
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object val = params.get(key);
        if (val == null) {
            return defaultVal;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(val.toString());
    }

    private long getLong(Map<String, Object> params, String key, long defaultVal) {
        Object val = params.get(key);
        if (val == null) {
            return defaultVal;
        }
        if (val instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(val.toString());
    }
}
