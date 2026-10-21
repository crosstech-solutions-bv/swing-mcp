package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * User-interaction operations (click, fill, select, keyboard, drag, scroll)
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
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        if (button != null) {
            params.put("button", button);
        }
        if (clickType != null) {
            params.put("clickType", clickType);
        }
        return registry.require().send(CommandType.CLICK, params);
    }

    /** Moves the mouse over a component to trigger hover effects and tooltips. */
    public Object hover(String uid) {
        return registry.require().send(CommandType.HOVER, Map.of("uid", uid));
    }

    /** Gives keyboard focus to a component. */
    public Object focus(String uid) {
        return registry.require().send(CommandType.FOCUS, Map.of("uid", uid));
    }

    /** Types text character-by-character using key events. */
    public Object typeText(String text, String uid) {
        return typeText(text, uid, null);
    }

    /** Types text character-by-character, as key events or, with {@code robot}, OS-level keystrokes. */
    public Object typeText(String text, String uid, Boolean robot) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
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
        return registry.require().send(CommandType.SELECT_CONTEXT_MENU_ITEM, Map.of("uid", uid, "path", path));
    }

    /** Fills a text component, spinner, or editable combo box. */
    public Object fill(String uid, String text) {
        return registry.require().send(CommandType.FILL, Map.of("uid", uid, "text", text));
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
        return registry.require().send(CommandType.SELECT_TREE_NODE, Map.of("uid", uid, "path", path));
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
        return registry.require().send(CommandType.SELECT_MENU_ITEM, Map.of("path", path));
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
        params.put("keys", keys);
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
        return registry.require().send(CommandType.DRAG, Map.of("fromUid", fromUid, "toUid", toUid));
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
}
