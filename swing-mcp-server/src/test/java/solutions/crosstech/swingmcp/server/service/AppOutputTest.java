package solutions.crosstech.swingmcp.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.server.config.SwingMcpProperties;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import solutions.crosstech.swingmcp.server.session.AgentConnection;
import solutions.crosstech.swingmcp.server.session.AttachedAppSession;
import solutions.crosstech.swingmcp.server.session.FakeAgentServer;
import solutions.crosstech.swingmcp.server.session.LaunchedAppSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #26: the captured stdout/stderr of a launched application can be read
 * through the tools, by tail or incrementally.
 */
class AppOutputTest {

    @TempDir
    Path tempDir;

    private FakeAgentServer agent;
    private SessionRegistry registry;
    private ApplicationService service;
    private Path log;
    private Process process;

    @BeforeEach
    void setUp() throws Exception {
        agent = FakeAgentServer.echoing();
        registry = new SessionRegistry();
        service = new ApplicationService(registry, new SwingMcpProperties());
        log = tempDir.resolve("app.log");
        // Any real process will do: the session only needs its pid and liveness.
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
            .redirectErrorStream(true).redirectOutput(tempDir.resolve("java-version.txt").toFile()).start();
        AgentConnection connection = AgentConnection.connect(agent.port(), 5000);
        registry.register("app", new LaunchedAppSession(process, connection, agent.port(), "Launched: test", log));
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.clear();
        agent.close();
    }

    @Test
    void tailReturnsTheLastLines() throws Exception {
        writeLines(120, "line ");
        Map<String, Object> out = service.output(null, null, null);
        assertEquals(120, out.get("totalLines"));
        assertEquals(71, out.get("fromLine"));
        assertEquals(120, out.get("toLine"));
        String text = (String) out.get("text");
        assertTrue(text.startsWith("line 71\n"), text);
        assertTrue(text.endsWith("line 120"), text);
        assertEquals(log.toString(), out.get("outputFile"));

        Map<String, Object> three = service.output("app", 3, null);
        assertEquals("line 118\nline 119\nline 120", three.get("text"));
    }

    @Test
    void sinceLineFollowsNewOutput() throws Exception {
        writeLines(10, "a");
        Map<String, Object> first = service.output(null, null, 8);
        assertEquals("a9\na10", first.get("text"));
        assertEquals(10, first.get("toLine"));
        assertFalse(first.containsKey("more"));

        Files.writeString(log, "late 1\nlate 2\n", java.nio.file.StandardOpenOption.APPEND);
        Map<String, Object> next = service.output(null, null, (Integer) first.get("toLine"));
        assertEquals("late 1\nlate 2", next.get("text"));
        assertEquals(11, next.get("fromLine"));

        Map<String, Object> none = service.output(null, null, 12);
        assertEquals("", none.get("text"));
        assertEquals(13, none.get("fromLine"));
        assertEquals(12, none.get("toLine"));
    }

    @Test
    void largeOutputIsCappedAndSaysSo() throws Exception {
        writeLines(ApplicationService.MAX_OUTPUT_LINES + 100, "x");
        Map<String, Object> page = service.output(null, null, 0);
        assertEquals(ApplicationService.MAX_OUTPUT_LINES, page.get("toLine"));
        assertEquals(Boolean.TRUE, page.get("more"));

        List<String> wide = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            wide.add(i + " " + "w".repeat(1000));
        }
        Files.write(log, wide);
        Map<String, Object> tail = service.output(null, 100, null);
        int returned = (Integer) tail.get("toLine") - (Integer) tail.get("fromLine") + 1;
        assertTrue(returned < 100, "the character budget must cut the tail: " + returned);
        assertTrue(((String) tail.get("text")).length() <= ApplicationService.MAX_OUTPUT_CHARS);
        assertTrue(((String) tail.get("text")).endsWith("w"), "the newest lines are kept");
    }

    @Test
    void undecodableBytesDoNotFailTheRead() throws Exception {
        Files.write(log, new byte[] {'o', 'k', '\n', (byte) 0xC3, (byte) 0x28, '\n'});
        Map<String, Object> out = service.output(null, null, null);
        assertEquals(2, out.get("totalLines"));
        assertTrue(((String) out.get("text")).startsWith("ok\n"));
    }

    @Test
    void closingTheSessionDeletesItsOutputLog() throws Exception {
        writeLines(3, "l");
        assertEquals(3, service.output("app", null, null).get("totalLines"));
        service.stop("app");
        assertFalse(Files.exists(log), "output log must be removed with its session");
    }

    @Test
    void sessionInfoReportsTheOutputFile() {
        assertEquals(log.toString(), service.status().get("outputFile"));
        assertEquals(log.toString(), service.listSessions().get(0).get("outputFile"));
    }

    @Test
    void attachedSessionsHaveNoCapturedOutput() throws Exception {
        AgentConnection connection = AgentConnection.connect(agent.port(), 5000);
        registry.register("attached", new AttachedAppSession(ProcessHandle.current().pid(), connection, agent.port()));
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> service.output("attached", null, null));
        assertTrue(e.getMessage().contains("launch_app"), e.getMessage());
    }

    private void writeLines(int count, String prefix) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add(prefix + i);
        }
        Files.write(log, lines);
    }
}
