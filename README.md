# swing-mcp

<!-- mcp-name: io.github.crosstech-solutions-bv/swing-mcp -->

[![CI](https://github.com/crosstech-solutions-bv/swing-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/crosstech-solutions-bv/swing-mcp/actions/workflows/ci.yml)
[![MCP Registry](https://img.shields.io/badge/MCP%20Registry-io.github.crosstech--solutions--bv%2Fswing--mcp-38a9dc)](https://registry.modelcontextprotocol.io)

**Let AI assistants operate Java desktop applications — Swing and JavaFX** — snapshot the UI, click, type, fill forms, read tables and trees, drive menus and dialogs. Inspired by [chrome-devtools-mcp](https://github.com/ChromeDevTools/chrome-devtools-mcp), but targeting a running Java UI instead of HTML pages.

One set of tools drives either toolkit, and both at once in applications that mix them — you never tell it which you are using.

A huge amount of business software is Java desktop software — internal tools, ERP clients, point-of-sale, lab and logistics systems — with no API and no web UI, written in Swing and increasingly in JavaFX. Swing MCP gives that software a safe, permissioned door into the AI era: assistants like Claude can see the interface and act in it, without changing the application itself.

![swing-mcp driving the demo app: snapshots, clicks, form filling, tables, trees, menus, and dialogs](docs/demo.gif)

## Quick start (Claude Desktop)

1. Make sure **JDK 21+** is on your `PATH`.
2. Download `swing-mcp.mcpb` from the [latest release](https://github.com/crosstech-solutions-bv/swing-mcp/releases).
3. Open the file — Claude Desktop installs it as an extension.
4. Ask Claude to `launch_app` your application (or `attach_to_app` a running one by PID) and take it from there.

Using another MCP client? One guide and a one-minute video per client — Claude Code, VS Code + Copilot, Cursor, Devin Desktop (Windsurf), IntelliJ IDEA, Codex CLI, Gemini CLI: [docs/installation.md](docs/installation.md) · [videos](https://crosstech.solutions/swing-mcp#clients). Prefer the long version? [3-minute install-and-first-use video](https://crosstech.solutions/swing-mcp#video). JavaFX app? [See it driven live](https://crosstech.solutions/swing-mcp#javafx) (96 seconds, every tool call from the real MCP log).

**Need this connected to your own application** — or an MCP connector for other software your business runs? CrossTech builds them: [crosstech.solutions/swing-mcp](https://crosstech.solutions/swing-mcp).

## Modules

- `swing-mcp-server` — Spring Boot MCP server (stdio transport) exposing the automation tools.
- `swing-mcp-agent` — Java agent loaded into the target JVM (at launch via `-javaagent`, or dynamically by PID). Runs a localhost-only JSON line-protocol socket server. Contains one `UiToolkit` implementation per supported toolkit and routes each command to the right one; see [docs/adr/0001-multi-toolkit-agent.md](docs/adr/0001-multi-toolkit-agent.md).
- `swing-mcp-common` — Shared command/DTO types and the `UiToolkit` interface.
- `swing-mcp-demo` — Demo Swing application used for integration testing.
- `swing-mcp-demo-fx` — Demo JavaFX application used for integration testing (driven both through the toolkit and through the real server over stdio).

## How it works

```
MCP client (stdio) ──▶ swing-mcp-server ──localhost socket──▶ swing-mcp-agent (inside target JVM) ──▶ Swing EDT
                                                                                         └──▶ JavaFX Application Thread
```

1. The MCP client calls `launch_app` (starts a JVM with the agent preloaded) or `attach_to_app` (loads the agent into a running JVM by PID).
2. The agent binds a loopback-only port in `swing.mcp.agent-port-min..max` and reports it back through a response file.
3. Tools such as `take_snapshot`, `click`, and `fill` are forwarded as JSON line commands and executed on the owning toolkit's UI thread — the Event Dispatch Thread for Swing, the JavaFX Application Thread for JavaFX.
4. Component uids are prefixed by the toolkit that issued them (`comp-` for Swing, `fx-` for JavaFX), which is how a mixed application stays unambiguous.
5. Every tool carries MCP annotations (`title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`), so clients that honour them can wave through the 12 read-only tools and flag `stop_app`, `close_window` and `evaluate_java` before running them.

See [docs/tools](docs/tools/README.md) for the full tool documentation (per-category pages), or [docs/tool-reference.md](docs/tool-reference.md) for the single-page quick reference.

## Skills

The [skills](skills/README.md) directory contains agent skills (`SKILL.md` files) that teach AI coding agents how to use Swing MCP effectively — core workflows, UI testing patterns, and troubleshooting.

## Building

Requires JDK 21+ and Maven.

```bash
mvn verify
```

GUI integration tests are skipped in headless environments; CI runs them under `xvfb`. The JavaFX
integration tests run in the `verify` phase via failsafe, so `mvn test` alone will not exercise them.

## Running

Build everything (or unzip the released `swing-mcp.mcpb` — the two jars are in
its `server/` folder), then register the server with your MCP client (see
[docs/installation.md](docs/installation.md) for per-client instructions —
Claude Desktop, Claude Code, VS Code, Cursor, Devin Desktop, IntelliJ IDEA, Codex CLI, Gemini CLI):

```json
{
  "mcpServers": {
    "swing": {
      "command": "java",
      "args": ["-jar", "/path/to/swing-mcp-server.jar"],
      "env": {
        "SWING_MCP_AGENT_JAR": "/path/to/swing-mcp-agent.jar"
      }
    }
  }
}
```

Try it against the demo app:

1. `launch_app` with `java -jar swing-mcp-demo/target/swing-mcp-demo-1.4.0.jar`
   (or the JavaFX demo: `swing-mcp-demo-fx/target/swing-mcp-demo-fx-1.4.0.jar`)
2. `take_snapshot` to discover component UIDs
3. `click`, `fill`, `select_option`, … to interact

## MCP Registry

This server is published to the [MCP Registry](https://registry.modelcontextprotocol.io) as
`io.github.crosstech-solutions-bv/swing-mcp`, distributed as an MCPB bundle attached to GitHub releases.
See [docs/registry-publishing.md](docs/registry-publishing.md) for how publishing works.

## Security notes

- The agent listens on the loopback interface only.
- `evaluate_java` (arbitrary code execution in the target JVM) is disabled by default; enable with `swing.mcp.evaluate.enabled=true`.

See [SECURITY.md](SECURITY.md) for the full security model and how to report vulnerabilities.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the PR workflow. Release history lives in [CHANGELOG.md](CHANGELOG.md).

## About

Swing MCP is built and maintained by [CrossTech](https://crosstech.solutions), an AI-first software
studio. We build MCP connectors that plug businesses' existing software — web, cloud, and
legacy desktop — into AI assistants: [crosstech.solutions/mcp](https://crosstech.solutions/mcp).

## License

[MIT](LICENSE.md)
