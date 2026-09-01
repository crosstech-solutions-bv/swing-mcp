---
name: troubleshooting
description: Uses Swing MCP and its documentation to troubleshoot server, agent, and session issues. Trigger this skill when launch_app or attach_to_app fail, when tools report no active session, or when the server initialization fails.
---

## Troubleshooting Wizard

You are acting as a troubleshooting wizard to help the user configure and fix their Swing MCP server setup. When this skill is triggered (e.g., because `launch_app` or `attach_to_app` failed, tools report that no session is connected, or the server wouldn't start), follow this step-by-step diagnostic process:

### Step 1: Find and Read Configuration

Your first action should be to locate and read the MCP configuration file. Search for the following files in the user's workspace: `.mcp.json`, `.vscode/mcp.json`, `.claude/settings.json`, `.cursor/mcp.json`, or the client's global MCP settings.

If you find a configuration file, read and interpret it to identify potential issues such as:

- A relative or wrong path to the `swing-mcp-server` jar.
- A missing or wrong `SWING_MCP_AGENT_JAR` environment variable (must be an absolute path to the shaded `swing-mcp-agent` jar).
- A `java` command that resolves to an old JDK.

If you cannot find any of these files, only then should you ask the user to provide their configuration file content.

### Step 2: Triage Common Errors

Before reading documentation or suggesting configuration changes, check if the failure matches one of the following common patterns.

#### Symptom: Server doesn't start / no tools listed

The server requires **JDK 21+**. Some GUI clients don't inherit the shell `PATH`.

1. Verify the Java version: `java -version` (must be 21 or newer).
2. If multiple JDKs are installed, use an absolute path to the JDK 21+ `java` binary in the MCP configuration instead of relying on `PATH`.
3. Remember that the server logs to `${java.io.tmpdir}/swing-mcp-server.log` (e.g. `/tmp/swing-mcp-server.log`) — console logging is disabled because stdout is reserved for the MCP stdio transport. Read this log for startup errors.

#### Error: `launch_app` / `attach_to_app` fails with a missing agent jar

The server needs the agent jar to preload (`launch_app`) or dynamically attach (`attach_to_app`) it.

1. Check that `SWING_MCP_AGENT_JAR` (or the `swing.mcp.agent-jar` property) is set in the MCP server configuration's `env` block.
2. Confirm the path is **absolute** and the file exists (e.g. `/path/to/swing-mcp/swing-mcp-agent/target/swing-mcp-agent-1.0.0.jar` after `mvn verify`).
3. Ask the user to restart the MCP server (or their AI client) after fixing the configuration.

#### Symptom: `attach_to_app` fails against a running JVM

1. Verify the PID belongs to a **Java** process running a Swing UI (`jps -l` lists candidate JVMs).
2. JVMs started with `-XX:+DisableAttachMechanism` cannot be attached to — relaunch the app via `launch_app` instead.
3. The target JVM and the MCP server should run as the same OS user; attaching across users fails.

#### Symptom: Session connects but tools time out or report no session

1. Call `app_status` and `list_sessions` — the session may have died (`alive: false`) or another session may be active; use `select_session` to switch.
2. The agent binds a **loopback-only** port in the `swing.mcp.agent-port-min..max` range (default 40000–40100). Check that a local firewall or sandbox does not block localhost connections in this range, or change the range.
3. Long-running UI operations may exceed the round-trip timeout — increase `swing.mcp.tool-timeout-ms` (default 30000).
4. If the target application blocks the Event Dispatch Thread (e.g. a long computation on the EDT), commands cannot execute; use `wait_for` with `EDT_IDLE` or fix the application.

#### Symptom: `evaluate_java` returns "disabled"

`evaluate_java` (arbitrary code execution in the target JVM) is **disabled by default** for security. Enable it explicitly with `swing.mcp.evaluate.enabled=true` in the server configuration, and only when the user understands the implications.

### Step 3: Read the Documentation

Read [docs/installation.md](../../docs/installation.md) (per-client setup and the Troubleshooting section) and [docs/tools/README.md](../../docs/tools/README.md) (server configuration properties) to map the error to a known issue.

### Step 4: Formulate a Configuration

Based on the exact error and the user's environment (OS, MCP client), formulate the correct MCP configuration snippet, for example:

```json
{
  "mcpServers": {
    "swing": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/swing-mcp-server-1.0.0.jar"],
      "env": {
        "SWING_MCP_AGENT_JAR": "/absolute/path/to/swing-mcp-agent-1.0.0.jar"
      }
    }
  }
}
```

After updating, the user must restart the MCP server (or their AI client) for the change to take effect.

### Step 5: Run Diagnostic Commands

If the issue is still unclear, run diagnostic commands to test the setup directly:

- `java -version` to verify the JDK is 21+.
- `java -jar /path/to/swing-mcp-server-*.jar` from a terminal to see startup errors directly.
- Inspect `${java.io.tmpdir}/swing-mcp-server.log` for stack traces.
- Test against the bundled demo app: `launch_app` with `java -jar /path/to/swing-mcp-demo-1.0.0.jar`, then `take_snapshot`.

### Step 6: Check GitHub for Existing Issues

If the documentation does not cover the specific error, check if the `gh` (GitHub CLI) tool is available in the environment. If so, search the repository for similar issues:
`gh issue list --repo crosstech-solutions-bv/swing-mcp --search "<error snippet>" --state all`

Alternatively, recommend that the user checks https://github.com/crosstech-solutions-bv/swing-mcp/issues for help.
