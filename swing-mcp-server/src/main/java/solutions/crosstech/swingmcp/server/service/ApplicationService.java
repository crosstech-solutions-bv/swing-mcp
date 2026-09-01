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
        Path agentJar = requireAgentJar();
        try {
            Path portFile = Files.createTempFile("swing-mcp-port", ".txt");
            Files.deleteIfExists(portFile);

            List<String> args = new ArrayList<>(List.of(command.trim().split("\\s+")));
            if (args.isEmpty() || args.getFirst().isBlank()) {
                throw new IllegalArgumentException("Launch command must not be empty");
            }
            String agentArg = "-javaagent:" + agentJar.toAbsolutePath()
                + "=portMin=" + properties.getAgentPortMin()
                + ",portMax=" + properties.getAgentPortMax()
                + ",responseFile=" + portFile.toAbsolutePath();
            args.add(1, agentArg);

            ProcessBuilder pb = new ProcessBuilder(args);
            if (workingDir != null && !workingDir.isBlank()) {
                pb.directory(Path.of(workingDir).toFile());
            }
            Path outputLog = Files.createTempFile("swing-mcp-app", ".log");
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputLog.toFile());
            Process process = pb.start();

            int port = awaitPortFile(portFile, process, outputLog);
            AgentConnection connection = AgentConnection.connect(port, properties.getToolTimeoutMs());
            AppSession session = new LaunchedAppSession(process, connection, port, "Launched: " + command);
            String id = registry.register(sessionId, session);
            LOG.info("Launched application pid={} agentPort={} sessionId={}", process.pid(), port, id);
            return sessionInfoMap(id, session.info());
        } catch (IOException e) {
            throw new AgentCommandException("Failed to launch application: " + e.getMessage(), e);
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
            Path portFile = Files.createTempFile("swing-mcp-port", ".txt");
            Files.deleteIfExists(portFile);

            String agentArgs = "portMin=" + properties.getAgentPortMin()
                + ",portMax=" + properties.getAgentPortMax()
                + ",responseFile=" + portFile.toAbsolutePath();
            loadAgent(pid, agentJar, agentArgs);

            int port = awaitPortFile(portFile, null, null);
            AgentConnection connection = AgentConnection.connect(port, properties.getToolTimeoutMs());
            AppSession session = new AttachedAppSession(pid, connection, port);
            String id = registry.register(sessionId, session);
            LOG.info("Attached to pid={} agentPort={} sessionId={}", pid, port, id);
            return sessionInfoMap(id, session.info());
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
        return Map.of(
            "connected", true,
            "sessionId", registry.activeSessionId(),
            "sessionCount", registry.sessions().size(),
            "alive", session.isAlive(),
            "mode", info.mode().name(),
            "pid", info.pid(),
            "agentPort", info.agentPort(),
            "description", info.description()
        );
    }

    private Map<String, Object> sessionInfoMap(String sessionId, SessionInfo info) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("sessionId", sessionId);
        map.put("mode", info.mode().name());
        map.put("pid", info.pid());
        map.put("agentPort", info.agentPort());
        map.put("description", info.description());
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

    private int awaitPortFile(Path portFile, Process process, Path outputLog) throws IOException {
        long deadline = System.currentTimeMillis() + PORT_FILE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new IOException("Target process exited before agent started (exit=" + process.exitValue() + ")"
                    + outputTail(outputLog));
            }
            if (Files.isRegularFile(portFile)) {
                String content = Files.readString(portFile).trim();
                if (!content.isEmpty()) {
                    Files.deleteIfExists(portFile);
                    return Integer.parseInt(content);
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
            List<String> lines = Files.readAllLines(outputLog);
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
