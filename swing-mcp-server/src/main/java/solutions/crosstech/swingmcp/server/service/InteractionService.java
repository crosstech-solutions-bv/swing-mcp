package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * User-interaction operations (click, fill, select, keyboard, drag, scroll, wheel)
 * on components of the target application.
 */
@Service
public class InteractionService {

    private final SessionRegistry registry;

    public InteractionService(SessionRegistry registry) {
        this.registry = registry;
    }

    /** Clicks a component by UID. */
    public Object click(String uid, String button, String clickType) {
        return click(uid, button, clickType, null, null, null, null);
    }

    /**
     * Clicks a component by UID, optionally at an offset from its top-left
     * corner, with modifier keys held, or with the real OS mouse.
     */
    public Object click(String uid, String button, String clickType, Integer x, Integer y,
                        String modifiers, Boolean robot) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        if (button != null) {
            params.put("button", button);
        }
        if (clickType != null) {
            params.put("clickType", clickType);
        }
        putPoint(params, "x", x, "y", y);
        if (modifiers != null && !modifiers.isBlank()) {
            params.put("modifiers", modifiers);
        }
        if (Boolean.TRUE.equals(robot)) {
            params.put("robot", true);
        }
        return registry.require().send(CommandType.CLICK, params);
    }

    /** Moves the mouse over a component to trigger hover effects and tooltips. */
    public Object hover(String uid) {
        return hover(uid, null, null, null);
    }

    /** Moves the mouse over a component, optionally at an offset or with the real OS mouse. */
    public Object hover(String uid, Integer x, Integer y, Boolean robot) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        putPoint(params, "x", x, "y", y);
        if (Boolean.TRUE.equals(robot)) {
            params.put("robot", true);
        }
        return registry.require().send(CommandType.HOVER, params);
    }

    private static void putPoint(Map<String, Object> params, String xKey, Integer x, String yKey, Integer y) {
        if (x != null) {
            params.put(xKey, x);
        }
        if (y != null) {
            params.put(yKey, y);
        }
    }

    /** Gives keyboard focus to a component. */
    public Object focus(String uid) {
        return registry.require().send(CommandType.FOCUS, Map.of("uid", Params.require("uid", uid)));
    }

    /** Types text character-by-character using key events. */
    public Object typeText(String text, String uid) {
        return typeText(text, uid, null);
    }

    /** Types text character-by-character, as key events or, with {@code robot}, OS-level keystrokes. */
    public Object typeText(String text, String uid, Boolean robot) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", Params.require("text", text));
        if (uid != null && !uid.isBlank()) {
            params.put("uid", uid);
        }
        if (Boolean.TRUE.equals(robot)) {
            params.put("robot", true);
        }
        return registry.require().send(CommandType.TYPE_TEXT, params);
    }

    /** Opens the context menu of a component and clicks an item by path. */
    public Object selectContextMenuItem(String uid, String path) {
        return registry.require().send(CommandType.SELECT_CONTEXT_MENU_ITEM,
            Map.of("uid", Params.require("uid", uid), "path", Params.require("path", path)));
    }

    /** Fills a text component, spinner, or editable combo box. */
    public Object fill(String uid, String text) {
        return registry.require().send(CommandType.FILL,
            Map.of("uid", Params.require("uid", uid), "text", Params.require("text", text)));
    }

    /** Selects an option in a list, combo box, or tabbed pane by index or text. */
    public Object selectOption(String uid, Integer index, String text) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        if (index != null) {
            params.put("index", index);
        }
        if (text != null) {
            params.put("text", text);
        }
        return registry.require().send(CommandType.SELECT_OPTION, params);
    }

    /** Selects a tree node by path, e.g. "Root > Folder > Leaf". */
    public Object selectTreeNode(String uid, String path) {
        return registry.require().send(CommandType.SELECT_TREE_NODE,
            Map.of("uid", Params.require("uid", uid), "path", Params.require("path", path)));
    }

    /** Selects a table cell by row and column. */
    public Object selectTableCell(String uid, int row, int col) {
        return selectTableCell(uid, row, col, null, null, null);
    }

    /**
     * Selects a table cell, or several rows. {@code toggle} and {@code extend}
     * mirror Ctrl/Cmd- and Shift-click; {@code rows} replaces the whole selection.
     */
    public Object selectTableCell(String uid, Integer row, Integer col, Boolean toggle, Boolean extend,
                                  List<Integer> rows) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        if (row != null) {
            params.put("row", row);
        }
        if (col != null) {
            params.put("col", col);
        }
        if (Boolean.TRUE.equals(toggle)) {
            params.put("toggle", true);
        }
        if (Boolean.TRUE.equals(extend)) {
            params.put("extend", true);
        }
        if (rows != null) {
            params.put("rows", rows);
        }
        return registry.require().send(CommandType.SELECT_TABLE_CELL, params);
    }

    /** Selects a menu item by path, e.g. "File > Save". */
    public Object selectMenuItem(String path) {
        return registry.require().send(CommandType.SELECT_MENU_ITEM, Map.of("path", Params.require("path", path)));
    }

    /** Presses a key chord, e.g. "CTRL+S" or "ENTER". */
    public Object pressKey(String keys) {
        return pressKey(keys, null, null);
    }

    /**
     * Presses a key chord, sent to {@code uid} when given (else the application's
     * focus owner), or as OS-level keystrokes with {@code robot}.
     */
    public Object pressKey(String keys, String uid, Boolean robot) {
        Map<String, Object> params = new HashMap<>();
        params.put("keys", Params.require("keys", keys));
        if (uid != null && !uid.isBlank()) {
            params.put("uid", uid);
        }
        if (Boolean.TRUE.equals(robot)) {
            params.put("robot", true);
        }
        return registry.require().send(CommandType.PRESS_KEY, params);
    }

    /** Drags from one component to another. */
    public Object drag(String fromUid, String toUid) {
        return drag(fromUid, toUid, null, null, null, null, null);
    }

    /**
     * Drags from a point in one component to a point in another (or, with no
     * {@code toUid}, the same) component; offsets default to the centers.
     */
    public Object drag(String fromUid, String toUid, Integer fromX, Integer fromY, Integer toX, Integer toY,
                       Boolean robot) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromUid", fromUid);
        if (toUid != null && !toUid.isBlank()) {
            params.put("toUid", toUid);
        }
        putPoint(params, "fromX", fromX, "fromY", fromY);
        putPoint(params, "toX", toX, "toY", toY);
        if (Boolean.TRUE.equals(robot)) {
            params.put("robot", true);
        }
        return registry.require().send(CommandType.DRAG, params);
    }

    /** Scrolls a scrollable component. */
    public Object scroll(String uid, String direction, Integer amount) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        if (direction != null) {
            params.put("direction", direction);
        }
        if (amount != null) {
            params.put("amount", amount);
        }
        return registry.require().send(CommandType.SCROLL, params);
    }

    /**
     * Turns the mouse wheel over a component, optionally at an offset from its
     * top-left corner, with modifier keys held, or with the real OS mouse.
     */
    public Object mouseWheel(String uid, int rotation, Integer x, Integer y, String modifiers, Boolean robot) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        params.put("rotation", rotation);
        putPoint(params, "x", x, "y", y);
        if (modifiers != null && !modifiers.isBlank()) {
            params.put("modifiers", modifiers);
        }
        if (Boolean.TRUE.equals(robot)) {
            params.put("robot", true);
        }
        return registry.require().send(CommandType.MOUSE_WHEEL, params);
    }
}
