# Inspection tools

Tools for discovering the component structure of the target application. All
interaction tools rely on component UIDs produced by `take_snapshot`.

## `take_snapshot`

Take a snapshot of the active window's Swing component tree. Every component
gets a stable UID (e.g. `comp-42`) that interaction tools use to address it.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `windowIndex` | number | no | Index of the window to snapshot (from `list_windows`); defaults to the active window |
| `filter` | string | no | State filter to reduce snapshot size: `ALL` (default), `VISIBLE_ONLY`, `ENABLED_ONLY`, `FOCUSABLE_ONLY` |
| `maxNodes` | number | no | Cap on the number of component nodes returned (default 2000). Prevents a huge UI from overflowing the client's context window |
| `maxDepth` | number | no | Maximum tree depth to descend (default unlimited) |

**Returns:** a tree of components with, per node: UID, component class, name,
text/label, and key state flags. When the tree was cut short by `maxNodes` or
`maxDepth`, the root carries `"truncated": true` — narrow the request (a
`filter`, a smaller `maxDepth`, or `find_component`) to see the rest.

**Notes:**
- Take a fresh snapshot after any action that changes the UI; UIDs from stale
  snapshots may no longer resolve.
- With a filter, containers that do not match are still included when they
  have matching descendants, so the tree structure is preserved.

## `get_component_details`

Get detailed information about a single component.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID from a snapshot |

**Returns:** component class, text, bounds, enabled / visible / focusable
flags, selection state, and accessibility metadata.

## `find_component`

Find components by text, name, tooltip, or class without taking a full
snapshot. Useful for very large component trees where a full snapshot is
expensive or noisy.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | Search string (case-insensitive substring match) |
| `by` | string | no | Field to search: `TEXT`, `NAME`, `TOOLTIP`, `CLASS`, or `ANY` (default) |

**Returns:** the matching components (across all visible windows) with their
UIDs, class, text, and bounds.

## `get_table_data`

Extract the model contents of a `JTable` as structured data.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | JTable component UID |
| `startRow` | number | no | Zero-based first row (inclusive) |
| `endRow` | number | no | Zero-based last row (inclusive) |

**Returns:** `rowCount`, `columns` (column names), and `rows` (cell values).

## `get_list_items`

Extract the items of a `JList` or the visible rows of a `JTree` as structured
data.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | JList or JTree component UID |
| `startIndex` | number | no | Zero-based first item (inclusive) |
| `endIndex` | number | no | Zero-based last item (inclusive) |

**Returns:** `itemCount` and `items`. For a `JTree`, items are the visible
rows rendered as ` > `-separated paths.
