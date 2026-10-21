package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.InteractionService;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for interacting with components of the target application.
 */
@Component
public class InteractionTools {

    private final InteractionService interactionService;

    public InteractionTools(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @McpTool(name = "click",
        annotations = @McpTool.McpAnnotations(title = "Click", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = "Click a component by UID. Buttons are clicked directly; other components via mouse emulation.")
    public String click(
            @McpToolParam(description = "Component UID from a snapshot") String uid,
            @McpToolParam(description = "Mouse button: LEFT, RIGHT, or MIDDLE (default LEFT)", required = false) String button,
            @McpToolParam(description = "Click type: SINGLE or DOUBLE (default SINGLE)", required = false) String clickType) {
        return ToolJson.toJson(interactionService.click(uid, button, clickType));
    }

    @McpTool(name = "hover",
        annotations = @McpTool.McpAnnotations(title = "Hover component", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "Move the mouse over a component by UID to trigger hover effects and tooltips.")
    public String hover(
            @McpToolParam(description = "Component UID from a snapshot") String uid) {
        return ToolJson.toJson(interactionService.hover(uid));
    }

    @McpTool(name = "focus",
        annotations = @McpTool.McpAnnotations(title = "Focus component", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "Give keyboard focus to a component by UID so subsequent press_key/type_text calls target it.")
    public String focus(
            @McpToolParam(description = "Component UID from a snapshot") String uid) {
        return ToolJson.toJson(interactionService.focus(uid));
    }

    @McpTool(name = "type_text",
        annotations = @McpTool.McpAnnotations(title = "Type text", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = """
        Type text character-by-character into the focused component using key events \
        (unlike fill, which sets the value directly). Triggers per-keystroke listeners. \
        The events go to the application itself, so it works while the app is in the background.""")
    public String typeText(
            @McpToolParam(description = "Text to type") String text,
            @McpToolParam(description = "Optional component UID to focus before typing", required = false) String uid,
            @McpToolParam(description = "Swing: send OS-level keystrokes via Robot; refused unless the window can be brought to the front", required = false) Boolean robot) {
        return ToolJson.toJson(interactionService.typeText(text, uid, robot));
    }

    @McpTool(name = "select_context_menu_item",
        annotations = @McpTool.McpAnnotations(title = "Select context-menu item", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = """
        Open the context menu of a component and click an item by path, \
        e.g. "Copy" or "Refactor > Rename".""")
    public String selectContextMenuItem(
            @McpToolParam(description = "Component UID from a snapshot") String uid,
            @McpToolParam(description = "Menu item path separated by ' > '") String path) {
        return ToolJson.toJson(interactionService.selectContextMenuItem(uid, path));
    }

    @McpTool(name = "fill",
        annotations = @McpTool.McpAnnotations(title = "Fill text", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "Set the text of a text field/area, spinner, or editable combo box by UID.")
    public String fill(
            @McpToolParam(description = "Component UID from a snapshot") String uid,
            @McpToolParam(description = "Text or value to enter") String text) {
        return ToolJson.toJson(interactionService.fill(uid, text));
    }

    @McpTool(name = "select_option",
        annotations = @McpTool.McpAnnotations(title = "Select option", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "Select an option in a JList, JComboBox, or JTabbedPane by index or visible text.")
    public String selectOption(
            @McpToolParam(description = "Component UID from a snapshot") String uid,
            @McpToolParam(description = "Zero-based option index", required = false) Integer index,
            @McpToolParam(description = "Visible text of the option to select", required = false) String text) {
        return ToolJson.toJson(interactionService.selectOption(uid, index, text));
    }

    @McpTool(name = "select_tree_node",
        annotations = @McpTool.McpAnnotations(title = "Select tree node", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "Select a JTree node by path, e.g. \"Root > Folder > Leaf\".")
    public String selectTreeNode(
            @McpToolParam(description = "JTree component UID") String uid,
            @McpToolParam(description = "Node path separated by ' > '") String path) {
        return ToolJson.toJson(interactionService.selectTreeNode(uid, path));
    }

    @McpTool(name = "select_table_cell",
        annotations = @McpTool.McpAnnotations(title = "Select table cell", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = """
        Select a JTable/TableView cell by zero-based row and column, like a click. \
        For multi-selection use toggle (Ctrl/Cmd-click) or extend (Shift-click), \
        or pass rows to set several selected rows at once.""")
    public String selectTableCell(
            @McpToolParam(description = "JTable (Swing) or TableView (JavaFX) component UID") String uid,
            @McpToolParam(description = "Zero-based row index; omit when passing rows", required = false) Integer row,
            @McpToolParam(description = "Zero-based column index (default 0)", required = false) Integer col,
            @McpToolParam(description = "Add or remove this row from the selection (Ctrl/Cmd-click)", required = false) Boolean toggle,
            @McpToolParam(description = "Select the range from the anchor row to this row (Shift-click)", required = false) Boolean extend,
            @McpToolParam(description = "Zero-based rows that become the whole selection; [] clears it", required = false) List<Integer> rows) {
        return ToolJson.toJson(interactionService.selectTableCell(uid, row, col, toggle, extend, rows));
    }

    @McpTool(name = "select_menu_item",
        annotations = @McpTool.McpAnnotations(title = "Select menu item", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = "Click a menu item by path in the active window, e.g. \"File > Save\".")
    public String selectMenuItem(
            @McpToolParam(description = "Menu path separated by ' > '") String path) {
        return ToolJson.toJson(interactionService.selectMenuItem(path));
    }

    @McpTool(name = "press_key",
        annotations = @McpTool.McpAnnotations(title = "Press key", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = """
        Press a key or key chord in the target application, e.g. "ENTER", "CTRL+S", "F5". \
        Sent to the component with keyboard focus in the application (or uid), even while the app \
        is in the background; the result names the component that received it.""")
    public String pressKey(
            @McpToolParam(description = "Key or chord such as ENTER, TAB, ESCAPE, F5, HOME, PAGE_DOWN, CTRL+S, SHORTCUT+A") String keys,
            @McpToolParam(description = "Optional component UID to focus and send the keys to", required = false) String uid,
            @McpToolParam(description = "Swing: send OS-level keystrokes via Robot; refused unless the window can be brought to the front", required = false) Boolean robot) {
        return ToolJson.toJson(interactionService.pressKey(keys, uid, robot));
    }

    @McpTool(name = "drag",
        annotations = @McpTool.McpAnnotations(title = "Drag", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = "Drag from one component to another using mouse emulation. Swing sessions only.")
    public String drag(
            @McpToolParam(description = "Source component UID") String fromUid,
            @McpToolParam(description = "Target component UID") String toUid) {
        return ToolJson.toJson(interactionService.drag(fromUid, toUid));
    }

    @McpTool(name = "scroll",
        annotations = @McpTool.McpAnnotations(title = "Scroll", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = "Scroll a scrollable component in a direction by a number of units.")
    public String scroll(
            @McpToolParam(description = "Component UID inside a scroll pane") String uid,
            @McpToolParam(description = "Direction: UP, DOWN, LEFT, or RIGHT (default DOWN)", required = false) String direction,
            @McpToolParam(description = "Number of scroll units (default 3)", required = false) Integer amount) {
        return ToolJson.toJson(interactionService.scroll(uid, direction, amount));
    }
}
