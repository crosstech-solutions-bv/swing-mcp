---
name: swing-mcp
description: Uses Swing MCP for efficient debugging, troubleshooting and automation of Java Swing and JavaFX applications. Use when inspecting a Swing or JavaFX UI, automating UI interactions, extracting data from tables/lists/trees, or verifying desktop application behavior.
---

## Core Concepts

**Application lifecycle**: Tools operate on a session that connects the MCP server to the swing-mcp agent inside the target JVM. Start a session with `launch_app` (starts a new JVM with the agent preloaded via `-javaagent`) or `attach_to_app` (loads the agent into an already-running Java desktop JVM by PID). End it with `stop_app` — a launched application is terminated, an attached one is only disconnected.

**Session selection**: Tools operate on the currently active session. Use `list_sessions` to see open sessions, then `select_session` to switch. Check connectivity with `app_status`.

**Window selection**: Inspection and interaction tools operate on the active window. Use `list_windows` to see visible windows, then `select_window` to switch context (this also brings the window to front).

**Toolkits**: Swing and JavaFX are both supported through the same tools — never ask the user which one they are on, just take a snapshot. Uids say which toolkit owns a component: `comp-42` is Swing, `fx-7` is JavaFX. An application can have both at once (a `JFXPanel` inside a Swing frame); pass a uid back exactly as given and it is routed correctly. `evaluate_java` and `drag` are Swing-only and will say so on JavaFX.

**Component interaction**: Use `take_snapshot` to get the UI component tree with component `uid`s (e.g. `comp-42` or `fx-7`). Each component has a stable UID used by interaction tools such as `click` and `fill`. If a component isn't found, take a fresh snapshot — UIDs from an older snapshot may be stale after the UI changed.

**UI thread**: Every command is executed on the owning toolkit's UI thread inside the target JVM — the Event Dispatch Thread for Swing, the JavaFX Application Thread for JavaFX — so interactions behave like real user actions and respect each toolkit's threading rules.

**JavaFX trees are pre-filtered**: a JavaFX `Control` is reported as a single node. Its skin sub-graph (`LabeledText`, caret and selection `Path` nodes, and similar) is deliberately omitted, because those are rendering details, not things a user can act on. Read a control's value from its snapshot entry or `get_component_details`, not by looking for child nodes.

## Workflow Patterns

### Before interacting with an application

1. Connect: `launch_app` or `attach_to_app`
2. Snapshot: `take_snapshot` to understand the component tree and collect UIDs
3. Interact: Use component `uid`s from the snapshot for `click`, `fill`, `select_option`, etc.
4. Wait: `wait_for` to synchronise on UI state changes (`COMPONENT_TEXT`, `COMPONENT_VISIBLE`, `WINDOW_TITLE`, `EDT_IDLE`, …) instead of sleeping
5. Re-snapshot: take a fresh `take_snapshot` after any action that changes the UI

### Efficient data retrieval

- Use `find_component` (by `TEXT`, `NAME`, `TOOLTIP`, `CLASS`, or `ANY`) to locate a component without paying for a full snapshot
- Use `take_snapshot` with `filter` (`VISIBLE_ONLY`, `ENABLED_ONLY`, `FOCUSABLE_ONLY`) to reduce output size
- Use `get_table_data` / `get_list_items` with `startRow`/`endRow` (or `startIndex`/`endIndex`) ranges instead of dumping entire models
- Use `get_component_details` for the full state of a single component instead of re-snapshotting the whole tree
- When an action has no visible effect, check `get_app_output` (launched apps) for an exception the UI swallowed; pass the last `toLine` as `sinceLine` to read only what is new

### Tool selection

- **Automation/inspection**: `take_snapshot` (text-based component tree, faster, better for automation)
- **Visual inspection**: `take_screenshot` (whole window, or a single component via `uid`); the PNG is saved to disk and the path returned — only set `returnImage: true` when the image must be inline; the default `PAINT` mode renders the UI even when other windows cover it (use `mode: SCREEN` only for native title bars or heavyweight popups)
- **Structured data**: `get_table_data` for `JTable`/`TableView`, `get_list_items` for `JList`/`JTree` or `ListView`/`TreeView`
- **Keyboard**: `press_key` and `type_text` send key events to the application itself, so they work while it is in the background; pass `uid` to target a component directly instead of calling `focus` first. Only use `robot: true` when the application needs OS-level input
- **Text entry**: `fill` sets a value directly (text components, `JSpinner`, editable `JComboBox` in Swing; `TextInputControl`, `Spinner`, editable `ComboBox` in JavaFX); `type_text` types character-by-character with key events when listeners/validation must fire
- **Selection**: `select_option` for `JList`/`JComboBox`/`JTabbedPane` (or `ListView`/`ComboBox`/`ChoiceBox`/`TabPane` in JavaFX), `select_tree_node` for `JTree`/`TreeView` paths, `select_table_cell` for `JTable`/`TableView` (`toggle`/`extend`/`rows` for multi-row selections), `select_menu_item` / `select_context_menu_item` for menus (`JMenuBar`/`JPopupMenu` in Swing, `MenuBar`/`ContextMenu` in JavaFX)
- **Additional details**: `evaluate_java` (JShell in the target JVM) for state not exposed by the other tools — disabled by default, requires `swing.mcp.evaluate.enabled=true`
- `drag` and `evaluate_java` are Swing-only

### Handling dialogs

Modal dialogs block interaction with the active window. Use `list_dialogs` to see open dialogs (type, title, message, buttons), then `handle_dialog` to click a button by text or pick a file in a `JFileChooser`.

### Parallel execution

You can send multiple tool calls in parallel, but maintain correct order: connect → snapshot → interact → wait.

## Troubleshooting

If tool calls fail or the session won't connect, use the [troubleshooting skill](../troubleshooting/SKILL.md) and refer to the documentation:

- [Tool reference](../../docs/tools/README.md)
- [Installation and troubleshooting guide](../../docs/installation.md)
