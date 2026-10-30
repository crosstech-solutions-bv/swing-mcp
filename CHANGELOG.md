# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Dependencies: Spring Boot 4.1.2, Spring AI 2.0.2, Lombok 1.18.48, JavaFX 21.0.13. Jackson,
  JUnit and Mockito versions now come from the Spring Boot BOM; the agent jar previously
  shaded `jackson-databind` 2.18 with `jackson-core` 2.21. Maven plugin versions are all
  pinned, and the CI actions are updated.
- `take_screenshot` renders the component into an image on the Event Dispatch Thread by
  default (`mode: PAINT`) instead of copying its screen region. Windows or notifications
  covering the application no longer end up in the image, background windows and
  components that are not on screen can be captured, and it works headless. A window is
  rendered through its root pane, so the native title bar is not included. The previous
  behaviour is available as `mode: SCREEN`. Either way, events already queued on the UI
  thread are processed first, so the image no longer lags one repaint behind the last
  action ([#24](https://github.com/crosstech-solutions-bv/swing-mcp/issues/24)).
- Swing `press_key` and `type_text` deliver key events straight to the application instead of
  using `java.awt.Robot`, which sends OS-level keystrokes to whatever application is in front.
  When the target application was in the background (the normal case when an agent drives it
  from an editor) keys went to the other application, in the worst case into the user's
  editor. Keys now go to `uid` (new optional parameter on `press_key`) or to the component
  with keyboard focus in the application, even while it is inactive, and never to another
  application. Focus traversal, key bindings, menu accelerators and default buttons behave as
  for real input, `type_text` can type any character regardless of keyboard layout, and the
  result names the component that received the keys. A key that opens a modal dialog returns
  a pending result instead of blocking
  ([#23](https://github.com/crosstech-solutions-bv/swing-mcp/issues/23)).
- `type_text` with `uid` no longer brings the window to the front.
- Swing `click` (other than a plain left click on a button), `hover` and `drag` are delivered
  as mouse events to the component under the point instead of moving the real mouse. They
  work while the window is covered or in the background, do not move the user's cursor, and
  work headless. A click at a point on a container reaches the child under it, a right click
  is flagged as the popup trigger, and hovering another component sends mouse-exited to the
  previous one. A right click on a button is now a right click rather than `doClick()`.
  Native drag-and-drop between components still needs `robot: true`
  ([#19](https://github.com/crosstech-solutions-bv/swing-mcp/issues/19)).
- `drag` is routed by `fromUid`, so in an application with both toolkits it reaches the
  toolkit that owns the source component.
- `scroll` on a component with no enclosing scroll pane sends mouse-wheel
  notches instead of failing, so custom scrollers (e.g. JUNG's
  `GraphZoomScrollPane`) and JavaFX `ListView`/`TableView`/`TreeView` scroll.
- JavaFX `scroll` without `amount` moves 3 units, the documented default and the
  Swing one, instead of 1.

### Fixed
- JavaFX `get_table_data` and `get_list_items` treated `endRow`/`endIndex` as exclusive, so
  every range came back one row short (`startRow: 0, endRow: 0` returned nothing). They are
  inclusive, as documented and as on Swing.
- JavaFX `select_tree_node` split the path on `/` instead of ` > `, so no multi-level path
  could be selected through the tool.
- JavaFX `close_window` called `Stage.close()`, which skips the window's close-request
  handlers: "save changes?" prompts and vetoes never ran, and closing the last window quit
  the application. It now fires the close request like a real close; a handler that opens
  a dialog gives a pending result, and a vetoed close is reported.
- JavaFX uids became permanently unknown after a node was detached and attached again
  (a cached view swapped back into a layout). The old uid now resolves again, and menu
  items no longer pin memory.
- JavaFX `press_key` rejected names the tool documents (`SHORTCUT`, `ESC`, `RETURN`,
  `PGUP`, `DEL`, single characters) and never sent the typed event, so a letter typed
  nothing into a text field. It accepts the same names as Swing and types characters.
- JavaFX `type_text` blocked when a key handler opened a modal dialog; it returns a pending
  result instead.
- JavaFX `handle_dialog` acted on the oldest open dialog; it now acts on the topmost one,
  so with nested dialogs the inner one is answered.
- A failed `launch_app` (agent never connected, or the application hung before the agent
  started) left the application running with no session to stop it. The process tree is
  now stopped and the temporary files removed. An agent that cannot bind a port reports
  that at once instead of the server waiting 30 s.
- `stop_app` stopped only the direct child, so a wrapper such as `xvfb-run java …` left the
  JVM running. Processes started by the application are stopped too, and the captured
  output file is deleted.
- Each `attach_to_app` started another agent server in the target JVM and none was ever
  stopped, so repeated attach/stop cycles used up the agent port range. Closing an attached
  session now shuts the agent's listener down (new `SHUTDOWN` agent command); the
  application keeps running.
- `wait_for` with `timeoutMs` above the 30 s tool timeout always failed with a misleading
  transport timeout. The read deadline now follows `timeoutMs`, and a zero or negative
  `swing.mcp.tool-timeout-ms` is rejected at startup.
- A response cut off by a read timeout made the next, unrelated command report a transport
  failure although it ran. The leftover is now skipped.
- An unknown session id was reported as "No active Swing application session"; the error
  now names the id and lists the active sessions.
- The process output shown when a launch fails was empty when the output was not valid
  UTF-8 (a localized JVM error on Windows).
- Services now reject a missing required parameter with its name instead of a bare
  `NullPointerException`, and `PING` no longer scans the windows of every UI thread.
- `take_screenshot` on a JavaFX window saved a broken file: the JavaFX toolkit returned the
  image under keys the server did not read (`base64`, `format`), so the server decoded the
  string `"null"`. It now returns `imageBase64` and `mimeType` like the Swing toolkit, and
  the server rejects a result without image data instead of writing garbage.
- `launch_app` inserted the `-javaagent` argument after the first token of the command, so a
  wrapper in front of `java` broke the launch (`env VAR=value java -jar app.jar` failed with
  `env: illegal option -- j`). The argument now goes right after the java launcher (`java`,
  `javaw`, with or without a path or `.exe`). A command with no java launcher is now
  rejected before anything is started; the argument used to be inserted after its first
  token, which ran the command without the agent and then waited 30 s for it
  ([#25](https://github.com/crosstech-solutions-bv/swing-mcp/issues/25)).
- Components of anonymous classes (`new JTabbedPane() { ... }`) could not be found with
  `find_component` by `CLASS` and were reported with an empty `componentClass` in
  snapshots, because both relied on `Class.getSimpleName()`. Class matching now covers the
  component's superclasses up to the toolkit base class (so `TabbedPane` finds an anonymous
  `JTabbedPane`, and `JTable` finds `JTable` subclasses), an anonymous class's own name
  (`MainFrame$1`), and, for queries containing a dot, the fully qualified name. Snapshots,
  `find_component`, `list_windows` and `list_dialogs` report anonymous classes by their first
  named superclass, e.g. `JTabbedPane (anonymous)`. Applies to Swing and JavaFX
  ([#20](https://github.com/crosstech-solutions-bv/swing-mcp/issues/20)).
- `fill` on a numeric `JSpinner` committed a `Double` regardless of the model, so an
  integer spinner suddenly held a `Double` and application code such as
  `(Integer) spinner.getValue()` threw a `ClassCastException`. The text is now converted
  to the class of the model's current value (`Integer`, `Long`, `Short`, `Byte`, `Float`,
  `Double`, `BigInteger`, `BigDecimal`), and fractions for integer models or values
  outside the model's bounds are rejected with a clear error, as the spinner's own editor
  would ([#22](https://github.com/crosstech-solutions-bv/swing-mcp/issues/22)).
- `press_key` silently pressed the first letter of a key name it did not know (`HOME` pressed
  `H`, `F5` pressed `F`); unknown names are now rejected with the accepted forms.
- JavaFX `click` on a node without `fire()` ignored `button` and `clickType` and passed the
  node-local center as scene coordinates, so handlers saw the wrong position for any node
  not at the scene origin. Clicks and hovers now carry the right coordinates, button, click
  count and modifiers.
- JavaFX snapshots ignored `filter: VISIBLE_ONLY`, because the toolkit compared the filter
  against `VISIBLE` while the server sends `VISIBLE_ONLY`.
- In JavaFX snapshots `maxDepth` cut plain containers one level earlier than controls and
  the Swing scanner; `maxDepth: 1` now returns the root and its direct children everywhere.

### Added
- `get_app_output` tool: read the captured stdout/stderr of an application started with
  `launch_app`, either the last `tail` lines or the lines after `sinceLine` to follow new
  output. Stack traces and log lines of a running application, often the only trace of an
  error the UI swallows, were previously only shown (last lines) when a launch failed, and
  an agent had to guess the log file's location in the temp directory. Read-only; at most
  500 lines and about 40,000 characters per call. The tool count is now 40
  ([#26](https://github.com/crosstech-solutions-bv/swing-mcp/issues/26)).
- `launch_app`, `app_status` and `list_sessions` report `outputFile` for launched
  applications.
- `launch_app` takes an optional `env` object of environment variables for the launched
  process, for variables an application's own launcher would normally set (native library
  or plugin paths). Previously the only way was to set them in the MCP server's own
  environment, which needs a server restart and applies to every launch
  ([#25](https://github.com/crosstech-solutions-bv/swing-mcp/issues/25)).
  - `select_table_cell` can build a multi-selection: `toggle` adds or removes a row
  (Ctrl/Cmd-click), `extend` selects the range from the anchor (Shift-click), and `rows`
  sets several selected rows in one call (`[]` clears the selection). The result now
  reports the resulting selection. `row` and `col` are optional (`col` defaults to 0), and
  out-of-range indices are rejected instead of being passed to the table. Works for
  `JTable` and `TableView`; the JavaFX demo table now allows multiple selection
  ([#18](https://github.com/crosstech-solutions-bv/swing-mcp/issues/18)).
- `robot: true` on `press_key` and `type_text` keeps OS-level keystrokes for applications that
  need them; the window is brought to the front first (activating the application on macOS),
  and the call fails rather than typing into another application if that does not work.
- `press_key` accepts any `KeyEvent.VK_*` name (`F5`, `HOME`, `END`, `PAGE_UP`, `INSERT`, …),
  `SHORTCUT` (Ctrl, or Cmd on macOS), and `CMD`/`OPTION` aliases. JavaFX `press_key` and
  `type_text` accept `uid` too.
- `click` and `hover` take `x`/`y` (offset from the component's top-left corner, default
  its center), and `click` takes `modifiers` (`CTRL`, `SHIFT`, `ALT`, `META`, `SHORTCUT`,
  combined with `+`). Content drawn inside one component (chart points, canvas items,
  handles) can now be reached, and Ctrl/Shift-clicks are possible. `drag` takes
  `fromX`/`fromY`/`toX`/`toY`, and `toUid` is optional, for drags inside one component
  ([#19](https://github.com/crosstech-solutions-bv/swing-mcp/issues/19)).
- `robot: true` on `click`, `hover` and `drag` moves the real OS mouse with
  `java.awt.Robot`, the previous behaviour; `drag` with `robot` now moves in steps so native
  drag-and-drop passes its gesture threshold.
- `take_snapshot` takes `uid` (snapshot only the subtree below a component), `maxDepth`
  and `maxNodes`. The agent already honoured the two limits, but the tool did not expose
  them, so a large window could only be fetched whole (80,000+ characters even with
  `VISIBLE_ONLY`). Nodes whose children were cut off by `maxDepth` now report
  `omittedChildren`, and the root reports `truncated` for depth cuts too, so a client can
  expand exactly the part it needs
  ([#21](https://github.com/crosstech-solutions-bv/swing-mcp/issues/21)).
- Snapshot nodes, `find_component` results and `get_component_details` include the
  component's `tooltip` (HTML reduced to plain text, omitted when equal to the text);
  for icon-only buttons it is often the only label.
- **`mouse_wheel` tool**: turns the mouse wheel over a component at `x`/`y`
  (default its center), with optional `modifiers` (`CTRL`, `SHIFT`, `ALT`, `META`,
  `SHORTCUT`), so graph viewers, charts and maps that zoom or pan on the wheel can
  be driven. The events go straight to the application, like `click`'s, so the
  window may be covered or in the background; `robot: true` (Swing) turns the real
  OS wheel instead. A wheel that opens a modal dialog returns a pending result.
  41 tools in total now.
- A "Canvas" tab in both demo apps that zooms and pans on the wheel.

## [1.3.0] - 2026-09-14

### Added
- **JavaFX support.** The agent can now drive JavaFX applications as well as Swing
  ones, through exactly the same 39 tools — a client never names a toolkit. Applications
  that mix both in one JVM (a `JFXPanel` inside a Swing frame) work too: component
  uids are prefixed by the toolkit that issued them (`comp-` for Swing, `fx-` for
  JavaFX) and each command is routed to the toolkit that owns the uid it carries.
- **Tool annotations and titles** on all 39 tools (`readOnlyHint`, `destructiveHint`,
  `idempotentHint`, `openWorldHint`, `title`). Clients that honour the hints — Claude
  Desktop, VS Code, Cursor — can skip the per-call approval prompt for the 12 read-only
  tools and flag the three whose own effect is irreversible (`stop_app`,
  `close_window`, `evaluate_java`). Tools migrated from Spring AI's generic `@Tool`
  to `@McpTool`; `McpToolConfig` removed in favour of the starter's annotation scanner.
- `UiToolkit` interface in `swing-mcp-common` and `ToolkitRegistry` in the agent,
  so a further toolkit means implementing one interface rather than touching
  dispatch. See `docs/adr/0001-multi-toolkit-agent.md`.
- `swing-mcp-demo-fx`: a JavaFX demo application mirroring the Swing one, plus 20
  integration tests that drive it through the toolkit and 8 that drive it through the
  real server over stdio (`ServerRoundTripIT`), so a server↔agent contract mismatch
  fails the build.
- `ToolAnnotationsTest` pins the read-only and destructive sets; `JavaFxAbsentTest`
  proves a Swing-only JVM never loads a `javafx.*` class.
- CI asserts the agent jar contains no JavaFX runtime classes.

### Changed
- Threading and the modal-dialog fire-and-poll protocol moved from `CommandHandler`
  into the toolkit, because the rules differ per toolkit. Swing behaviour is
  unchanged: `SwingToolkit` is a thin adapter over the existing `ComponentScanner`
  and adds no scanning logic of its own.
- In a JavaFX snapshot a `Control` is reported as a single node; its skin sub-graph
  (`LabeledText`, caret and selection `Path` nodes) is omitted as a rendering detail.
  Controls that hold real content — `ScrollPane`, `TabPane`, `SplitPane`,
  `TitledPane`, `Accordion`, `ToolBar`, `ButtonBar` — are traversed explicitly.
- Tool descriptions served over `tools/list`, the MCPB manifest, README, docs and
  skills now say Swing *and* JavaFX, so clients and models know the capability exists.
  `take_snapshot` explains the `comp-`/`fx-` prefixes; `evaluate_java` and `drag`
  are marked Swing-only.
- `tools/list` grew from 14.4 KB to 22.1 KB (roughly 3.8k → 5.8k tokens per request):
  4.5 KB is the annotations themselves, 2.1 KB is a `$schema` line the annotation
  scanner adds to every input schema. The latter is pure overhead and is a candidate
  for stripping in a follow-up.
- `additionalProperties: false` is no longer emitted on input schemas (scanner
  behaviour); property names, types, descriptions and `required` arrays are unchanged.
- Registry one-liner shortened to fit the MCP Registry's 100-character limit;
  `RegistryMetadataTest` enforces it and keeps `server.json` and `mcpb/manifest.json`
  in step.

### Notes
- JavaFX is an optional dependency: compiled against at `provided` scope and
  resolved reflectively, so no `javafx.*` class is loaded in a Swing-only
  application and the agent jar never bundles a JavaFX runtime.
- `evaluate_java` and `drag` remain Swing-only; on JavaFX they fail with a message
  saying so rather than a stack trace.
- Structured output (`outputSchema`) is not part of this release; every tool still
  returns a JSON string in a text block.

## [1.2.3] - 2026-09-08

### Fixed
- The MCP `serverInfo.version` reported to clients was hardcoded to `1.1.0`; it now
  follows the Maven project version (`application.yml` is filtered at build time).

### Changed
- `docs/installation.md`: unversioned jar paths, the unzip-the-`.mcpb` route for
  clients that need the jars, `mcpServers` config shape for IntelliJ IDEA (JetBrains
  AI Assistant), Claude Desktop extension install first, link to the install video.
- New per-client guides in `docs/install/` (Claude Desktop, Claude Code, VS Code +
  GitHub Copilot, Cursor, Devin Desktop/Windsurf, IntelliJ IDEA, OpenAI Codex CLI,
  Gemini CLI), each paired with a one-minute video on crosstech.solutions/swing-mcp.
## [1.2.2] - 2026-09-04

### Changed
- Registry/extension listing: benefit-first description, `websiteUrl` and icon in `server.json`; MCPB manifest now ships an icon, three screenshots, a markdown long description, license and richer keywords (`mcpb/assets/`, copied into the bundle by the publish workflow).

## [1.2.1] - 2026-09-01

### Fixed
- `publish` workflow compile break in `AgentServer.handleClient` (#13). This is the
  version actually published to the MCP Registry and attached to the GitHub release;
  the `V1.2.0` tag was never published. Functionally identical to 1.2.0 below.

## [1.2.0] - 2026-09-01

### Security
- **Agent socket now requires a per-session auth token.** The agent generates a
  random token at startup and hands it to the paired MCP server via the (now
  owner-only) port response file; every command must carry it or is rejected.
  Closes the hole where any local process could connect to the loopback socket
  and drive — or code-exec — the automated application.
- **`evaluate_java` is enforced in the agent, not only the server.** The
  `swing.mcp.evaluate.enabled` flag (default `false`) is passed to the agent and
  checked there, so arbitrary Java evaluation can no longer be triggered by
  talking to the socket directly. The `evaluate_java` JShell instance is now
  always closed (previously it leaked a forked JVM per call) and prefers
  in-process (`local`) execution.
- The token handshake file is the server's owner-only temp file (0600 /
  user-scoped ACL) written *in place* by the agent — never deleted and
  recreated with default permissions — and is removed as soon as it is read.
  The server requires both port and token before connecting, so a partial
  write can never yield an unauthenticated session.
- A connection that presents a bad token is closed on the first attempt, and
  the token is redacted from all agent log output.

### Fixed
- Applications launched via `launch_app` are now closed on server shutdown
  (`@PreDestroy`) instead of being orphaned on every stdio-MCP restart.
- `stop_app` forcibly terminates a process that ignores graceful shutdown
  (`destroyForcibly` fallback), so frozen/modal apps are actually stopped.
- Launch commands are tokenized with quote awareness, so paths and arguments
  containing spaces (e.g. `"/Users/My App/app.jar"`) no longer break.
- `select_option` by text now fails with the list of available options when
  nothing matches, instead of silently reporting success.
- `windowIndex` is now honored by `take_snapshot`, `close_window`, `resize_window`
  and friends (it was documented but silently ignored — `close_window(windowIndex=1)`
  could close the main frame). Out-of-range indices fail clearly.
- Clearer error when the target application exits mid-command (the session is
  reported dead with a hint to relaunch, rather than a generic transport error).
- Temporary port/output files are marked `deleteOnExit`; the port-file read
  tolerates partial writes instead of throwing a raw `NumberFormatException`.

### Added
- `take_snapshot` accepts `maxNodes` (default 2000) and `maxDepth` limits and
  returns `"truncated": true` when the tree was cut short — preventing a single
  snapshot of a large UI from blowing the AI client's context window.
- Project moved to the CrossTech organization: repository transferred to
  `github.com/crosstech-solutions-bv/swing-mcp` (old URLs redirect), Maven
  `groupId` changed from `io.github.tinusj` to `solutions.crosstech`, Java
  packages renamed `io.github.tinusj.swingmcp.*` → `solutions.crosstech.swingmcp.*`
  (breaking for code importing these classes), and the MCP registry name is now
  `io.github.crosstech-solutions-bv/swing-mcp`.
- Demo GIF in the README showing snapshots, clicks, form filling, tables, trees, menus, and dialogs.

### Removed
- Stray `test/current` directory (#10).

## [1.1.0] - 2026-07-06

### Added
- MCP Registry packaging: `server.json`, MCPB bundle, and a `publish-mcp` GitHub Actions workflow (#5).
- Comprehensive MCP Registry publishing guide under `docs/registry-publishing.md` (#6).

### Fixed
- Case-sensitive server name `io.github.crosstech-solutions-bv/swing-mcp` in registry metadata (#6).
- `server.json` name casing, description, and identifier URL (#7).

## [1.0.0] - 2026-07-05

Initial release.

### Added
- `swing-mcp-server`: Spring Boot MCP server (stdio transport) exposing Swing automation tools.
- `swing-mcp-agent`: Java agent loaded into the target Swing JVM (via `-javaagent` or dynamic attach by PID), running a loopback-only JSON line-protocol socket server that executes commands on the Swing EDT.
- `swing-mcp-common`: shared command/DTO types between server and agent.
- `swing-mcp-demo`: demo Swing application used for integration testing.
- Full tool set: inspection (`take_snapshot`, `find_component`, `get_component_details`, tables/lists/trees), interaction (`click`, `fill`, `select_option`, menus, drag, keyboard), dialogs, window management, clipboard, wait conditions, inline screenshots, and multi-session support (#3).
- Agent skills for AI coding agents: `swing-mcp`, `swing-ui-testing`, `troubleshooting` (#4).
- Documentation: per-category tool docs, single-page tool reference, per-client installation guide (IntelliJ IDEA, VS Code, Claude Desktop, Claude Code, Cursor, Windsurf) (#1, #2).
- CI with GUI integration tests under `xvfb`; restricted workflow permissions.

### Changed
- Logging migrated to `logback-spring.xml`; agent command handling updated for modal dialogs.

[Unreleased]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.3.0...HEAD
[1.3.0]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.2.3...V1.3.0
[1.2.3]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.2.2...V1.2.3
[1.2.2]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.2.1...V1.2.2
[1.2.1]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.2.0...V1.2.1
[1.2.0]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.1.0...V1.2.0
[1.1.0]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.0.0...V1.1.0
[1.0.0]: https://github.com/crosstech-solutions-bv/swing-mcp/releases/tag/V1.0.0
