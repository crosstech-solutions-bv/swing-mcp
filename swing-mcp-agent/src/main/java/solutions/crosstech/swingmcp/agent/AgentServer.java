package solutions.crosstech.swingmcp.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Localhost-only JSON line-protocol socket server running inside the target JVM.
 * Accepts connections from the MCP server's {@code AttachedAppSession} and dispatches
 * commands to the Swing EDT via {@link CommandHandler}.
 */
public class AgentServer {

    private static final Logger LOG = Logger.getLogger(AgentServer.class.getName());

    private final AgentConfig config;
    private final JsonCodec codec;
    private final String token;
    private ServerSocket serverSocket;
    private int port;
    private volatile boolean running;

    public AgentServer(AgentConfig config) {
        this.config = config;
        this.codec = new JsonCodec();
        // Per-session shared secret handed to the paired MCP server via the
        // (owner-only) response file. Any command without it is rejected.
        this.token = UUID.randomUUID().toString();
    }

    /**
     * Starts the server, binding to a free port in the configured range.
     * If a response file is configured, writes the chosen port to that file.
     *
     * @throws IOException if no port in the range can be bound
     */
    public void start() throws IOException {
        serverSocket = bindToFreePort();
        port = serverSocket.getLocalPort();
        running = true;

        if (config.responseFile() != null) {
            writePortToFile(config.responseFile(), port, token);
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(this::acceptLoop);
    }

    /**
     * Returns the actual port the server is listening on.
     *
     * @return the listening port
     */
    public int getPort() {
        return port;
    }

    private ServerSocket bindToFreePort() throws IOException {
        for (int p = config.portMin(); p <= config.portMax(); p++) {
            try {
                return new ServerSocket(p, 10, InetAddress.getLoopbackAddress());
            } catch (IOException ignored) {
                // Port in use, try next
            }
        }
        throw new IOException("No free port in range [" + config.portMin() + ", " + config.portMax() + "]");
    }

    /**
     * Writes the chosen port (line 1) and auth token (line 2) to the response
     * file, restricting it to owner-only permissions where the filesystem
     * supports POSIX permissions so the token cannot leak to other local users.
     */
    private void writePortToFile(String responseFile, int chosenPort, String authToken) {
        Path path = Path.of(responseFile);
        try {
            String content = chosenPort + System.lineSeparator() + authToken + System.lineSeparator();
            Files.writeString(path, content, StandardCharsets.UTF_8);
            restrictToOwner(path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write port to response file: " + responseFile, e);
        }
    }

    private void restrictToOwner(Path path) {
        try {
            Set<PosixFilePermission> ownerOnly =
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (e.g. Windows): fall back to the File API.
            File f = path.toFile();
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        }
    }

    private void acceptLoop() {
        ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
        while (running) {
            try {
                Socket client = serverSocket.accept();
                clientExecutor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "Accept loop error", e);
                }
            }
        }
    }

    /**
     * Reads command lines and dispatches each one to its own virtual thread,
     * writing responses (which carry the request id) as they complete. This
     * keeps the agent responsive even while an action command is blocked by a
     * modal dialog: {@code list_dialogs} / {@code handle_dialog} still work.
     * Responses may be written out of order; the server correlates them by
     * request id.
     */
    private void handleClient(Socket client) {
        ExecutorService commandExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Object writeLock = new Object();
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true)
        ) {
            CommandHandler handler = new CommandHandler(codec, config.evaluateEnabled(), token);
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line;
                // Authenticate before dispatch; a bad token ends the connection.
                String rejection = handler.rejectIfUnauthorized(command);
                if (rejection != null) {
                    synchronized (writeLock) {
                        writer.println(rejection);
                    }
                    break;
                }
                commandExecutor.submit(() -> {
                    String response = handler.handle(command);
                    synchronized (writeLock) {
                        writer.println(response);
                    }
                });
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Client disconnected", e);
        } finally {
            commandExecutor.shutdown();
        }
    }
}
