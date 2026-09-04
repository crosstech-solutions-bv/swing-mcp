# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.2.2...HEAD
[1.2.2]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.2.1...V1.2.2
[1.2.1]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.2.0...V1.2.1
[1.2.0]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.1.0...V1.2.0
[1.1.0]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.0.0...V1.1.0
[1.0.0]: https://github.com/crosstech-solutions-bv/swing-mcp/releases/tag/V1.0.0
