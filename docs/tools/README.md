# Swing MCP — Tools Documentation

The Swing MCP server exposes automation tools over the MCP stdio transport.
Each tool call is translated into a JSON line command, forwarded over a
localhost-only socket to the agent inside the target JVM, and executed on the
owning toolkit's UI thread — the Swing Event Dispatch Thread (EDT), or the JavaFX Application Thread.

For instructions on registering the server with your MCP client (IntelliJ
IDEA, VS Code, Claude Desktop, Claude Code, Cursor, Windsurf), see the
[installation guide](../installation.md).

Component `uid` values (e.g. `comp-42`, or `fx-7` for JavaFX nodes) come from
`take_snapshot`. Take a fresh snapshot after any action that changes the UI,
as UIDs from an older snapshot may be stale.

## Tool categories

| Category | Documentation | Tools |
|---|---|---|
| Application lifecycle | [application.md](application.md) | `launch_app`, `attach_to_app`, `stop_app`, `app_status`, `get_app_output`, `list_sessions`, `select_session` |
| Inspection | [inspection.md](inspection.md) | `take_snapshot`, `get_component_details`, `find_component`, `get_table_data`, `get_list_items` |
| Windows | [windows.md](windows.md) | `list_windows`, `select_window`, `resize_window`, `move_window`, `maximize_window`, `minimize_window`, `restore_window`, `close_window` |
| Interaction | [interaction.md](interaction.md) | `click`, `hover`, `focus`, `type_text`, `fill`, `select_option`, `select_tree_node`, `select_table_cell`, `select_menu_item`, `select_context_menu_item`, `press_key`, `drag`, `scroll`, `mouse_wheel` |
| Dialogs | [dialogs.md](dialogs.md) | `list_dialogs`, `handle_dialog` |
| Clipboard | [clipboard.md](clipboard.md) | `get_clipboard`, `set_clipboard` |
| Screenshots | [screenshots.md](screenshots.md) | `take_screenshot` |
| Utilities | [utilities.md](utilities.md) | `wait_for`, `evaluate_java` |

## Status overview

| Tool | Status |
|---|---|
| `launch_app` | ✅ Implemented |
| `attach_to_app` | ✅ Implemented |
| `stop_app` | ✅ Implemented |
| `app_status` | ✅ Implemented |
| `get_app_output` | ✅ Implemented |
| `list_sessions` | ✅ Implemented |
| `select_session` | ✅ Implemented |
| `take_snapshot` | ✅ Implemented |
| `get_component_details` | ✅ Implemented |
| `find_component` | ✅ Implemented |
| `get_table_data` | ✅ Implemented |
| `get_list_items` | ✅ Implemented |
| `list_windows` | ✅ Implemented |
| `select_window` | ✅ Implemented |
| `resize_window` | ✅ Implemented |
| `move_window` | ✅ Implemented |
| `maximize_window` | ✅ Implemented |
| `minimize_window` | ✅ Implemented |
| `restore_window` | ✅ Implemented |
| `close_window` | ✅ Implemented |
| `click` | ✅ Implemented |
| `hover` | ✅ Implemented |
| `focus` | ✅ Implemented |
| `type_text` | ✅ Implemented |
| `fill` | ✅ Implemented |
| `select_option` | ✅ Implemented |
| `select_tree_node` | ✅ Implemented |
| `select_table_cell` | ✅ Implemented |
| `select_menu_item` | ✅ Implemented |
| `select_context_menu_item` | ✅ Implemented |
| `list_dialogs` | ✅ Implemented |
| `handle_dialog` | ✅ Implemented |
| `get_clipboard` | ✅ Implemented |
| `set_clipboard` | ✅ Implemented |
| `press_key` | ✅ Implemented |
| `drag` | ✅ Implemented |
| `scroll` | ✅ Implemented |
| `mouse_wheel` | ✅ Implemented |
| `take_screenshot` | ✅ Implemented |
| `wait_for` | ✅ Implemented |
| `evaluate_java` | ✅ Implemented (disabled by default) |

All previously planned capabilities have been implemented — see the
[roadmap](roadmap.md) for the mapping.

## Tool annotations

Every tool carries an MCP `title` and the `readOnlyHint`, `destructiveHint`,
`idempotentHint`, and `openWorldHint` annotations from the MCP spec. Three
tools are marked destructive: `stop_app`, `close_window`, and
`evaluate_java`. 13 tools are read-only — clients that honour these hints
(Claude Desktop, VS Code, Cursor) can skip per-call approval for them.
`openWorldHint` is `false` for every tool, since the server only ever touches
the local JVM.

## Typical workflow

1. `launch_app` (or `attach_to_app` for a running JVM) to start a session.
2. `take_snapshot` to discover component UIDs.
3. Interact: `click`, `fill`, `select_option`, `press_key`, …
4. `wait_for` to synchronise on UI state changes.
5. `take_screenshot` to visually verify results.
6. `stop_app` to end the session.

## Server configuration

Properties under the `swing.mcp` prefix (see the server's `application.yml`):

| Property | Default | Description |
|---|---|---|
| `agent-port-min` / `agent-port-max` | 40000 / 40100 | Port range the agent binds within |
| `agent-jar` | `${SWING_MCP_AGENT_JAR:}` | Path to the swing-mcp-agent shaded jar |
| `screenshot-dir` | `${java.io.tmpdir}/swing-mcp-screenshots` | Screenshot output directory |
| `tool-timeout-ms` | 30000 | Agent command round-trip timeout |
| `evaluate.enabled` | `false` | Allow `evaluate_java` |
