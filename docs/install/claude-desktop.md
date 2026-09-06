# Swing MCP in Claude Desktop

Video: <https://crosstech.solutions/swing-mcp#claude-desktop> (about a minute)

## 1. Prerequisites

- **JDK 21 or newer** on your `PATH` (`java -version` to check).
- `swing-mcp.mcpb` from the [latest release](https://github.com/crosstech-solutions-bv/swing-mcp/releases/latest).
  That is the whole install: the bundle carries the server, the agent and the manifest.

## 2. Install

1. Open `swing-mcp.mcpb` — double-click it, or drag it onto the Claude window.
2. Claude Desktop shows the extension (Swing MCP, CrossTech Solutions). Click **Install**.
3. It appears under **Settings → Extensions**, enabled. No config file to edit.

Manual alternative (if you prefer the config file): add this to
`claude_desktop_config.json` (macOS `~/Library/Application Support/Claude/`, Windows `%APPDATA%\Claude\`)
and restart Claude Desktop.

```json
{
  "mcpServers": {
    "swing": {
      "command": "java",
      "args": ["-jar", "/path/to/swing-mcp/server/swing-mcp-server.jar"],
      "env": {
        "SWING_MCP_AGENT_JAR": "/path/to/swing-mcp/server/swing-mcp-agent.jar"
      }
    }
  }
}
```

## 3. Verify

Start a new chat and ask *"Which Swing MCP tools do you have?"* — you should see `launch_app`,
`take_snapshot`, `click`, `fill`, `get_table_data`, … (39 tools). The tools icon under the
chat box lists them too.

## 4. First prompt

> Launch `/path/to/my-app.jar` with Swing MCP, take a snapshot and tell me what is on screen.

The assistant calls `launch_app`, then `take_snapshot`, and from there clicks, fills and reads
by component `uid`. To drive an application that is already running, give it the process id
instead: *"Attach to PID 12345 and read the table in the main window."* The tool list is in
[../tool-reference.md](../tool-reference.md); the workflow patterns the assistant should follow
are in the bundled [skill](../../skills/swing-mcp/SKILL.md).

## 5. If it does not connect

- `java` must be JDK 21+ **on the PATH the client sees**. GUI apps often do not inherit your
  shell PATH — use an absolute path to the `java` binary in `command` if needed.
- `SWING_MCP_AGENT_JAR` must be an absolute path to an existing file, otherwise
  `launch_app`/`attach_to_app` fail.
- The server logs to `${java.io.tmpdir}/swing-mcp-server.log` (e.g. `/tmp/swing-mcp-server.log`,
  `%TEMP%\swing-mcp-server.log`). Nothing is written to stdout — that is the MCP channel.
- More options (ports, screenshot folder, timeouts, enabling `evaluate_java`) in
  [../installation.md](../installation.md#configuration-options).
