package solutions.crosstech.swingmcp.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private ServerSocket serverSocket;
    private int port;
    private volatile boolean running;

    public AgentServer(AgentConfig config) {
        this.config = config;
        this.codec = new JsonCodec();
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
            writePortToFile(config.responseFile(), port);
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

    private void writePortToFile(String responseFile, int chosenPort) {
        try (FileWriter fw = new FileWriter(new File(responseFile))) {
            fw.write(String.valueOf(chosenPort));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write port to response file: " + responseFile, e);
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
            CommandHandler handler = new CommandHandler(codec);
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line;
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
