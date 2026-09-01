package solutions.crosstech.swingmcp.common.enums;

/**
 * Types of commands sent from the MCP server to the agent.
 */
public enum CommandType {
    TAKE_SNAPSHOT,
    GET_COMPONENT_DETAILS,
    FIND_COMPONENT,
    GET_TABLE_DATA,
    GET_LIST_ITEMS,
    TAKE_SCREENSHOT,
    LIST_WINDOWS,
    SELECT_WINDOW,
    RESIZE_WINDOW,
    MOVE_WINDOW,
    SET_WINDOW_STATE,
    CLOSE_WINDOW,
    CLICK,
    HOVER,
    FOCUS,
    TYPE_TEXT,
    FILL,
    SELECT_OPTION,
    SELECT_TREE_NODE,
    SELECT_TABLE_CELL,
    SELECT_MENU_ITEM,
    SELECT_CONTEXT_MENU_ITEM,
    LIST_DIALOGS,
    HANDLE_DIALOG,
    GET_CLIPBOARD,
    SET_CLIPBOARD,
    PRESS_KEY,
    DRAG,
    SCROLL,
    WAIT_FOR,
    EVALUATE_JAVA,
    PING
}
