package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent server's lifecycle: {@code SHUTDOWN} releases the port without
 * touching the JVM, a failed bind is reported through the response file, and
 * toolkit-independent commands never resolve a toolkit. Headless-safe.
 */
class AgentLifecycleTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonCodec codec = new JsonCodec();

    @TempDir
    Path tempDir;

    @Test
    void pingNeedsNoToolkitEvenWithAnUnknownUid() throws Exception {
        CommandHandler handler = new CommandHandler(codec);
        CommandResponse r = mapper.readValue(handler.handle(mapper.writeValueAsString(
            new CommandRequest("r1", CommandType.PING, Map.of("uid", "nope-1")))), CommandResponse.class);
        assertTrue(r.success(), r.error());
        assertEquals("pong", r.result());
    }

    @Test
    void shutdownRunsTheHook() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CommandHandler handler = new CommandHandler(codec, false, null, calls::incrementAndGet);
        CommandResponse r = mapper.readValue(handler.handle(mapper.writeValueAsString(
            new CommandRequest("r1", CommandType.SHUTDOWN, Map.of()))), CommandResponse.class);
        assertTrue(r.success(), r.error());
        assertEquals(1, calls.get());
    }

    @Test
    void shutdownCommandStopsTheServerAndFreesThePort() throws Exception {
        int port = freePort();
        Path responseFile = Files.createFile(tempDir.resolve("port.txt"));
        AgentServer server = new AgentServer(new AgentConfig(port, port, responseFile.toString(), false));
        server.start();
        try {
            String[] handshake = Files.readString(responseFile).strip().split("\\R");
            assertEquals(String.valueOf(port), handshake[0]);
            String token = handshake[1];

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println(mapper.writeValueAsString(
                    new CommandRequest("r1", CommandType.SHUTDOWN, Map.of(), token)));
                CommandResponse r = mapper.readValue(reader.readLine(), CommandResponse.class);
                assertTrue(r.success(), r.error());
            }
            assertFalse(server.isRunning());
            // The port is free again: a later attach can bind it.
            try (ServerSocket rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                assertEquals(port, rebound.getLocalPort());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void failedBindIsReportedThroughTheResponseFile() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = taken.getLocalPort();
            Path responseFile = Files.createFile(tempDir.resolve("port.txt"));
            AgentServer server = new AgentServer(new AgentConfig(port, port, responseFile.toString(), false));
            IOException e = assertThrows(IOException.class, server::start);
            String content = Files.readString(responseFile).strip();
            assertTrue(content.startsWith("ERROR "), content);
            assertTrue(content.contains(e.getMessage()), content);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
