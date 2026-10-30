package solutions.crosstech.swingmcp.agent;

import solutions.crosstech.swingmcp.common.dto.ComponentDescriptor;
import solutions.crosstech.swingmcp.common.dto.SnapshotNode;
import solutions.crosstech.swingmcp.common.enums.ComponentStateFilter;
import solutions.crosstech.swingmcp.common.enums.ScrollDirection;
import solutions.crosstech.swingmcp.common.enums.WindowState;
import solutions.crosstech.swingmcp.common.spi.UiToolkit;
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
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
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
import javax.swing.ListCellRenderer;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;

/**
 * Scans Swing component trees and performs interactions on behalf of the agent.
 * Maintains a UID registry mapping string UIDs to {@link Component} instances
 * via weak references in both directions, so the registry never keeps a
 * disposed window alive; the uid of a collected component is dropped and
 * reported as unknown.
 */
public class ComponentScanner {

    /**
     * Bounds every wait for the EDT made from a command thread, so a wedged EDT
     * fails the command instead of hanging it forever.
     */
    static final long EDT_TIMEOUT_MS = 20_000;

    private final AtomicInteger uidCounter = new AtomicInteger(0);
    /**
     * Guards the uid registry. Commands run concurrently, some of them off the
     * EDT, and {@link WeakHashMap} is not thread-safe.
     */
    private final Object uidLock = new Object();
    private final Map<String, UidRef> uidToComponent = new HashMap<>();
    private final Map<Component, String> componentToUid = new WeakHashMap<>();
    private final ReferenceQueue<Component> collectedUids = new ReferenceQueue<>();
    /** Weak, so a selected window that is later disposed can still be collected. */
    private volatile WeakReference<Window> activeWindow = new WeakReference<>(null);
    /** Component the last event-based hover entered; EDT only. Weak, so it cannot pin a closed window. */
    private java.lang.ref.WeakReference<Component> lastHovered = new java.lang.ref.WeakReference<>(null);

    /** Default cap on the number of component nodes returned by a single snapshot. */
    private static final int DEFAULT_MAX_NODES = 2000;

    /**
     * Takes a snapshot of the current active window's component tree.
     *
     * @param params optional params: windowIndex, filter, maxNodes, maxDepth, and
     *               uid to snapshot only the subtree rooted at that component
     * @return a {@link SnapshotNode} with the component tree
     */
    public SnapshotNode takeSnapshot(Map<String, Object> params) {
        String uid = (String) params.get("uid");
        Component root = uid == null || uid.isBlank() ? null : resolveUid(uid);
        Window win = root == null ? getTargetWindow(params)
            : root instanceof Window w ? w : SwingUtilities.getWindowAncestor(root);
        if (root == null) {
            root = win;
        }
        if (root == null) {
            return new SnapshotNode("No window", "null", 0, 0, 0, 0, List.of());
        }
        ComponentStateFilter filter = ComponentStateFilter.valueOf(
            getString(params, "filter", "ALL").toUpperCase());
        int maxNodes = params.containsKey("maxNodes") ? getInt(params, "maxNodes", DEFAULT_MAX_NODES) : DEFAULT_MAX_NODES;
        int maxDepth = params.containsKey("maxDepth") ? getInt(params, "maxDepth", Integer.MAX_VALUE) : Integer.MAX_VALUE;
        ScanBudget budget = new ScanBudget(Math.max(1, maxNodes), maxDepth);
        List<ComponentDescriptor> children = scanComponent(root, filter, budget, 0);
        if (win == null) {
            // A detached component: no window to report, only the subtree.
            return new SnapshotNode("No window", "null", 0, 0, 0, 0, children,
                budget.truncated ? Boolean.TRUE : null);
        }
        Rectangle bounds = win.getBounds();
        return new SnapshotNode(
            getWindowTitle(win),
            ClassNames.displayName(win.getClass()),
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
                info.put("class", ClassNames.displayName(w.getClass()));
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
        Window selected = visible.get(index);
        activeWindow = new WeakReference<>(selected);
        selected.toFront();
        selected.requestFocus();
        return "Window selected: " + getWindowTitle(selected);
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
     *
     * <p>Called off the EDT. Pending events are processed first, so the image
     * reflects the last action rather than lagging one repaint behind. Mode
     * {@code PAINT} (default) renders the component into an image on the EDT:
     * nothing covering it ends up in the picture and it works for windows in the
     * background. Mode {@code SCREEN} copies the screen region with {@code Robot},
     * for when native rendering, window decorations or heavyweight popups matter.</p>
     */
    public Map<String, Object> takeScreenshot(Map<String, Object> params) throws Exception {
        String mode = getString(params, "mode", "PAINT").toUpperCase();
        if (!mode.equals("PAINT") && !mode.equals("SCREEN")) {
            throw new IllegalArgumentException("Unknown screenshot mode: " + mode + ". Use PAINT or SCREEN.");
        }
        if (mode.equals("SCREEN") && GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot capture the screen in a headless environment; use mode PAINT");
        }
        awaitIdleEventQueue();
        BufferedImage img = invokeAndWaitQuietly(() -> {
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
            if (mode.equals("PAINT")) {
                return paintToImage(target);
            }
            if (!target.isShowing()) {
                throw new IllegalStateException("Component is not on screen; use mode PAINT to render it anyway");
            }
            // Robot.createScreenCapture only reads pixels; it does not wait for the EDT.
            return new Robot().createScreenCapture(new Rectangle(target.getLocationOnScreen(), target.getSize()));
        });
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageBase64", base64);
        result.put("mimeType", "image/png");
        result.put("width", img.getWidth());
        result.put("height", img.getHeight());
        result.put("mode", mode);
        return result;
    }

    /**
     * Renders a component, or a window's content, into an image. Must run on the EDT.
     * A Swing window is rendered through its root pane (menu bar, content, and
     * lightweight popups), without the native title bar.
     */
    private BufferedImage paintToImage(Component target) {
        Component painted = target instanceof javax.swing.RootPaneContainer rpc && rpc.getRootPane() != null
            ? rpc.getRootPane() : target;
        int width = painted.getWidth();
        int height = painted.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Component has no size (" + width + "x" + height
                + "); it is probably not laid out or not displayed");
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            // Non-opaque components show their ancestors' background on screen.
            g.setColor(backgroundOf(painted));
            g.fillRect(0, 0, width, height);
            if (painted.isShowing()) {
                // paintAll also renders heavyweight children (e.g. an AWT Canvas).
                painted.paintAll(g);
            } else {
                painted.paint(g);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static java.awt.Color backgroundOf(Component comp) {
        for (Component c = comp; c != null; c = c.getParent()) {
            if (c.isOpaque() && c.getBackground() != null) {
                return c.getBackground();
            }
        }
        return comp.getBackground() != null ? comp.getBackground() : java.awt.Color.WHITE;
    }

    /**
     * Lets the EDT process what is already queued (listeners, revalidation,
     * repaints posted by the last action) before an image is taken. Bounded, so a
     * busy application cannot stall the screenshot.
     */
    private void awaitIdleEventQueue() throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return;
        }
        java.awt.EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        long deadline = System.currentTimeMillis() + 1000;
        do {
            try {
                invokeAndWaitQuietly(() -> null, deadline - System.currentTimeMillis());
            } catch (EdtTimeoutException e) {
                // Still busy: take the image anyway rather than stall the screenshot.
                return;
            }
        } while (queue.peekEvent() != null && System.currentTimeMillis() < deadline);
    }

    /**
     * Clicks a component identified by UID. Runs on the EDT.
     *
     * <p>A plain left click on a button is a {@code doClick()}. Anything else is
     * a mouse click at {@code x}/{@code y} (relative to the component's top-left
     * corner, default its center) with optional {@code modifiers}, delivered as
     * mouse events to the component under that point, so it works while the
     * window is covered or in the background. {@code robot=true} moves the real
     * mouse instead, for code that needs OS-level input.</p>
     */
    public String click(Map<String, Object> params) throws AWTException {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        String buttonStr = getString(params, "button", "LEFT").toUpperCase();
        int clicks = "DOUBLE".equalsIgnoreCase(getString(params, "clickType", "SINGLE")) ? 2 : 1;
        int modifiers = parseModifiers(getString(params, "modifiers", ""));
        boolean robot = getBoolean(params, "robot", false);
        boolean atPoint = params.get("x") != null || params.get("y") != null;

        if (comp instanceof AbstractButton btn && "LEFT".equals(buttonStr) && modifiers == 0 && !robot && !atPoint) {
            btn.doClick();
            return "Clicked button: " + btn.getText();
        }
        Point p = pointIn(comp, params, "x", "y");
        if (robot) {
            robotClick(comp, p, getMouseMask(buttonStr), clicks, modifiers);
        } else {
            Component target = dispatchClick(comp, p, mouseButton(buttonStr), clicks, modifiers);
            if (target != comp) {
                return "Clicked " + simpleNameOf(target) + " inside " + uid + " at (" + p.x + "," + p.y + ")";
            }
        }
        return "Clicked component: " + uid + " at (" + p.x + "," + p.y + ")";
    }

    private static String simpleNameOf(Component c) {
        String name = c.getClass().getSimpleName();
        return name.isEmpty() ? c.getClass().getName() : name;
    }

    /**
     * The point a pointer command targets, relative to {@code comp}: the given
     * coordinates, or the center for any that are missing. Must lie inside it.
     */
    private Point pointIn(Component comp, Map<String, Object> params, String xKey, String yKey) {
        int x = getInt(params, xKey, comp.getWidth() / 2);
        int y = getInt(params, yKey, comp.getHeight() / 2);
        if (x < 0 || y < 0 || x >= comp.getWidth() || y >= comp.getHeight()) {
            throw new IllegalArgumentException("Point (" + x + "," + y + ") is outside the component, which is "
                + comp.getWidth() + "x" + comp.getHeight() + "; " + xKey + "/" + yKey
                + " are relative to its top-left corner");
        }
        return new Point(x, y);
    }

    /**
     * Delivers a click as mouse events to the deepest component at {@code p}
     * (relative to {@code comp}), like the real mouse would. Must run on the EDT.
     *
     * @return the component that received the events
     */
    private Component dispatchClick(Component comp, Point p, int button, int clicks, int modifiers) {
        Component target = deepestAt(comp, p);
        Point tp = SwingUtilities.convertPoint(comp, p, target);
        int buttonMask = InputEvent.getMaskForButton(button);
        boolean rightButton = button == MouseEvent.BUTTON3;
        // Windows reports the popup trigger on release, other platforms on press.
        boolean triggerOnRelease = System.getProperty("os.name", "").startsWith("Windows");
        long now = System.currentTimeMillis();
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_MOVED, now, modifiers,
            tp.x, tp.y, 0, false, MouseEvent.NOBUTTON));
        for (int count = 1; count <= clicks; count++) {
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now, modifiers | buttonMask,
                tp.x, tp.y, count, rightButton && !triggerOnRelease, button));
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now, modifiers,
                tp.x, tp.y, count, rightButton && triggerOnRelease, button));
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now, modifiers,
                tp.x, tp.y, count, false, button));
        }
        return target;
    }

    private static Component deepestAt(Component comp, Point p) {
        Component deepest = SwingUtilities.getDeepestComponentAt(comp, p.x, p.y);
        return deepest != null ? deepest : comp;
    }

    private void robotClick(Component comp, Point p, int buttonMask, int clicks, int modifiers) throws AWTException {
        requireShowing(comp);
        Robot robot = new Robot();
        Point loc = comp.getLocationOnScreen();
        List<Integer> keys = modifierKeys(modifiers);
        keys.forEach(robot::keyPress);
        try {
            robot.mouseMove(loc.x + p.x, loc.y + p.y);
            for (int i = 0; i < clicks; i++) {
                robot.mousePress(buttonMask);
                robot.mouseRelease(buttonMask);
            }
        } finally {
            for (int i = keys.size() - 1; i >= 0; i--) {
                robot.keyRelease(keys.get(i));
            }
        }
    }

    private static void requireShowing(Component comp) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("robot input needs a display");
        }
        if (!comp.isShowing()) {
            throw new IllegalStateException("Component is not on screen, so the real mouse cannot reach it; "
                + "omit robot to deliver the events directly");
        }
    }

    /**
     * Parses modifier names such as {@code "CTRL+SHIFT"} into an extended
     * modifier mask. {@code SHORTCUT} is the platform's menu shortcut key
     * (Ctrl, or Cmd on macOS).
     */
    static int parseModifiers(String modifiers) {
        int mask = 0;
        for (String part : modifiers.toUpperCase().split("[+,\\s]+")) {
            mask |= switch (part) {
                case "" -> 0;
                case "CTRL", "CONTROL" -> InputEvent.CTRL_DOWN_MASK;
                case "SHIFT" -> InputEvent.SHIFT_DOWN_MASK;
                case "ALT", "OPTION" -> InputEvent.ALT_DOWN_MASK;
                case "META", "CMD", "COMMAND" -> InputEvent.META_DOWN_MASK;
                case "ALT_GRAPH", "ALTGR" -> InputEvent.ALT_GRAPH_DOWN_MASK;
                case "SHORTCUT" -> GraphicsEnvironment.isHeadless()
                    ? InputEvent.CTRL_DOWN_MASK : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
                default -> throw new IllegalArgumentException("Unknown modifier: " + part
                    + ". Use CTRL, SHIFT, ALT, META (Cmd), or SHORTCUT, combined with '+'");
            };
        }
        return mask;
    }

    private static List<Integer> modifierKeys(int modifiers) {
        List<Integer> keys = new ArrayList<>();
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            keys.add(KeyEvent.VK_CONTROL);
        }
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            keys.add(KeyEvent.VK_SHIFT);
        }
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            keys.add(KeyEvent.VK_ALT);
        }
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) {
            keys.add(KeyEvent.VK_META);
        }
        if ((modifiers & InputEvent.ALT_GRAPH_DOWN_MASK) != 0) {
            keys.add(KeyEvent.VK_ALT_GRAPH);
        }
        return keys;
    }

    private static int mouseButton(String button) {
        return switch (button.toUpperCase()) {
            case "RIGHT" -> MouseEvent.BUTTON3;
            case "MIDDLE" -> MouseEvent.BUTTON2;
            default -> MouseEvent.BUTTON1;
        };
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
            throw new IllegalArgumentException("Component " + uid + " does not support text input: " + ClassNames.displayName(comp.getClass()));
        }
        return "Filled: " + uid;
    }

    /**
     * Selects an option in a JList, JComboBox, or JTabbedPane by index or text.
     * Items match by {@code String.valueOf(item)} or by the text their renderer
     * shows, so models of enums or domain objects work; the model's own element
     * is then selected. No match, or an index out of range, is an error rather
     * than a silent no-op.
     */
    public String selectOption(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        String text = (String) params.get("text");
        Integer index = params.containsKey("index") ? getInt(params, "index", 0) : null;
        if (index == null && text == null) {
            throw new IllegalArgumentException("Provide text or index");
        }

        if (comp instanceof JComboBox<?> combo) {
            if (index != null) {
                requireOptionIndex("item", index, combo.getItemCount(), uid);
                combo.setSelectedIndex(index);
            } else {
                int match = matchOption(combo.getModel(), combo.getRenderer(), new JList<>(), text);
                if (match < 0) {
                    throw new IllegalArgumentException("No combo box item matches \"" + text + "\" on " + uid
                        + ". Available items: " + optionTexts(combo.getModel(), combo.getRenderer(), new JList<>())
                        + (combo.isEditable() ? ". Use fill to enter free text into this editable combo box." : ""));
                }
                combo.setSelectedIndex(match);
            }
        } else if (comp instanceof JList<?> list) {
            if (index != null) {
                requireOptionIndex("item", index, list.getModel().getSize(), uid);
                list.setSelectedIndex(index);
            } else {
                int match = matchOption(list.getModel(), list.getCellRenderer(), list, text);
                if (match < 0) {
                    throw new IllegalArgumentException("No list item matches \"" + text
                        + "\" on " + uid + ". Available items: "
                        + optionTexts(list.getModel(), list.getCellRenderer(), list));
                }
                list.setSelectedIndex(match);
            }
        } else if (comp instanceof JTabbedPane tabs) {
            if (index != null) {
                requireOptionIndex("tab", index, tabs.getTabCount(), uid);
                tabs.setSelectedIndex(index);
            } else {
                int match = -1;
                List<String> available = new ArrayList<>();
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    // Icon-only tabs have no title.
                    available.add(tabs.getTitleAt(i));
                    if (match < 0 && Objects.equals(tabs.getTitleAt(i), text)) {
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
            throw new IllegalArgumentException("Component " + uid + " does not support option selection: " + ClassNames.displayName(comp.getClass()));
        }
        return "Option selected on: " + uid;
    }

    private static void requireOptionIndex(String what, int index, int count, String uid) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException(what + " index " + index + " is out of range on " + uid
                + "; it has " + count + " " + what + "(s)");
        }
    }

    /**
     * The first element whose {@code String.valueOf} or rendered text equals
     * {@code text}, or -1. Exact string matches win over rendered ones.
     */
    private static int matchOption(javax.swing.ListModel<?> model, ListCellRenderer<?> renderer,
                                   JList<?> list, String text) {
        for (int i = 0; i < model.getSize(); i++) {
            if (String.valueOf(model.getElementAt(i)).equals(text)) {
                return i;
            }
        }
        for (int i = 0; i < model.getSize(); i++) {
            if (Objects.equals(renderedText(renderer, list, model.getElementAt(i), i), text)) {
                return i;
            }
        }
        return -1;
    }

    /** How each option reads for an error message: its rendered text, else its string value. */
    private static List<String> optionTexts(javax.swing.ListModel<?> model, ListCellRenderer<?> renderer, JList<?> list) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < model.getSize(); i++) {
            String rendered = renderedText(renderer, list, model.getElementAt(i), i);
            texts.add(rendered != null ? rendered : String.valueOf(model.getElementAt(i)));
        }
        return texts;
    }

    /** The text a label-based cell renderer shows for {@code value}, or null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String renderedText(ListCellRenderer renderer, JList list, Object value, int index) {
        if (renderer == null) {
            return null;
        }
        try {
            Component c = renderer.getListCellRendererComponent(list, value, index, false, false);
            return c instanceof JLabel label ? label.getText() : null;
        } catch (RuntimeException e) {
            // A custom renderer that cannot cope with this list: fall back to the string value.
            return null;
        }
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
     * Selects a table cell by row and column, like a mouse click on it.
     * Params: uid, row, col (default 0), toggle (Ctrl/Cmd-click), extend
     * (Shift-click), or rows (a list of rows that becomes the whole selection).
     */
    public String selectTableCell(Map<String, Object> params) {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        int col = getInt(params, "col", 0);
        boolean toggle = getBoolean(params, "toggle", false);
        boolean extend = getBoolean(params, "extend", false);

        if (!(comp instanceof JTable table)) {
            throw new IllegalArgumentException("Component " + uid + " is not a JTable");
        }
        requireIndex("col", col, table.getColumnCount());
        if (params.get("rows") instanceof List<?> rows) {
            if (toggle || extend) {
                throw new IllegalArgumentException("rows replaces the whole selection; "
                    + "it cannot be combined with toggle or extend");
            }
            table.clearSelection();
            boolean first = true;
            for (Object r : new java.util.LinkedHashSet<>(rows)) {
                int row = r instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(r));
                requireIndex("row", row, table.getRowCount());
                table.changeSelection(row, col, !first, false);
                first = false;
            }
        } else {
            if (params.get("row") == null) {
                throw new IllegalArgumentException("Provide row (optionally with toggle or extend) or rows");
            }
            int row = getInt(params, "row", 0);
            requireIndex("row", row, table.getRowCount());
            table.changeSelection(row, col, toggle, extend);
        }
        String selection = "Table selection: rows " + java.util.Arrays.toString(table.getSelectedRows())
            + ", column " + table.getSelectedColumn();
        if (table.getSelectionModel().getSelectionMode() == javax.swing.ListSelectionModel.SINGLE_SELECTION
            && (toggle || extend || params.get("rows") instanceof List<?> l && l.size() > 1)) {
            selection += " (this table allows only one selected row)";
        }
        return selection;
    }

    private static void requireIndex(String what, int index, int count) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException(what + " " + index + " is out of range; the table has "
                + count + (what.equals("row") ? " row(s)" : " column(s)"));
        }
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
     * Presses a key or chord (e.g. "CTRL+S", "ENTER"). Called off the EDT.
     *
     * <p>By default the key events are dispatched straight to the component that
     * has keyboard focus in the application (or to {@code uid}), not through the
     * operating system: they reach the application while it is in the background
     * and can never end up in another application. {@code robot=true} sends
     * OS-level keystrokes with {@code java.awt.Robot} instead, and only once the
     * target window is in front.</p>
     */
    public String pressKey(Map<String, Object> params) throws Exception {
        String keys = getString(params, "keys");
        List<Key> chord = parseKeyChord(keys);
        if (getBoolean(params, "robot", false)) {
            ensureForeground(invokeAndWaitQuietly(() -> windowOf(keyTarget(params))));
            robotChord(new Robot(), chord.stream().map(Key::code).toList());
            return "Key pressed: " + keys;
        }
        Component target = invokeAndWaitQuietly(() -> keyTarget(params));
        invokeAndWaitQuietly(() -> {
            dispatchChord(target, chord);
            return null;
        });
        return "Key pressed: " + keys + " (sent to " + describeKeyTarget(target) + ")";
    }

    /**
     * Presses the keys of a chord in order and releases them in reverse. Keys
     * already pressed are released even if a later press fails, so a modifier
     * is never left held down system-wide. Package-private for tests.
     */
    static void robotChord(Robot robot, List<Integer> codes) {
        List<Integer> pressed = new ArrayList<>();
        try {
            for (int code : codes) {
                robot.keyPress(code);
                pressed.add(code);
            }
        } finally {
            for (int i = pressed.size() - 1; i >= 0; i--) {
                robot.keyRelease(pressed.get(i));
            }
        }
    }

    /** A key of a chord: its key code and the character it types, if any. */
    private record Key(int code, char ch) {
        boolean isModifier() {
            return modifierMask(code) != 0;
        }
    }

    private static int modifierMask(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK;
            case KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK;
            case KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK;
            case KeyEvent.VK_META -> InputEvent.META_DOWN_MASK;
            case KeyEvent.VK_ALT_GRAPH -> InputEvent.ALT_GRAPH_DOWN_MASK;
            default -> 0;
        };
    }

    /**
     * Dispatches a chord the way the keyboard produces it: modifiers pressed in
     * order, then each key pressed, typed (when it produces a character and no
     * Ctrl/Alt/Meta is held) and released, then the modifiers released. Must
     * run on the EDT.
     */
    private void dispatchChord(Component target, List<Key> chord) {
        int mask = 0;
        long now = System.currentTimeMillis();
        List<Key> modifiers = chord.stream().filter(Key::isModifier).toList();
        for (Key mod : modifiers) {
            mask |= modifierMask(mod.code());
            dispatchKey(target, KeyEvent.KEY_PRESSED, now, mask, mod.code(), KeyEvent.CHAR_UNDEFINED);
        }
        boolean shift = (mask & InputEvent.SHIFT_DOWN_MASK) != 0;
        boolean command = (mask & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
            | InputEvent.META_DOWN_MASK)) != 0;
        for (Key key : chord) {
            if (key.isModifier()) {
                continue;
            }
            char ch = key.ch() == KeyEvent.CHAR_UNDEFINED || !Character.isLetter(key.ch()) ? key.ch()
                : shift ? Character.toUpperCase(key.ch()) : Character.toLowerCase(key.ch());
            dispatchKey(target, KeyEvent.KEY_PRESSED, now, mask, key.code(), ch);
            if (ch != KeyEvent.CHAR_UNDEFINED && !command) {
                dispatchKey(target, KeyEvent.KEY_TYPED, now, mask, KeyEvent.VK_UNDEFINED, ch);
            }
            dispatchKey(target, KeyEvent.KEY_RELEASED, now, mask, key.code(), ch);
        }
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            mask &= ~modifierMask(modifiers.get(i).code());
            dispatchKey(target, KeyEvent.KEY_RELEASED, now, mask, modifiers.get(i).code(), KeyEvent.CHAR_UNDEFINED);
        }
    }

    /**
     * Dispatches one key event directly to {@code target}. Not posted to the event
     * queue, so it is neither retargeted to the (possibly absent) system focus owner
     * nor held back by pending focus changes; the focus manager still applies focus
     * traversal keys, key bindings and accelerators.
     */
    private static void dispatchKey(Component target, int id, long when, int modifiers, int code, char ch) {
        target.dispatchEvent(new KeyEvent(target, id, when, modifiers, code, ch));
    }

    /**
     * The component key events go to: {@code uid} (which is given focus within its
     * window), else the application's focus owner, else the most recent focus owner
     * of the open modal dialog or the target window. Must run on the EDT.
     */
    private Component keyTarget(Map<String, Object> params) {
        String uid = (String) params.get("uid");
        Component target;
        if (uid != null && !uid.isBlank()) {
            target = resolveUid(uid);
            if (!target.hasFocus()) {
                target.requestFocusInWindow();
            }
        } else {
            target = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (target == null) {
                // The application is not active (normal while an agent drives it from an
                // editor): use what would get focus once its window is activated.
                Window win = keyWindow(params);
                target = win.getMostRecentFocusOwner();
                if (target == null) {
                    target = win instanceof javax.swing.RootPaneContainer rpc ? rpc.getRootPane() : win;
                }
            }
        }
        if (!target.isShowing()) {
            throw new IllegalStateException("The component that would receive the keys ("
                + describeKeyTarget(target) + ") is not on screen");
        }
        markFocusedTextComponent(target);
        return target;
    }

    /**
     * Some text actions act on "the focused text component" rather than the event
     * source — JTextField's ENTER action fires only for it. Swing records that
     * component in a focus listener every JTextComponent installs; in a window
     * without keyboard focus no field ever gains focus, so ENTER would do nothing.
     * Notifying just that listener records the key target, as clicking into the
     * field would, without touching real focus or other focus listeners.
     */
    private static void markFocusedTextComponent(Component target) {
        if (!(target instanceof JTextComponent text) || text.hasFocus()) {
            return;
        }
        for (java.awt.event.FocusListener listener : text.getFocusListeners()) {
            if (listener.getClass().getName().equals("javax.swing.text.JTextComponent$MutableCaretEvent")) {
                listener.focusGained(new java.awt.event.FocusEvent(text, java.awt.event.FocusEvent.FOCUS_GAINED));
            }
        }
    }

    /** An open modal dialog takes the keyboard, as it would for a user. */
    private Window keyWindow(Map<String, Object> params) {
        if (params.get("windowIndex") == null) {
            List<Window> visible = getVisibleWindows();
            for (int i = visible.size() - 1; i >= 0; i--) {
                if (visible.get(i) instanceof Dialog d && d.isModal()) {
                    return d;
                }
            }
        }
        Window win = getTargetWindow(params);
        if (win == null) {
            throw new IllegalStateException("No window to send keys to");
        }
        return win;
    }

    private static Window windowOf(Component c) {
        return c instanceof Window w ? w : SwingUtilities.getWindowAncestor(c);
    }

    private String describeKeyTarget(Component c) {
        String type = c.getClass().getSimpleName().isEmpty() ? c.getClass().getName() : c.getClass().getSimpleName();
        return assignUid(c) + " " + type + (c.getName() == null ? "" : " '" + c.getName() + "'");
    }

    /**
     * Brings {@code win} to the front for OS-level input, or fails: Robot events go
     * to whatever application is in front, so sending them to a background window
     * would type into another application. Called off the EDT.
     */
    private void ensureForeground(Window win) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("robot input needs a display");
        }
        if (win == null) {
            throw new IllegalStateException("No window to send keys to");
        }
        if (Boolean.TRUE.equals(invokeAndWaitQuietly(win::isFocused))) {
            return;
        }
        invokeAndWaitQuietly(() -> {
            // Window.toFront() does not activate a background application on macOS.
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop()
                    .isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
                java.awt.Desktop.getDesktop().requestForeground(true);
            }
            win.toFront();
            win.requestFocus();
            return null;
        });
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(invokeAndWaitQuietly(win::isFocused))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("The application window could not be brought to the front, so "
            + "OS-level keystrokes would go to another application. Omit robot to send the keys "
            + "to the application directly.");
    }

    /** Intermediate positions of a drag, so drag listeners and gesture thresholds see motion. */
    private static final int DRAG_STEPS = 10;

    /**
     * Drags with the left button from a point in one component to a point in
     * another (or the same) component. Called off the EDT.
     *
     * <p>Points are {@code fromX}/{@code fromY} and {@code toX}/{@code toY},
     * relative to each component's top-left corner, default its center;
     * {@code toUid} defaults to {@code fromUid}. By default the press, drag and
     * release are delivered as mouse events to the component under the start
     * point, as AWT does for a real drag; this covers drags inside a component
     * (sliders, dividers, chart zoom, canvas handles). Native drag-and-drop
     * between components needs the real mouse: {@code robot=true}.</p>
     */
    public String drag(Map<String, Object> params) throws Exception {
        String fromUid = getString(params, "fromUid");
        String toUid = getString(params, "toUid", fromUid);
        boolean robot = getBoolean(params, "robot", false);
        Component from = resolveUid(fromUid);
        Component to = resolveUid(toUid);
        Point[] points = invokeAndWaitQuietly(() -> new Point[] {
            pointIn(from, params, "fromX", "fromY"), pointIn(to, params, "toX", "toY")});
        String done = "Dragged from " + fromUid + " (" + points[0].x + "," + points[0].y + ") to "
            + toUid + " (" + points[1].x + "," + points[1].y + ")";
        if (robot) {
            robotDrag(from, points[0], to, points[1]);
            return done;
        }
        invokeAndWaitQuietly(() -> {
            dispatchDrag(from, points[0], to, points[1]);
            return null;
        });
        return done;
    }

    private void dispatchDrag(Component from, Point fromP, Component to, Point toP) {
        Component source = deepestAt(from, fromP);
        Point start = SwingUtilities.convertPoint(from, fromP, source);
        // AWT keeps sending drag and release events to the pressed component,
        // in its own coordinates, wherever the mouse goes.
        Point end = SwingUtilities.convertPoint(to, toP, source);
        long now = System.currentTimeMillis();
        int down = InputEvent.BUTTON1_DOWN_MASK;
        source.dispatchEvent(new MouseEvent(source, MouseEvent.MOUSE_MOVED, now, 0,
            start.x, start.y, 0, false, MouseEvent.NOBUTTON));
        source.dispatchEvent(new MouseEvent(source, MouseEvent.MOUSE_PRESSED, now, down,
            start.x, start.y, 1, false, MouseEvent.BUTTON1));
        for (int i = 1; i <= DRAG_STEPS; i++) {
            int x = start.x + (end.x - start.x) * i / DRAG_STEPS;
            int y = start.y + (end.y - start.y) * i / DRAG_STEPS;
            source.dispatchEvent(new MouseEvent(source, MouseEvent.MOUSE_DRAGGED, now, down,
                x, y, 1, false, MouseEvent.NOBUTTON));
        }
        source.dispatchEvent(new MouseEvent(source, MouseEvent.MOUSE_RELEASED, now, 0,
            end.x, end.y, 1, false, MouseEvent.BUTTON1));
    }

    private void robotDrag(Component from, Point fromP, Component to, Point toP) throws Exception {
        Point[] screen = invokeAndWaitQuietly(() -> {
            requireShowing(from);
            requireShowing(to);
            Point a = from.getLocationOnScreen();
            Point b = to.getLocationOnScreen();
            return new Point[] {new Point(a.x + fromP.x, a.y + fromP.y), new Point(b.x + toP.x, b.y + toP.y)};
        });
        Robot robot = new Robot();
        robot.setAutoDelay(15);
        robot.mouseMove(screen[0].x, screen[0].y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try {
            // Several moves: drag-and-drop only starts after the gesture threshold is passed.
            for (int i = 1; i <= DRAG_STEPS; i++) {
                robot.mouseMove(screen[0].x + (screen[1].x - screen[0].x) * i / DRAG_STEPS,
                    screen[0].y + (screen[1].y - screen[0].y) * i / DRAG_STEPS);
            }
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
    }

    /**
     * Scrolls a scrollable component in the specified direction. Components
     * with no enclosing {@link JScrollPane} (custom scrollers that paint their
     * own scrollbars) get mouse-wheel events at their centre instead.
     * {@code amount} defaults to {@value UiToolkit#DEFAULT_SCROLL_AMOUNT} units: scroll
     * bar unit increments, or wheel notches in the fallback.
     */
    public String scroll(Map<String, Object> params) {
        String uid = getString(params, "uid");
        String directionStr = getString(params, "direction", "DOWN");
        int amount = getInt(params, "amount", UiToolkit.DEFAULT_SCROLL_AMOUNT);
        Component comp = resolveUid(uid);

        ScrollDirection direction = ScrollDirection.valueOf(directionStr.toUpperCase());
        JScrollPane scrollPane = findScrollPane(comp);
        if (scrollPane == null) {
            // Shift+wheel is the Swing convention for horizontal scrolling.
            boolean horizontal = direction == ScrollDirection.LEFT || direction == ScrollDirection.RIGHT;
            int rotation = (direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT) ? amount : -amount;
            requireWheelNotches(rotation);
            Component target = dispatchWheel(comp, new Point(comp.getWidth() / 2, comp.getHeight() / 2),
                rotation, horizontal ? InputEvent.SHIFT_DOWN_MASK : 0);
            return "Scrolled " + direction + " by " + amount + " mouse-wheel notch(es) on "
                + ClassNames.displayName(target.getClass());
        }

        JScrollBar bar = (direction == ScrollDirection.UP || direction == ScrollDirection.DOWN)
            ? scrollPane.getVerticalScrollBar()
            : scrollPane.getHorizontalScrollBar();

        int delta = (direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT) ? amount : -amount;
        bar.setValue(bar.getValue() + delta * bar.getUnitIncrement());
        return "Scrolled " + direction + " by " + amount;
    }

    /**
     * Turns the mouse wheel over a component. Must run on the EDT.
     *
     * <p>Params: uid, rotation (notches, positive = down/towards the user),
     * x/y (relative to the component's top-left corner, default its center),
     * modifiers (e.g. "CTRL+SHIFT", "SHORTCUT"). One {@link MouseWheelEvent}
     * per notch is delivered to the component under the point, so it works
     * while the window is covered or in the background; {@code robot=true}
     * turns the real wheel over that point instead.</p>
     */
    public String mouseWheel(Map<String, Object> params) throws AWTException {
        String uid = getString(params, "uid");
        int rotation = getInt(params, "rotation", 0);
        Component comp = resolveUid(uid);
        if (!comp.isEnabled()) {
            throw new IllegalStateException("Component " + uid + " is disabled");
        }
        requireWheelNotches(rotation);
        Point p = pointIn(comp, params, "x", "y");
        String modifiers = getString(params, "modifiers", "");
        int mask = parseModifiers(modifiers);
        String delivered;
        if (getBoolean(params, "robot", false)) {
            robotWheel(comp, p, rotation, mask);
            delivered = "with the real mouse";
        } else {
            delivered = "delivered to " + ClassNames.displayName(dispatchWheel(comp, p, rotation, mask).getClass());
        }
        return "Mouse wheel rotated " + rotation + " notch(es) at (" + p.x + "," + p.y + ") of " + uid
            + (modifiers.isBlank() ? "" : " with " + modifiers.toUpperCase()) + "; " + delivered;
    }

    private static void requireWheelNotches(int rotation) {
        if (rotation == 0 || Math.abs(rotation) > UiToolkit.MAX_WHEEL_NOTCHES) {
            throw new IllegalArgumentException("Wheel notches must be between 1 and " + UiToolkit.MAX_WHEEL_NOTCHES
                + " in either direction, got " + rotation);
        }
    }

    /**
     * Dispatches one {@link MouseWheelEvent} per notch to the deepest component at {@code p}
     * (relative to {@code comp}). AWT passes a wheel event that component has no listener for
     * on to the nearest ancestor that has one, as for a physical wheel. Returns the component
     * the events were dispatched to.
     */
    private Component dispatchWheel(Component comp, Point p, int rotation, int modifiers) {
        Component target = deepestAt(comp, p);
        Point tp = SwingUtilities.convertPoint(comp, p, target);
        Point screen = new Point(tp);
        if (target.isShowing()) {
            SwingUtilities.convertPointToScreen(screen, target);
        }
        int notch = Integer.signum(rotation);
        for (int i = 0; i < Math.abs(rotation); i++) {
            // Unit scroll of 3 units per notch, as the desktop platforms report a physical wheel.
            target.dispatchEvent(new MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), modifiers, tp.x, tp.y, screen.x, screen.y,
                0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, notch, notch));
        }
        return target;
    }

    private void robotWheel(Component comp, Point p, int rotation, int modifiers) throws AWTException {
        requireShowing(comp);
        Robot robot = new Robot();
        Point loc = comp.getLocationOnScreen();
        List<Integer> keys = modifierKeys(modifiers);
        keys.forEach(robot::keyPress);
        try {
            robot.mouseMove(loc.x + p.x, loc.y + p.y);
            robot.mouseWheel(rotation);
        } finally {
            for (int i = keys.size() - 1; i >= 0; i--) {
                robot.keyRelease(keys.get(i));
            }
        }
    }

    /**
     * Waits for a condition with polling. Params: conditionType, uid, expectedValue, timeoutMs.
     * Called off the EDT; each check reads the component tree on the EDT, bounded
     * by the time left, so the wait never walks Swing state from another thread.
     */
    public String waitFor(Map<String, Object> params) throws Exception {
        String condType = getString(params, "conditionType");
        long timeoutMs = getLong(params, "timeoutMs", 5000L);
        long pollMs = 200L;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                if (checkConditionOnEdt(condType, params, deadline - System.currentTimeMillis())) {
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

    /** One poll of {@link #waitFor}: the EDT answering in time is the condition for EDT_IDLE. */
    private boolean checkConditionOnEdt(String condType, Map<String, Object> params, long timeoutMs) throws Exception {
        try {
            if ("EDT_IDLE".equalsIgnoreCase(condType)) {
                invokeAndWaitQuietly(() -> null, timeoutMs);
                return true;
            }
            return Boolean.TRUE.equals(invokeAndWaitQuietly(() -> checkCondition(condType, params), timeoutMs));
        } catch (EdtTimeoutException e) {
            return false;
        }
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
            if (ClassNames.matches(comp.getClass(), query, true)) {
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
     * Moves the mouse over a component, at {@code x}/{@code y} (default its
     * center), to trigger hover effects and tooltips. Called off the EDT.
     * Delivered as mouse-entered/moved events to the component under the point
     * unless {@code robot=true}, which moves the real mouse.
     */
    public String hover(Map<String, Object> params) throws Exception {
        String uid = getString(params, "uid");
        Component comp = resolveUid(uid);
        boolean robot = getBoolean(params, "robot", false);
        Point p = invokeAndWaitQuietly(() -> pointIn(comp, params, "x", "y"));
        if (robot) {
            Point screen = invokeAndWaitQuietly(() -> {
                requireShowing(comp);
                Point loc = comp.getLocationOnScreen();
                return new Point(loc.x + p.x, loc.y + p.y);
            });
            new Robot().mouseMove(screen.x, screen.y);
        } else {
            invokeAndWaitQuietly(() -> {
                Component target = deepestAt(comp, p);
                Point tp = SwingUtilities.convertPoint(comp, p, target);
                long now = System.currentTimeMillis();
                Component previous = lastHovered.get();
                if (previous != null && previous != target && previous.isDisplayable()) {
                    // As the real mouse would: leave the last component first, ending its
                    // rollover state and tooltip.
                    previous.dispatchEvent(new MouseEvent(previous, MouseEvent.MOUSE_EXITED, now, 0,
                        -1, -1, 0, false, MouseEvent.NOBUTTON));
                }
                lastHovered = new java.lang.ref.WeakReference<>(target);
                target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_ENTERED, now, 0,
                    tp.x, tp.y, 0, false, MouseEvent.NOBUTTON));
                target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_MOVED, now, 0,
                    tp.x, tp.y, 0, false, MouseEvent.NOBUTTON));
                return null;
            });
        }
        return "Hovering over: " + uid + " at (" + p.x + "," + p.y + ")";
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
     * Params: text (required), uid (optional - focus this component first), robot
     * (optional - OS-level keystrokes, see {@link #pressKey}). Called off the EDT.
     *
     * <p>Each character is dispatched as pressed/typed/released in its own EDT
     * turn, so work the application queues from its key listeners runs between
     * keystrokes, as it does for a typist. Unlike Robot, any character can be
     * typed, whatever the keyboard layout.</p>
     */
    public String typeText(Map<String, Object> params) throws Exception {
        String text = getString(params, "text");
        if (getBoolean(params, "robot", false)) {
            ensureForeground(invokeAndWaitQuietly(() -> windowOf(keyTarget(params))));
            Robot robot = new Robot();
            robot.setAutoDelay(20);
            for (char ch : text.toCharArray()) {
                typeChar(robot, ch);
            }
            return "Typed " + text.length() + " characters";
        }
        Component target = invokeAndWaitQuietly(() -> keyTarget(params));
        for (char ch : text.toCharArray()) {
            invokeAndWaitQuietly(() -> {
                typeCharEvents(target, ch);
                return null;
            });
        }
        return "Typed " + text.length() + " characters into " + describeKeyTarget(target);
    }

    private static void typeCharEvents(Component target, char ch) {
        // As in typeChar: the shift state assumes a US layout, but the character
        // itself is delivered exactly, so the inserted text is right on any layout.
        boolean shift = Character.isUpperCase(ch) || "~!@#$%^&*()_+{}|:\"<>?".indexOf(ch) >= 0;
        int code = KeyEvent.getExtendedKeyCodeForChar(ch);
        int mask = shift ? InputEvent.SHIFT_DOWN_MASK : 0;
        long now = System.currentTimeMillis();
        if (shift) {
            dispatchKey(target, KeyEvent.KEY_PRESSED, now, mask, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
        }
        dispatchKey(target, KeyEvent.KEY_PRESSED, now, mask, code, ch);
        dispatchKey(target, KeyEvent.KEY_TYPED, now, mask, KeyEvent.VK_UNDEFINED, ch);
        dispatchKey(target, KeyEvent.KEY_RELEASED, now, mask, code, ch);
        if (shift) {
            dispatchKey(target, KeyEvent.KEY_RELEASED, now, 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
        }
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
                // As in selectMenuItem: hide the popups and clear the selection path
                // BEFORE the click, so a modal dialog opened by the item does not
                // leave the menu on screen while it blocks (Issue #1 Defect C, Issue #2).
                for (int i = openedSubmenus.size() - 1; i >= 0; i--) {
                    openedSubmenus.get(i).setPopupMenuVisible(false);
                }
                popup.setVisible(false);
                MenuSelectionManager.defaultManager().clearSelectedPath();
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
                info.put("class", ClassNames.displayName(dialog.getClass()));
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
        // The dialog the user would be answering: the active modal dialog, else the
        // most recently created showing modal one (so the inner of nested modals),
        // else the most recent showing dialog. A non-modal palette opened at startup
        // must not shadow a JOptionPane opened later.
        Dialog modal = null;
        Dialog any = null;
        for (int i = visible.size() - 1; i >= 0; i--) {
            if (visible.get(i) instanceof Dialog d && d.isShowing()) {
                if (d.isModal() && d.isActive()) {
                    return d;
                }
                if (d.isModal() && modal == null) {
                    modal = d;
                }
                if (any == null) {
                    any = d;
                }
            }
        }
        if (modal != null) {
            return modal;
        }
        if (any != null) {
            return any;
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
            throw new IllegalArgumentException("Active window is not a Frame: " + ClassNames.displayName(win.getClass()));
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
        return invokeAndWaitQuietly(action, EDT_TIMEOUT_MS);
    }

    /**
     * Runs {@code action} on the EDT and waits at most {@code timeoutMs} for it.
     * An action that opens a modal dialog, or an EDT that is wedged, fails the
     * caller with {@link EdtTimeoutException} instead of blocking it indefinitely.
     */
    private <T> T invokeAndWaitQuietly(EdtAction<T> action, long timeoutMs) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.run();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(action.run());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS)) {
            throw new EdtTimeoutException(timeoutMs);
        }
        Throwable t = error.get();
        if (t instanceof Exception e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return result.get();
    }

    /** The EDT did not run a posted action in time: it is busy, wedged, or held by a modal dialog. */
    static final class EdtTimeoutException extends IllegalStateException {
        EdtTimeoutException(long timeoutMs) {
            super("The Event Dispatch Thread did not respond within " + timeoutMs
                + " ms. The application UI may be blocked or busy, or a modal dialog may be open.");
        }
    }

    private List<ComponentDescriptor> scanComponent(Component comp, ComponentStateFilter filter,
                                                    ScanBudget budget, int depth) {
        List<ComponentDescriptor> result = new ArrayList<>();
        List<ComponentDescriptor> children = new ArrayList<>();
        Integer omittedChildren = null;
        if (comp instanceof Container container) {
            if (depth < budget.maxDepth) {
                for (Component child : container.getComponents()) {
                    if (budget.remaining <= 0) {
                        budget.truncated = true;
                        break;
                    }
                    children.addAll(scanComponent(child, filter, budget, depth + 1));
                }
            } else if (container.getComponentCount() > 0) {
                // Cut by maxDepth: say how much is below so the client can drill in by uid.
                omittedChildren = container.getComponentCount();
                budget.truncated = true;
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
        String tooltip = extractTooltip(comp);
        String selectionState = extractSelectionState(comp);
        Rectangle bounds = comp.getBounds();
        ComponentDescriptor desc = new ComponentDescriptor(
            uid,
            ClassNames.displayName(comp.getClass()),
            comp.getName(),
            text,
            tooltip != null && tooltip.equals(text) ? null : tooltip,
            comp.isEnabled(),
            comp.isVisible(),
            comp.isFocusable(),
            bounds.x, bounds.y, bounds.width, bounds.height,
            selectionState,
            children.isEmpty() ? null : children,
            omittedChildren
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

    /** A registry entry: weak, and remembers its uid so a collected entry can be dropped. */
    private static final class UidRef extends WeakReference<Component> {
        private final String uid;

        UidRef(Component comp, String uid, ReferenceQueue<Component> queue) {
            super(comp, queue);
            this.uid = uid;
        }
    }

    /** Package-private for tests. */
    String assignUid(Component comp) {
        synchronized (uidLock) {
            purgeCollectedUids();
            String uid = componentToUid.get(comp);
            if (uid == null) {
                uid = "comp-" + uidCounter.incrementAndGet();
                componentToUid.put(comp, uid);
                uidToComponent.put(uid, new UidRef(comp, uid, collectedUids));
            }
            return uid;
        }
    }

    /** Package-private for tests. */
    Component resolveUid(String uid) {
        Component comp;
        synchronized (uidLock) {
            purgeCollectedUids();
            UidRef ref = uid == null ? null : uidToComponent.get(uid);
            comp = ref == null ? null : ref.get();
        }
        if (comp == null) {
            throw new IllegalArgumentException("Unknown component UID: " + uid + ". Please take a fresh snapshot.");
        }
        return comp;
    }

    /** Number of live uid entries; package-private for tests. */
    int registeredUidCount() {
        synchronized (uidLock) {
            purgeCollectedUids();
            return uidToComponent.size();
        }
    }

    /** Drops the entries of collected components. Caller holds {@link #uidLock}. */
    private void purgeCollectedUids() {
        for (Reference<? extends Component> ref = collectedUids.poll(); ref != null; ref = collectedUids.poll()) {
            uidToComponent.remove(((UidRef) ref).uid, ref);
        }
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

    /** The component's tooltip as plain text; HTML tooltips are reduced to their text. */
    private String extractTooltip(Component comp) {
        if (!(comp instanceof JComponent jc)) {
            return null;
        }
        String tip = jc.getToolTipText();
        if (tip == null || tip.isBlank()) {
            return null;
        }
        if (tip.regionMatches(true, 0, "<html>", 0, 6)) {
            tip = tip.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ").strip();
        }
        return tip.isEmpty() ? null : tip;
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
        info.put("simpleName", ClassNames.displayName(comp.getClass()));
        info.put("name", comp.getName());
        info.put("text", extractText(comp));
        String tooltip = extractTooltip(comp);
        if (tooltip != null) {
            info.put("tooltip", tooltip);
        }
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
        Window active = activeWindow.get();
        if (active != null && active.isVisible()) {
            return active;
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
        return ClassNames.displayName(w.getClass());
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
                if (menu != null && path[0].equals(menu.getText())) {
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
            // Icon-only items have no text.
            if (path[0].equals(item.getText())) {
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

    /** Evaluates a {@link #waitFor} condition. Must run on the EDT. */
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
            // Reached on the EDT, which is then by definition processing events.
            case "EDT_IDLE" -> true;
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

    /**
     * Parses a chord such as {@code "CTRL+SHIFT+S"}, {@code "ENTER"}, {@code "F5"}
     * or {@code "PAGE_DOWN"}: modifier and key names, any {@code KeyEvent.VK_*}
     * name without the prefix, or a single character.
     */
    static List<Key> parseKeyChord(String keys) {
        List<Key> chord = new ArrayList<>();
        for (String raw : keys.split("\\+")) {
            String part = raw.trim();
            String name = part.toUpperCase();
            chord.add(switch (name) {
                case "CTRL", "CONTROL" -> new Key(KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED);
                case "ALT", "OPTION" -> new Key(KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED);
                case "SHIFT" -> new Key(KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
                case "META", "CMD", "COMMAND" -> new Key(KeyEvent.VK_META, KeyEvent.CHAR_UNDEFINED);
                case "SHORTCUT" -> new Key(shortcutKey(), KeyEvent.CHAR_UNDEFINED);
                case "ENTER", "RETURN" -> new Key(KeyEvent.VK_ENTER, '\n');
                case "ESCAPE", "ESC" -> new Key(KeyEvent.VK_ESCAPE, (char) 27);
                case "TAB" -> new Key(KeyEvent.VK_TAB, '\t');
                case "SPACE" -> new Key(KeyEvent.VK_SPACE, ' ');
                case "BACKSPACE" -> new Key(KeyEvent.VK_BACK_SPACE, '\b');
                case "DELETE", "DEL" -> new Key(KeyEvent.VK_DELETE, (char) 127);
                case "PGUP" -> new Key(KeyEvent.VK_PAGE_UP, KeyEvent.CHAR_UNDEFINED);
                case "PGDN" -> new Key(KeyEvent.VK_PAGE_DOWN, KeyEvent.CHAR_UNDEFINED);
                default -> {
                    if (part.length() == 1) {
                        char ch = part.charAt(0);
                        int code = KeyEvent.getExtendedKeyCodeForChar(ch);
                        if (code == KeyEvent.VK_UNDEFINED) {
                            throw new IllegalArgumentException("No key for character: " + part);
                        }
                        yield new Key(code, ch);
                    }
                    yield new Key(namedKeyCode(name, keys), KeyEvent.CHAR_UNDEFINED);
                }
            });
        }
        return chord;
    }

    /** Looks up {@code KeyEvent.VK_<name>}, e.g. F5, HOME, END, PAGE_UP, INSERT. */
    private static int namedKeyCode(String name, String keys) {
        try {
            return KeyEvent.class.getField("VK_" + name.replace(' ', '_')).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unknown key \"" + name + "\" in \"" + keys
                + "\". Use names such as ENTER, TAB, ESCAPE, F5, HOME, PAGE_DOWN, a single character, "
                + "and modifiers CTRL, SHIFT, ALT, META, SHORTCUT joined with '+'");
        }
    }

    /** The platform's menu shortcut key: Cmd on macOS, Ctrl elsewhere. */
    private static int shortcutKey() {
        if (GraphicsEnvironment.isHeadless()) {
            return KeyEvent.VK_CONTROL;
        }
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK
            ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
    }

    private Object parseSpinnerValue(JSpinner spinner, String text) {
        return spinnerValue(spinner.getModel(), text);
    }

    /**
     * Converts {@code text} to the value a user typing into the spinner would
     * commit. A {@link javax.swing.SpinnerNumberModel} keeps the number class of
     * its current value (an integer model stays {@code Integer}), so application
     * code casting {@code getValue()} keeps working; values outside the model's
     * bounds are rejected instead of being set silently.
     */
    static Object spinnerValue(javax.swing.SpinnerModel model, String text) {
        if (!(model instanceof javax.swing.SpinnerNumberModel numbers)) {
            return text;
        }
        java.math.BigDecimal parsed;
        try {
            parsed = new java.math.BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Spinner expects a number, got \"" + text + "\"");
        }
        Class<?> type = numbers.getNumber() == null ? Double.class : numbers.getNumber().getClass();
        Number value;
        try {
            if (type == Integer.class) {
                value = parsed.intValueExact();
            } else if (type == Long.class) {
                value = parsed.longValueExact();
            } else if (type == Short.class) {
                value = parsed.shortValueExact();
            } else if (type == Byte.class) {
                value = parsed.byteValueExact();
            } else if (type == java.math.BigInteger.class) {
                value = parsed.toBigIntegerExact();
            } else if (type == java.math.BigDecimal.class) {
                value = parsed;
            } else if (type == Float.class) {
                value = parsed.floatValue();
            } else {
                value = parsed.doubleValue();
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Spinner holds " + type.getSimpleName()
                + " values; \"" + text + "\" is not a whole number in that range");
        }
        if (outOfBounds(numbers.getMinimum(), value, true) || outOfBounds(numbers.getMaximum(), value, false)) {
            throw new IllegalArgumentException("Value " + value + " is outside the spinner's range ["
                + numbers.getMinimum() + ", " + numbers.getMaximum() + "]");
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean outOfBounds(Comparable bound, Number value, boolean lower) {
        if (bound == null) {
            return false;
        }
        try {
            int cmp = bound.compareTo(value);
            return lower ? cmp > 0 : cmp < 0;
        } catch (ClassCastException e) {
            // Bound of a different Number class than the value: let the model decide.
            return false;
        }
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

    private boolean getBoolean(Map<String, Object> params, String key, boolean defaultVal) {
        Object val = params.get(key);
        if (val == null) {
            return defaultVal;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(val.toString());
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
