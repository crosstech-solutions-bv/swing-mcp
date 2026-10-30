package solutions.crosstech.swingmcp.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.AgentCommandException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentConnectionTest {

    @Test
    void sendReturnsResultOnSuccess() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.echoing();
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            Object result = connection.send(CommandType.PING, Map.of());
            assertEquals("PING", result);
            assertTrue(connection.isOpen());
        }
    }

    @Test
    void sendThrowsOnAgentError() throws Exception {
        try (FakeAgentServer server = new FakeAgentServer(req ->
                new CommandResponse(req.requestId(), false, null, "boom"));
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            AgentCommandException ex = assertThrows(AgentCommandException.class,
                () -> connection.send(CommandType.CLICK, Map.of("uid", "comp-1")));
            assertTrue(ex.getMessage().contains("boom"));
        }
    }

    @Test
    void sendPassesParamsThrough() throws Exception {
        try (FakeAgentServer server = new FakeAgentServer(req ->
                new CommandResponse(req.requestId(), true, req.params(), null));
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            Object result = connection.send(CommandType.FILL, Map.of("uid", "comp-2", "text", "hello"));
            assertEquals(Map.of("uid", "comp-2", "text", "hello"), result);
        }
    }

    /** Issue #1 (Defect B): a stale response with a foreign request id must be skipped. */
    @Test
    void sendDiscardsStaleResponsesAndReturnsOwnResult() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.multiResponding(req -> List.of(
                new CommandResponse("some-older-request", true, "STALE", null),
                new CommandResponse(req.requestId(), true, "FRESH", null)));
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            Object result = connection.send(CommandType.PING, Map.of());
            assertEquals("FRESH", result);
        }
    }

    /**
     * Issue #1 (Defect B): after a request times out, its late response must be
     * dropped and the next request must receive its own response — no off-by-N
     * desync, no re-attach needed.
     */
    @Test
    void timedOutRequestDoesNotDesyncSubsequentRequests() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        AtomicReference<String> firstRequestId = new AtomicReference<>();
        try (FakeAgentServer server = FakeAgentServer.multiResponding(req -> {
                if (counter.incrementAndGet() == 1) {
                    firstRequestId.set(req.requestId());
                    return List.of(); // never answer the first request -> client times out
                }
                // Late response of request 1 arrives before the response of request 2.
                return List.of(
                    new CommandResponse(firstRequestId.get(), true, "LATE-ANSWER-1", null),
                    new CommandResponse(req.requestId(), true, "ANSWER-2", null));
            });
             AgentConnection connection = AgentConnection.connect(server.port(), 300)) {
            AgentCommandException ex = assertThrows(AgentCommandException.class,
                () -> connection.send(CommandType.SELECT_MENU_ITEM, Map.of("path", "Help > About")));
            assertTrue(ex.getMessage().contains("timed out"));

            Object result = connection.send(CommandType.LIST_DIALOGS, Map.of());
            assertEquals("ANSWER-2", result);
            assertTrue(connection.isOpen());
        }
    }

    /** A command that may run long (WAIT_FOR) waits past the configured read timeout. */
    @Test
    void perCallTimeoutExtendsTheReadTimeout() throws Exception {
        try (FakeAgentServer server = new FakeAgentServer(req -> {
                sleep(700);
                return new CommandResponse(req.requestId(), true, "MET", null);
            });
             AgentConnection connection = AgentConnection.connect(server.port(), 200)) {
            assertEquals("MET", connection.send(CommandType.WAIT_FOR, Map.of(), 3000));
        }
    }

    /**
     * A timeout that strikes partway through a response line loses that line's
     * start; the next command must skip the leftover tail, not fail to parse it.
     */
    @Test
    void responseCutOffByATimeoutDoesNotBreakTheNextCommand() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread agent = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                         new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    CommandRequest first = mapper.readValue(reader.readLine(), CommandRequest.class);
                    String big = mapper.writeValueAsString(
                        new CommandResponse(first.requestId(), true, "x".repeat(50_000), null));
                    int half = big.length() / 2;
                    writer.print(big.substring(0, half));
                    writer.flush();
                    CommandRequest second = mapper.readValue(reader.readLine(), CommandRequest.class);
                    writer.println(big.substring(half));
                    writer.println(mapper.writeValueAsString(
                        new CommandResponse(second.requestId(), true, "SECOND", null)));
                    reader.readLine(); // hold the connection until the client closes it
                } catch (IOException ignored) {
                    // test over
                }
            });
            agent.setDaemon(true);
            agent.start();
            try (AgentConnection connection = AgentConnection.connect(server.getLocalPort(), 300)) {
                AgentCommandException ex = assertThrows(AgentCommandException.class,
                    () -> connection.send(CommandType.TAKE_SNAPSHOT, Map.of()));
                assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());

                assertEquals("SECOND", connection.send(CommandType.PING, Map.of()));
            }
        }
    }

    @Test
    void requestShutdownSendsShutdown() throws Exception {
        AtomicReference<CommandType> seen = new AtomicReference<>();
        try (FakeAgentServer server = new FakeAgentServer(req -> {
                seen.set(req.type());
                return new CommandResponse(req.requestId(), true, "stopped", null);
            });
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            connection.requestShutdown();
            assertEquals(CommandType.SHUTDOWN, seen.get());
        }
    }

    /** An agent too old to know SHUTDOWN answers with an uncorrelated error; the wait stays short. */
    @Test
    void requestShutdownGivesUpQuicklyOnAnOldAgent() throws Exception {
        try (FakeAgentServer server = new FakeAgentServer(req ->
                new CommandResponse(null, false, null, "Failed to parse request"));
             AgentConnection connection = AgentConnection.connect(server.port(), 30_000)) {
            long start = System.nanoTime();
            assertThrows(AgentCommandException.class, connection::requestShutdown);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 10_000, "took " + elapsedMs + " ms");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
