# Inspection tools

Tools for discovering the component structure of the target application. All
interaction tools rely on component UIDs produced by `take_snapshot`.

## `take_snapshot`

Take a snapshot of the active window's component tree, or of the subtree
below one component. Every component gets a stable UID (e.g. `comp-42`) that
interaction tools use to address it.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `windowIndex` | number | no | Index of the window to snapshot (from `list_windows`); defaults to the active window |
| `filter` | string | no | State filter to reduce snapshot size: `ALL` (default), `VISIBLE_ONLY`, `ENABLED_ONLY`, `FOCUSABLE_ONLY` |
| `uid` | string | no | Snapshot only the subtree rooted at this component |
| `maxDepth` | number | no | Maximum depth below the root to descend (default unlimited); `1` returns the root and its direct children |
| `maxNodes` | number | no | Cap on the number of component nodes returned (default 2000). Prevents a huge UI from overflowing the client's context window |

**Returns:** a tree of components with, per node: UID, component class, name,
text/label, tooltip (when it differs from the text), and key state flags. When
the tree was cut short by `maxNodes` or `maxDepth`, the root carries
`"truncated": true`, and every node whose children were cut off by `maxDepth`
carries `omittedChildren` (the number of direct children left out).

**Exploring a large window:** start with a small `maxDepth` (e.g. 3), then call
`take_snapshot` again with the `uid` of a node that reports `omittedChildren`
to expand just that part of the tree. `find_component` is the shortcut when you
already know a text, name, tooltip, or class.
text/label, and key state flags. Components of anonymous classes are reported
by their first named superclass, e.g. `JTabbedPane (anonymous)`. When the tree was cut short by `maxNodes` or
`maxDepth`, the root carries `"truncated": true` — narrow the request (a
`filter`, a smaller `maxDepth`, or `find_component`) to see the rest.

**Notes:**
- Take a fresh snapshot after any action that changes the UI; UIDs from stale
  snapshots may no longer resolve.
- With a filter, containers that do not match are still included when they
  have matching descendants, so the tree structure is preserved.
- UIDs are prefixed by the toolkit that owns the component: `comp-` for Swing,
  `fx-` for JavaFX. In a JavaFX snapshot, a `Control` is reported as a single
  node with its skin sub-graph omitted; content-bearing containers —
  `ScrollPane`, `TabPane`, `SplitPane`, `TitledPane`, `Accordion`, `ToolBar`,
  `ButtonBar` — are traversed as normal.

## `get_component_details`

Get detailed information about a single component.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID from a snapshot |

**Returns:** component class, text, tooltip, bounds, enabled / visible /
focusable flags, selection state, and accessibility metadata.

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

**Notes:**
- `CLASS` matches the component's class and its superclasses (up to
  `JComponent` / `Control`), so `JTable` also finds `JTable` subclasses and an
  anonymous `new JTabbedPane() { ... }` is found by `TabbedPane`. An anonymous
  class also answers to its name without the package (e.g. `MainFrame$1`), and a
  query containing a dot is compared against the fully qualified class name.

## `get_table_data`

Extract the model contents of a `JTable` (Swing) or `TableView` (JavaFX) as
structured data.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | `JTable` (Swing) or `TableView` (JavaFX) component UID |
| `startRow` | number | no | Zero-based first row (inclusive) |
| `endRow` | number | no | Zero-based last row (inclusive) |

**Returns:** `rowCount`, `columns` (column names), and `rows` (cell values).
On JavaFX, a call without `endRow` returns at most 200 rows from `startRow` and
sets `truncated: true` when more rows follow.

## `get_list_items`

Extract the items of a `JList`/`ListView` or the visible rows of a
`JTree`/`TreeView` as structured data.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | `JList`/`JTree` (Swing) or `ListView`/`TreeView` (JavaFX) component UID |
| `startIndex` | number | no | Zero-based first item (inclusive) |
| `endIndex` | number | no | Zero-based last item (inclusive) |

**Returns:** `itemCount` and `items`. For a `JTree`/`TreeView`, items are the
visible rows rendered as ` > `-separated paths.
