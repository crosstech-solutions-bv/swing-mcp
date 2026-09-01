package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Retrieves component tree snapshots and component details from the agent.
 */
@Service
public class SnapshotService {

    private final SessionRegistry registry;

    public SnapshotService(SessionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Takes a snapshot of the active window's component tree.
     *
     * @param windowIndex optional window index; null for the active window
     */
    public Object takeSnapshot(Integer windowIndex) {
        return takeSnapshot(windowIndex, null);
    }

    /**
     * Takes a snapshot of the active window's component tree.
     *
     * @param windowIndex optional window index; null for the active window
     * @param filter      optional state filter: ALL, VISIBLE_ONLY, ENABLED_ONLY, FOCUSABLE_ONLY
     */
    public Object takeSnapshot(Integer windowIndex, String filter) {
        Map<String, Object> params = new HashMap<>();
        if (windowIndex != null) {
            params.put("windowIndex", windowIndex);
        }
        if (filter != null && !filter.isBlank()) {
            params.put("filter", filter);
        }
        return registry.require().send(CommandType.TAKE_SNAPSHOT, params);
    }

    /**
     * Finds components matching a query by text, name, tooltip, or class.
     *
     * @param query the search string (case-insensitive substring match)
     * @param by    optional field: TEXT, NAME, TOOLTIP, CLASS, or ANY (default)
     */
    public Object findComponent(String query, String by) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        if (by != null && !by.isBlank()) {
            params.put("by", by);
        }
        return registry.require().send(CommandType.FIND_COMPONENT, params);
    }

    /**
     * Extracts the model contents of a JTable.
     *
     * @param uid      the JTable component UID
     * @param startRow optional zero-based first row (inclusive)
     * @param endRow   optional zero-based last row (inclusive)
     */
    public Object tableData(String uid, Integer startRow, Integer endRow) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        if (startRow != null) {
            params.put("startRow", startRow);
        }
        if (endRow != null) {
            params.put("endRow", endRow);
        }
        return registry.require().send(CommandType.GET_TABLE_DATA, params);
    }

    /**
     * Extracts the model contents of a JList or the visible rows of a JTree.
     *
     * @param uid        the JList or JTree component UID
     * @param startIndex optional zero-based first item (inclusive)
     * @param endIndex   optional zero-based last item (inclusive)
     */
    public Object listItems(String uid, Integer startIndex, Integer endIndex) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        if (startIndex != null) {
            params.put("startIndex", startIndex);
        }
        if (endIndex != null) {
            params.put("endIndex", endIndex);
        }
        return registry.require().send(CommandType.GET_LIST_ITEMS, params);
    }

    /**
     * Returns detailed information about a component identified by UID.
     */
    public Object componentDetails(String uid) {
        return registry.require().send(CommandType.GET_COMPONENT_DETAILS, Map.of("uid", uid));
    }
}
