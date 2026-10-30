package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.server.config.SwingMcpProperties;
import solutions.crosstech.swingmcp.server.domain.AgentCommandException;
import solutions.crosstech.swingmcp.server.domain.SessionInfo;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import solutions.crosstech.swingmcp.server.session.AgentConnection;
import solutions.crosstech.swingmcp.server.session.AppSession;
import solutions.crosstech.swingmcp.server.session.AttachedAppSession;
import solutions.crosstech.swingmcp.server.session.LaunchedAppSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages the lifecycle of the target Swing application session:
 * launching a new JVM with the agent preloaded, attaching to a running JVM
 * by PID, and stopping/disconnecting.
 */
@Service
public class ApplicationService {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationService.class);
    private static final long PORT_FILE_POLL_MS = 200;
    private static final long PORT_FILE_TIMEOUT_MS = 30000;

    private final SessionRegistry registry;
    private final SwingMcpProperties properties;

    public ApplicationService(SessionRegistry registry, SwingMcpProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Launches a Swing application with the agent preloaded via {@code -javaagent}.
     *
     * @param command    full java command line (e.g. "java -jar app.jar")
     * @param workingDir optional working directory
     * @return session info for the launched application
     */
    public Map<String, Object> launch(String command, String workingDir) {
        return launch(command, workingDir, null);
    }

    /**
     * Launches a Swing application with the agent preloaded via {@code -javaagent},
     * registering it under the given session id (auto-generated when null).
     *
     * @param command    full java command line (e.g. "java -jar app.jar")
     * @param workingDir optional working directory
     * @param sessionId  optional session id; replaces an existing session with the same id
     * @return session info for the launched application
     */
    public Map<String, Object> launch(String command, String workingDir, String sessionId) {
        return launch(command, workingDir, sessionId, null);
    }

    /**
     * Launches a Swing application with the agent preloaded via {@code -javaagent},
     * with extra environment variables for the launched process.
     *
     * @param command    full java command line (e.g. "java -jar app.jar"); may start with
     *                   a wrapper such as {@code env VAR=value java ...}
     * @param workingDir optional working directory
     * @param sessionId  optional session id; replaces an existing session with the same id
     * @param env        optional environment variables added to (or overriding) the
     *                   server's own environment for this process only
     * @return session info for the launched application
     */
    public Map<String, Object> launch(String command, String workingDir, String sessionId,
                                      Map<String, String> env) {
        Path agentJar = requireAgentJar();
        List<String> args = new ArrayList<>(tokenizeCommand(command == null ? "" : command));
        if (args.isEmpty() || args.getFirst().isBlank()) {
            throw new IllegalArgumentException("Launch command must not be empty");
        }
        int agentIndex = agentArgIndex(args);
        Path portFile = null;
        Path outputLog = null;
        Process process = null;
        boolean launched = false;
        try {
            // Created with owner-only permissions (0600 on POSIX; user-scoped temp
            // ACL on Windows). Keep this file in place (don’t delete/recreate it)
            // so the agent can write the port + auth token into a pre-secured file;
            // it is deleted after a successful read.
            portFile = Files.createTempFile("swing-mcp-port", ".txt");
            portFile.toFile().deleteOnExit();
            args.add(agentIndex, buildAgentArg(agentJar, portFile));

            ProcessBuilder pb = processBuilder(args, workingDir, env);
            outputLog = Files.createTempFile("swing-mcp-app", ".log");
            outputLog.toFile().deleteOnExit();
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputLog.toFile());
            process = pb.start();

            AgentHandshake handshake = awaitPortFile(portFile, process, outputLog);
            int port = handshake.port();
            AgentConnection connection =
                AgentConnection.connect(port, properties.getToolTimeoutMs(), handshake.token());
            AppSession session = new LaunchedAppSession(process, connection, port, "Launched: " + command, outputLog);
            launched = true; // the session owns the process and its output log from here on
            String id = registry.register(sessionId, session);
            LOG.info("Launched application pid={} agentPort={} sessionId={}", session.info().pid(), port, id);
            return sessionInfoMap(id, session);
        } catch (IOException e) {
            throw new AgentCommandException("Failed to launch application: " + e.getMessage(), e);
        } finally {
            if (!launched) {
                // Never handed to a session: don't leave an orphan JVM with a window,
                // nor the handshake and log files behind.
                if (process != null) {
                    LaunchedAppSession.terminate(process.toHandle());
                }
                deleteQuietly(portFile);
                deleteQuietly(outputLog);
            }
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.debug("Failed to delete {}", file, e);
            }
        }
    }

    /**
     * Attaches the agent to an already-running JVM by PID and connects to it.
     *
     * @param pid the target JVM process id
     * @return session info for the attached application
     */
    public Map<String, Object> attach(long pid) {
        return attach(pid, null);
    }

    /**
     * Attaches the agent to an already-running JVM by PID, registering the
     * session under the given id (auto-generated when null).
     *
     * @param pid       the target JVM process id
     * @param sessionId optional session id; replaces an existing session with the same id
     * @return session info for the attached application
     */
    public Map<String, Object> attach(long pid, String sessionId) {
        Path agentJar = requireAgentJar();
        try {
            // Created with owner-only permissions (0600 on POSIX; user-scoped temp
            // ACL on Windows). It is intentionally NOT deleted: the agent writes the
            // port and auth token into this existing file, so the token is never
            // exposed via a freshly-created world-readable file.
            Path portFile = Files.createTempFile("swing-mcp-port", ".txt");
            portFile.toFile().deleteOnExit();

            // buildAgentArg returns "-javaagent:...=<args>"; attach needs just the <args>.
            String agentArgs = buildAgentArg(agentJar, portFile).substring(
                ("-javaagent:" + agentJar.toAbsolutePath() + "=").length());
            loadAgent(pid, agentJar, agentArgs);

            AgentHandshake handshake = awaitPortFile(portFile, null, null);
            int port = handshake.port();
            AgentConnection connection =
                AgentConnection.connect(port, properties.getToolTimeoutMs(), handshake.token());
            AppSession session = new AttachedAppSession(pid, connection, port);
            String id = registry.register(sessionId, session);
            LOG.info("Attached to pid={} agentPort={} sessionId={}", pid, port, id);
            return sessionInfoMap(id, session);
        } catch (IOException e) {
            throw new AgentCommandException("Failed to attach to PID " + pid + ": " + e.getMessage(), e);
        }
    }

    /**
     * Stops the active session. A launched application is terminated;
     * an attached application is only disconnected.
     */
    public String stop() {
        return stop(null);
    }

    /**
     * Stops the session with the given id (or the active session when null).
     * A launched application is terminated; an attached application is only
     * disconnected.
     */
    public String stop(String sessionId) {
        if (!registry.hasSession()) {
            return "No active session";
        }
        SessionInfo info = registry.require(sessionId).info();
        String closedId = registry.close(sessionId);
        return "Session closed: " + closedId + " (" + info.description() + ")";
    }

    /** Lists all registered sessions with their id, mode, PID, and liveness. */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        String active = registry.activeSessionId();
        registry.sessions().forEach((id, session) -> {
            SessionInfo info = session.info();
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("sessionId", id);
            entry.put("active", id.equals(active));
            entry.put("alive", session.isAlive());
            entry.put("mode", info.mode().name());
            entry.put("pid", info.pid());
            entry.put("agentPort", info.agentPort());
            entry.put("description", info.description());
            session.outputFile().ifPresent(f -> entry.put("outputFile", f.toString()));
            result.add(entry);
        });
        return result;
    }

    /** Makes the session with the given id the active session. */
    public String selectSession(String sessionId) {
        registry.select(sessionId);
        return "Active session: " + sessionId;
    }

    /** Returns status of the active session. */
    public Map<String, Object> status() {
        if (!registry.hasSession() || registry.activeSessionId() == null) {
            return Map.of("connected", false, "sessionCount", registry.sessions().size());
        }
        AppSession session = registry.require();
        SessionInfo info = session.info();
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("connected", true);
        map.put("sessionId", registry.activeSessionId());
        map.put("sessionCount", registry.sessions().size());
        map.put("alive", session.isAlive());
        map.put("mode", info.mode().name());
        map.put("pid", info.pid());
        map.put("agentPort", info.agentPort());
        map.put("description", info.description());
        session.outputFile().ifPresent(f -> map.put("outputFile", f.toString()));
        return map;
    }

    /** Most lines a single {@link #output} call returns. */
    static final int MAX_OUTPUT_LINES = 500;
    /** Character budget of a single {@link #output} call, so a noisy log cannot flood the client. */
    static final int MAX_OUTPUT_CHARS = 40_000;
    private static final int DEFAULT_TAIL = 50;

    /**
     * Reads the captured stdout/stderr of a launched application.
     *
     * <p>Lines are numbered from 1. With {@code sinceLine}, the lines after that
     * line are returned (pass the previous call's {@code toLine} to follow the
     * output); otherwise the last {@code tail} lines. Either way at most
     * {@link #MAX_OUTPUT_LINES} lines and about {@link #MAX_OUTPUT_CHARS}
     * characters come back, and {@code fromLine}/{@code toLine} say which.</p>
     *
     * @param sessionId optional session id; the active session when null
     * @param tail      number of last lines to return (default 50); ignored with sinceLine
     * @param sinceLine return the lines after this line number instead of a tail
     */
    public Map<String, Object> output(String sessionId, Integer tail, Integer sinceLine) {
        AppSession session = registry.require(sessionId);
        Path file = session.outputFile().orElseThrow(() -> new IllegalStateException(
            "This session was attached by PID, so its output is not captured; only applications "
                + "started with launch_app have their stdout and stderr recorded."));
        boolean following = sinceLine != null;
        int wanted = following ? MAX_OUTPUT_LINES
            : Math.min(MAX_OUTPUT_LINES, Math.max(1, tail == null ? DEFAULT_TAIL : tail));
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        int total = 0;
        int chars = 0;
        int from = -1;
        boolean more = false;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                Files.newInputStream(file), nativeCharset().newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;
                if (following) {
                    if (total <= sinceLine) {
                        continue;
                    }
                    if (more || lines.size() >= wanted
                            || !lines.isEmpty() && chars + line.length() > MAX_OUTPUT_CHARS) {
                        more = true;
                        continue;
                    }
                    if (from < 0) {
                        from = total;
                    }
                    lines.addLast(line);
                    chars += line.length() + 1;
                } else {
                    lines.addLast(line);
                    chars += line.length() + 1;
                    while (lines.size() > wanted || lines.size() > 1 && chars > MAX_OUTPUT_CHARS) {
                        chars -= lines.removeFirst().length() + 1;
                    }
                }
            }
        } catch (IOException e) {
            throw new AgentCommandException("Failed to read application output: " + e.getMessage(), e);
        }
        if (!following) {
            from = total - lines.size() + 1;
        } else if (from < 0) {
            from = total + 1;
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("sessionId", sessionId != null ? sessionId : registry.activeSessionId());
        map.put("alive", session.isAlive());
        map.put("outputFile", file.toString());
        map.put("totalLines", total);
        map.put("fromLine", from);
        map.put("toLine", from + lines.size() - 1);
        if (more) {
            map.put("more", true);
        }
        map.put("text", String.join("\n", lines));
        return map;
    }

    /**
     * The charset a child JVM writes redirected output in: its {@code native.encoding},
     * which is this machine's, not this server's UTF-8 default (JEP 400).
     */
    private static java.nio.charset.Charset nativeCharset() {
        try {
            String name = System.getProperty("native.encoding");
            return name == null ? java.nio.charset.Charset.defaultCharset() : java.nio.charset.Charset.forName(name);
        } catch (RuntimeException e) {
            return java.nio.charset.Charset.defaultCharset();
        }
    }

    private Map<String, Object> sessionInfoMap(String sessionId, AppSession session) {
        SessionInfo info = session.info();
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("sessionId", sessionId);
        map.put("mode", info.mode().name());
        map.put("pid", info.pid());
        map.put("agentPort", info.agentPort());
        map.put("description", info.description());
        session.outputFile().ifPresent(f -> map.put("outputFile", f.toString()));
        return map;
    }

    private Path requireAgentJar() {
        String path = properties.getAgentJar();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                "swing.mcp.agent-jar is not configured. Set it to the swing-mcp-agent shaded jar path.");
        }
        Path jar = Path.of(path);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Agent jar not found at: " + jar.toAbsolutePath());
        }
        return jar;
    }

    private void loadAgent(long pid, Path agentJar, String agentArgs) throws IOException {
        try {
            com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
            try {
                vm.loadAgent(agentJar.toAbsolutePath().toString(), agentArgs);
            } finally {
                vm.detach();
            }
        } catch (com.sun.tools.attach.AttachNotSupportedException
                 | com.sun.tools.attach.AgentLoadException
                 | com.sun.tools.attach.AgentInitializationException e) {
            throw new IOException("VM attach failed: " + e.getMessage(), e);
        }
    }

    /** The port and per-session auth token the agent reports back via the response file. */
    private record AgentHandshake(int port, String token) {}

    /** Builds the {@code -javaagent:<jar>=<args>} argument, carrying the port range,
     *  response file and the {@code evaluateEnabled} gate (so the agent enforces it too). */
    private String buildAgentArg(Path agentJar, Path portFile) {
        return "-javaagent:" + agentJar.toAbsolutePath()
            + "=portMin=" + properties.getAgentPortMin()
            + ",portMax=" + properties.getAgentPortMax()
            + ",evaluateEnabled=" + properties.getEvaluate().isEnabled()
            + ",responseFile=" + portFile.toAbsolutePath();
    }

    /**
     * Where the {@code -javaagent} argument goes: right after the java launcher
     * ({@code java}, {@code javaw}, with or without a path or {@code .exe}), so a
     * wrapper in front of it — {@code env VAR=value java -jar app.jar} — keeps
     * working.
     *
     * @throws IllegalArgumentException when the command has no java launcher: the
     *                                  agent can only be injected into a JVM
     */
    static int agentArgIndex(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            // Plain string handling: tokens such as -Dx=<y> are not valid paths everywhere.
            String token = args.get(i).replace('\\', '/');
            String name = token.substring(token.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            if (name.equals("java") || name.equals("java.exe") || name.equals("javaw") || name.equals("javaw.exe")) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("Launch command must run a Java launcher (java, java.exe, javaw "
            + "or javaw.exe, optionally behind a wrapper such as env or xvfb-run), but none was found in: "
            + String.join(" ", args));
    }

    /** Builds the process for the launch command, applying the working directory and extra environment. */
    static ProcessBuilder processBuilder(List<String> args, String workingDir, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(args);
        if (workingDir != null && !workingDir.isBlank()) {
            pb.directory(Path.of(workingDir).toFile());
        }
        if (env != null) {
            env.forEach((name, value) -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Environment variable names must not be empty");
                }
                pb.environment().put(name, value == null ? "" : value);
            });
        }
        return pb;
    }

    /**
     * Splits a command line into arguments, honoring single and double quotes
     * so paths and arguments containing spaces survive intact.
     */
    static List<String> tokenizeCommand(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private AgentHandshake awaitPortFile(Path portFile, Process process, Path outputLog) throws IOException {
        long deadline = System.currentTimeMillis() + PORT_FILE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new IOException("Target process exited before agent started (exit=" + process.exitValue() + ")"
                    + outputTail(outputLog));
            }
            if (Files.isRegularFile(portFile)) {
                String content = Files.readString(portFile).strip();
                if (!content.isEmpty()) {
                    String[] lines = content.split("\\R", 2);
                    String portText = lines[0].strip();
                    if (portText.startsWith("ERROR")) {
                        // The agent could not start (e.g. no free port) and says why.
                        Files.deleteIfExists(portFile);
                        throw new IOException("Agent failed to start: "
                            + portText.substring("ERROR".length()).strip() + outputTail(outputLog));
                    }
                    String token = lines.length > 1 ? lines[1].strip() : "";
                    // Both lines are required: a port without a token means the agent
                    // is still writing (or is an incompatible version) — keep waiting
                    // rather than proceed unauthenticated.
                    if (portText.matches("\\d+") && !token.isEmpty()) {
                        Files.deleteIfExists(portFile);
                        return new AgentHandshake(Integer.parseInt(portText), token);
                    }
                }
            }
            try {
                Thread.sleep(PORT_FILE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for agent port", e);
            }
        }
        throw new IOException("Timed out waiting for agent port file: " + portFile);
    }

    /** Returns the last lines of the launched process output, for error messages. */
    private String outputTail(Path outputLog) {
        if (outputLog == null) {
            return "";
        }
        try {
            List<String> lines = new String(Files.readAllBytes(outputLog), nativeCharset()).lines().toList();
            if (lines.isEmpty()) {
                return "";
            }
            List<String> tail = lines.subList(Math.max(0, lines.size() - 10), lines.size());
            return ". Process output (last " + tail.size() + " lines): " + String.join(" | ", tail);
        } catch (IOException e) {
            return "";
        }
    }
}
