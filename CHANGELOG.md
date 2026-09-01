# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **Project moved to the CrossTech organization**: repository transferred to
  `github.com/crosstech-solutions-bv/swing-mcp` (old URLs redirect), Maven
  `groupId` changed from `io.github.tinusj` to `solutions.crosstech`, Java
  packages renamed `io.github.tinusj.swingmcp.*` → `solutions.crosstech.swingmcp.*`
  (breaking for code importing these classes), and the MCP registry name is now
  `io.github.crosstech-solutions-bv/swing-mcp`.

### Added
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

[Unreleased]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.1.0...HEAD
[1.1.0]: https://github.com/crosstech-solutions-bv/swing-mcp/compare/V1.0.0...V1.1.0
[1.0.0]: https://github.com/crosstech-solutions-bv/swing-mcp/releases/tag/V1.0.0
