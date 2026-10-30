# Application lifecycle tools

Tools for starting, attaching to, and stopping Swing or JavaFX application sessions.
Multiple named sessions can be open concurrently; one of them is the *active*
session that all other tools operate on. Use `list_sessions` and
`select_session` to switch between applications.

## `launch_app`

Launch a Java desktop application (Swing or JavaFX) with the swing-mcp agent preloaded via `-javaagent`.
The agent binds a loopback-only socket in the configured port range and reports
it back through a response file.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `command` | string | yes | Full java command line, e.g. `java -jar /path/to/app.jar` |
| `workingDir` | string | no | Working directory for the launched process |
| `sessionId` | string | no | Session id; auto-generated when omitted. Reusing an id replaces (and closes) that session |
| `env` | object | no | Environment variables for the launched process, e.g. `{"HDF5_PLUGIN_PATH": "./plugins"}`. Added to (and overriding) the server's environment, for this launch only |

**Returns:** session info (session id, mode, PID, agent port). The new session
becomes the active one.

**Notes:**
- Requires `swing.mcp.agent-jar` (or the `SWING_MCP_AGENT_JAR` environment
  variable) to point at the swing-mcp-agent shaded jar.
- A launched application is terminated when the session is closed with
  `stop_app`, together with any processes it started (so a wrapper such as
  `xvfb-run` does not leave the JVM behind). Its captured output file is
  deleted at the same time.
- If the agent does not come up (the application exits, hangs before the agent
  starts, or the agent cannot bind a port), the launch fails and the process is
  stopped instead of being left running.
- The `-javaagent` argument is inserted right after the java launcher (`java`,
  `javaw`, with or without a path or `.exe`), so JVM options belong in `command`
  after it, and a wrapper in front of it keeps working, e.g.
  `env HDF5_PLUGIN_PATH=./plugins java -jar app.jar`. A command that contains no
  java launcher is rejected, since the agent could not be loaded into it.
- Use `env` for variables an application's own launcher script would normally
  set (native library paths, plugin directories): without them, an application
  may start but fail silently later.

## `attach_to_app`

Attach the agent to an already-running Java desktop JVM by PID using dynamic
agent loading.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pid` | number | yes | Process id of the target JVM |
| `sessionId` | string | no | Session id; auto-generated when omitted. Reusing an id replaces (and closes) that session |

**Returns:** session info (session id, mode, PID, agent port). The new session
becomes the active one.

**Notes:**
- The target JVM must allow dynamic attach.
- JavaFX windows only exist once `Application.start` has run, so attach after
  the UI is up.
- An attached application keeps running when the session is closed. The
  agent's listening socket is shut down, so attaching to the same JVM again and
  again does not use up the agent port range.

## `stop_app`

Close a session — the active one by default.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `sessionId` | string | no | Session id to close; the active session when omitted |

**Behaviour:**
- Launched application → the process is terminated.
- Attached application → the agent connection is closed; the target keeps
  running.
- When the active session is closed, another open session (if any) becomes
  active.

## `list_sessions`

List all open application sessions.

No parameters.

**Returns:** for each session: `sessionId`, `active`, `alive`, `mode`, `pid`,
`agentPort`, `description`.

## `select_session`

Make a session the active one that all other tools operate on.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `sessionId` | string | yes | Session id from `list_sessions` |

## `app_status`

Get the status of the active application session.

No parameters.

**Returns:** `connected`, `sessionId`, `sessionCount`, `alive`, `mode`
(`LAUNCHED`/`ATTACHED`), `pid`, `agentPort`, `description`, and for a launched
application `outputFile`, the file its stdout and stderr are captured in.

## `get_app_output`

Read the captured stdout and stderr of an application started with
`launch_app`: stack traces and log lines are often the only trace of an error
the UI swallows.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `tail` | number | no | Number of last lines to return (default 50, max 500) |
| `sinceLine` | number | no | Return the lines after this line number instead of a tail |
| `sessionId` | string | no | Session id; the active session when omitted |

**Returns:** `sessionId`, `alive`, `outputFile`, `totalLines`, `fromLine` and
`toLine` (1-based line numbers of the returned block), `text`, and `more: true`
when lines after `toLine` were left out.

**Notes:**
- To follow the output, pass the previous call's `toLine` as `sinceLine`.
- A call returns at most 500 lines and about 40,000 characters.
- Only launched applications have captured output; for an application attached
  by PID the tool returns an error, because its output goes wherever it was
  started from.
- `launch_app` and `list_sessions` also report `outputFile`.
