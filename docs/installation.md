# Installing the Swing MCP server in your MCP client

This guide shows how to register the Swing MCP server with different MCP
clients (Claude Desktop, Claude Code, IntelliJ IDEA, VS Code, Cursor,
Windsurf, …).

**Per-client guides (one page + one-minute video each):**

| Client | Guide | Video |
|---|---|---|
| Claude Desktop | [install/claude-desktop.md](install/claude-desktop.md) | [▶](https://crosstech.solutions/swing-mcp#claude-desktop) |
| Claude Code | [install/claude-code.md](install/claude-code.md) | [▶](https://crosstech.solutions/swing-mcp#claude-code) |
| VS Code + GitHub Copilot | [install/vscode-copilot.md](install/vscode-copilot.md) | [▶](https://crosstech.solutions/swing-mcp#vscode) |
| Cursor | [install/cursor.md](install/cursor.md) | [▶](https://crosstech.solutions/swing-mcp#cursor) |
| Devin Desktop (formerly Windsurf) | [install/windsurf.md](install/windsurf.md) | [▶](https://crosstech.solutions/swing-mcp#windsurf) |
| IntelliJ IDEA (JetBrains AI Assistant) | [install/intellij.md](install/intellij.md) | [▶](https://crosstech.solutions/swing-mcp#intellij) |
| OpenAI Codex CLI | [install/codex-cli.md](install/codex-cli.md) | [▶](https://crosstech.solutions/swing-mcp#codex) |
| Gemini CLI | [install/gemini-cli.md](install/gemini-cli.md) | [▶](https://crosstech.solutions/swing-mcp#gemini) |

The 3-minute overview video (Claude Desktop, Claude Code, IntelliJ IDEA, then driving an
app) is at <https://crosstech.solutions/swing-mcp#video>. The rest of this page is the
condensed reference for all clients.

## Prerequisites

1. **JDK 21+** on your `PATH` (the server and agent both require Java 21).
   Check with `java -version`.
2. **The two jars** — pick one:

   **A. Download (no build).** Get `swing-mcp.mcpb` from the
   [latest release](https://github.com/crosstech-solutions-bv/swing-mcp/releases/latest).
   The bundle is a plain zip; unpack it and the jars are in `server/`:

   ```bash
   unzip swing-mcp.mcpb -d swing-mcp
   # swing-mcp/server/swing-mcp-server.jar
   # swing-mcp/server/swing-mcp-agent.jar
   ```

   (On Windows: `tar -xf swing-mcp.mcpb`, or rename it to `.zip` and extract.)

   **B. Build from source.**

   ```bash
   mvn verify
   ```

   This produces `swing-mcp-server/target/swing-mcp-server-<version>.jar`
   (the MCP server, stdio transport) and
   `swing-mcp-agent/target/swing-mcp-agent-<version>.jar` (the Java agent
   injected into the target Swing JVM).

In all examples below, replace `/path/to/…` with the absolute paths to these
two jars on your machine. On Windows, use paths like
`C:\\path\\to\\swing-mcp-server.jar` (escaped backslashes in JSON).

## Common configuration

Every client uses the same underlying command:

| Setting | Value |
|---|---|
| Command | `java` |
| Arguments | `-jar /path/to/swing-mcp-server.jar` |
| Environment | `SWING_MCP_AGENT_JAR=/path/to/swing-mcp-agent.jar` |

The `SWING_MCP_AGENT_JAR` environment variable tells the server where to find
the agent jar so it can preload it (`launch_app`) or attach it dynamically
(`attach_to_app`).

## Claude Desktop

**Extension (recommended).** Open `swing-mcp.mcpb` (double-click it, or drag it
onto the Claude window). Claude Desktop installs it as an extension; it then
appears under **Settings → Extensions** and its tools are available in every
chat. No config file to edit.

**Manual alternative.** Edit the Claude Desktop configuration file:

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`

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

Restart Claude Desktop after saving.

## Claude Code (CLI)

```bash
claude mcp add swing \
  --env SWING_MCP_AGENT_JAR=/path/to/swing-mcp-agent.jar \
  -- java -jar /path/to/swing-mcp-server.jar
```

`claude mcp list` should then show `swing … ✓ Connected`. Add `-s user` to
make it available in every project instead of only the current one.

## IntelliJ IDEA (JetBrains AI Assistant)

1. Open **Settings → Tools → AI Assistant → Model Context Protocol (MCP)**.
2. Click **+** (Add), choose **STDIO**, and paste the configuration below
   (the same `mcpServers` shape Claude uses). Pick **Global** or **Project**
   level as you prefer.
3. **OK**, then **Apply**. The server appears in the list; click its status
   icon to see the available tools (`launch_app`, `take_snapshot`, `click`, …).

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

Start a new AI Assistant chat; the Swing tools are now offered to the model.

## VS Code (GitHub Copilot)

Add the server to your workspace's `.vscode/mcp.json` (or via
**Command Palette → MCP: Add Server**):

```json
{
  "servers": {
    "swing": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/swing-mcp-server.jar"],
      "env": {
        "SWING_MCP_AGENT_JAR": "/path/to/swing-mcp-agent.jar"
      }
    }
  }
}
```

To make the server available in all workspaces, add the same entry to your
user-level MCP configuration instead (**MCP: Open User Configuration**).

## Cursor

Add the server to `~/.cursor/mcp.json` (global) or `.cursor/mcp.json` in your
project (or via **Settings → MCP → Add new MCP server**):

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

## Devin Desktop (formerly Windsurf)

Add the same `mcpServers` entry as above to `~/.codeium/windsurf/mcp_config.json`
(**Settings → Cascade → MCP Servers**).

## OpenAI Codex CLI

```bash
codex mcp add swing --env SWING_MCP_AGENT_JAR=/path/to/swing-mcp-agent.jar -- java -jar /path/to/swing-mcp-server.jar
```

## Gemini CLI

```bash
gemini mcp add -s user swing java -e SWING_MCP_AGENT_JAR=/path/to/swing-mcp-agent.jar -- -jar /path/to/swing-mcp-server.jar
```

## Configuration options

The server is a Spring Boot application, so any property can be overridden on
the command line with `-D` system properties placed **before** `-jar`, or via
environment variables. Common options:

| Property | Default | Description |
|---|---|---|
| `swing.mcp.agent-jar` | `$SWING_MCP_AGENT_JAR` | Path to the agent jar |
| `swing.mcp.agent-port-min` / `agent-port-max` | `40000` / `40100` | Loopback port range the agent binds to |
| `swing.mcp.screenshot-dir` | `${java.io.tmpdir}/swing-mcp-screenshots` | Where `take_screenshot` writes images |
| `swing.mcp.tool-timeout-ms` | `30000` | Per-tool command timeout |
| `swing.mcp.evaluate.enabled` | `false` | Enable the `evaluate_java` tool (arbitrary code execution — keep disabled unless needed) |

Example enabling `evaluate_java` and a custom screenshot directory:

```json
{
  "mcpServers": {
    "swing": {
      "command": "java",
      "args": [
        "-Dswing.mcp.evaluate.enabled=true",
        "-Dswing.mcp.screenshot-dir=/tmp/screens",
        "-jar", "/path/to/swing-mcp-server.jar"
      ],
      "env": {
        "SWING_MCP_AGENT_JAR": "/path/to/swing-mcp-agent.jar"
      }
    }
  }
}
```

## Verifying the setup

1. Ask your client to list its MCP tools — you should see `launch_app`,
   `take_snapshot`, `click`, etc. (39 tools in total).
2. Try the demo app (built with `mvn verify`):
   - `launch_app` with command `java -jar /path/to/swing-mcp-demo-<version>.jar`
   - `take_snapshot` to discover component UIDs
   - `click` / `fill` to interact

The workflow is the same in every client: connect (`launch_app` or
`attach_to_app`) → `take_snapshot` → interact by `uid` → `wait_for` →
re-snapshot after the UI changes. See [tool-reference.md](tool-reference.md).

## Troubleshooting

- **Server doesn't start / no tools listed** — make sure `java` on the
  client's `PATH` is JDK 21+ (`java -version`). Some GUI clients don't inherit
  your shell `PATH`; use an absolute path to the `java` binary if needed.
- **`launch_app`/`attach_to_app` fails with a missing agent jar** — check that
  `SWING_MCP_AGENT_JAR` points to an existing file with an absolute path.
- **Logs** — the server logs to `${java.io.tmpdir}/swing-mcp-server.log`
  (e.g. `/tmp/swing-mcp-server.log`). Console logging is disabled because
  stdout is reserved for the MCP stdio transport.
