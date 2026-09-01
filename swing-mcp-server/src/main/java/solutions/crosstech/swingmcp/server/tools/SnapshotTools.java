package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.SnapshotService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for inspecting the target application's component tree.
 */
@Component
public class SnapshotTools {

    private final SnapshotService snapshotService;

    public SnapshotTools(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Tool(name = "take_snapshot", description = """
        Take a snapshot of the active window's Swing component tree. \
        Each component gets a stable UID (e.g. "comp-42") used by interaction tools. \
        Always take a fresh snapshot after actions that change the UI.""")
    public String takeSnapshot(
            @ToolParam(description = "Optional index of the window to snapshot (from list_windows)", required = false) Integer windowIndex,
            @ToolParam(description = "Optional state filter to reduce snapshot size: ALL (default), VISIBLE_ONLY, ENABLED_ONLY, FOCUSABLE_ONLY", required = false) String filter) {
        return ToolJson.toJson(snapshotService.takeSnapshot(windowIndex, filter));
    }

    @Tool(name = "get_component_details", description = """
        Get detailed information about a single component by UID, including \
        accessibility metadata, bounds, text, and selection state.""")
    public String getComponentDetails(
            @ToolParam(description = "Component UID from a snapshot") String uid) {
        return ToolJson.toJson(snapshotService.componentDetails(uid));
    }

    @Tool(name = "find_component", description = """
        Find components by text, name, tooltip, or class without taking a full snapshot. \
        Returns matching components with their UIDs. Useful for very large component trees.""")
    public String findComponent(
            @ToolParam(description = "Search string (case-insensitive substring match)") String query,
            @ToolParam(description = "Field to search: TEXT, NAME, TOOLTIP, CLASS, or ANY (default)", required = false) String by) {
        return ToolJson.toJson(snapshotService.findComponent(query, by));
    }

    @Tool(name = "get_table_data", description = """
        Extract the model contents of a JTable as structured data: column names \
        and row values, optionally limited to a row range.""")
    public String getTableData(
            @ToolParam(description = "JTable component UID") String uid,
            @ToolParam(description = "Zero-based first row (inclusive)", required = false) Integer startRow,
            @ToolParam(description = "Zero-based last row (inclusive)", required = false) Integer endRow) {
        return ToolJson.toJson(snapshotService.tableData(uid, startRow, endRow));
    }

    @Tool(name = "get_list_items", description = """
        Extract the items of a JList or the visible rows of a JTree as structured \
        data, optionally limited to an index range.""")
    public String getListItems(
            @ToolParam(description = "JList or JTree component UID") String uid,
            @ToolParam(description = "Zero-based first item (inclusive)", required = false) Integer startIndex,
            @ToolParam(description = "Zero-based last item (inclusive)", required = false) Integer endIndex) {
        return ToolJson.toJson(snapshotService.listItems(uid, startIndex, endIndex));
    }
}
