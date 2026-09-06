# Swing MCP in Devin Desktop (formerly Windsurf)

Video: <https://crosstech.solutions/swing-mcp#windsurf> (about a minute)

Cognition renamed the Windsurf editor to **Devin Desktop** in 2026; the agent panel is still
called Cascade and the MCP config file path is unchanged.

## 1. Prerequisites

- **JDK 21 or newer** on your `PATH` (`java -version` to check).
- **The two jars.** Download `swing-mcp.mcpb` from the
  [latest release](https://github.com/crosstech-solutions-bv/swing-mcp/releases/latest);
  it is a plain zip — unpack it and the jars are in `server/`:

  ```bash
  unzip swing-mcp.mcpb -d swing-mcp
  # swing-mcp/server/swing-mcp-server.jar
  # swing-mcp/server/swing-mcp-agent.jar
  ```

  (Windows: `tar -xf swing-mcp.mcpb`, or rename to `.zip` and extract. Building from
  source with `mvn verify` works too.) Use **absolute paths** to both jars below; on
  Windows escape backslashes in JSON (`C:\\tools\\swing-mcp\\server\\swing-mcp-server.jar`).

## 2. Install

Open **Settings → Cascade → MCP Servers** (or the MCPs icon at the top right of the Cascade
panel) and edit the raw config, `~/.codeium/windsurf/mcp_config.json`:

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

Save and refresh the MCP list; `swing` shows with its tools, each of which can be toggled.
Cascade allows 100 active tools across all servers; Swing MCP uses 39.

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
