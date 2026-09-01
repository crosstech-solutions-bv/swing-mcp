# Security Policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.1.x   | ✅ |
| 1.0.x   | ❌ (upgrade to 1.1.x) |

## Security model

swing-mcp is a local development and automation tool. It is designed to run on a developer's machine and to control Swing applications on that same machine. It is **not** intended to be exposed to a network or to untrusted clients.

Trust boundaries to be aware of:

- **The agent controls the target JVM.** `swing-mcp-agent` is loaded into the target application (via `-javaagent` or dynamic attach by PID) and can drive its UI fully. Only attach it to applications you are entitled to control.
- **The MCP client controls the server.** Any MCP client connected over stdio can invoke all enabled tools. Treat the client (and the model driving it) as trusted.
- **Screenshots and snapshots can contain sensitive data.** Tool output may include on-screen text, table contents, and clipboard data from the target application.

## Built-in mitigations

The following protections are in place today:

- **Loopback-only agent socket.** The agent binds its JSON line-protocol socket server to the loopback interface only, within the configured `swing.mcp.agent-port-min..max` range. It is not reachable from other machines.
- **`evaluate_java` disabled by default.** Arbitrary code execution in the target JVM is opt-in only, via `swing.mcp.evaluate.enabled=true`. Leave it disabled unless you specifically need it, and never enable it when attaching to applications handling sensitive data.
- **Restricted CI workflow permissions.** GitHub Actions workflows run with minimal `permissions` grants (hardened in the initial release, PR #1).
- **No committed build artifacts.** Build outputs are excluded from the repository via `.gitignore` (fixed in the initial release).

## Known limitations

- The agent socket has no authentication; any local process that can reach the loopback port during a session could send commands. Run swing-mcp only on machines where local processes are trusted.
- Dynamic attach (`attach_to_app`) uses the JVM attach mechanism, which the target JVM must permit; it grants the same full UI control as launch-time loading.

## Reporting a vulnerability

Please do **not** report security vulnerabilities through public GitHub issues.

Instead, use [GitHub private vulnerability reporting](https://github.com/crosstech-solutions-bv/swing-mcp/security/advisories/new) for this repository. Include:

- A description of the issue and its impact
- Steps to reproduce or a proof of concept
- Affected version(s) and configuration (e.g., whether `evaluate_java` was enabled)

You can expect an acknowledgement within a few days. Please allow time for a fix and coordinated disclosure before sharing details publicly.

## Fix history

- **1.0.0** — CI workflow permissions restricted to the minimum required; committed build artifacts removed from the repository; agent socket made loopback-only and `evaluate_java` shipped disabled by default.

No security vulnerabilities have been reported against released versions to date. Future security-relevant fixes will be listed here and in [CHANGELOG.md](CHANGELOG.md).
