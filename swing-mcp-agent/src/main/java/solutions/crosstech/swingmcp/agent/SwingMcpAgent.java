package solutions.crosstech.swingmcp.agent;

import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Java agent entry point for the Swing MCP agent.
 * Supports both dynamic attach (agentmain) and startup (-javaagent / premain) modes.
 * When loaded, starts a localhost-only JSON line-protocol socket server on a free port
 * within the configured range, then writes the chosen port back via the response file
 * or agent arguments mechanism so the MCP server can connect.
 */
public class SwingMcpAgent {

    private static final Logger LOG = Logger.getLogger(SwingMcpAgent.class.getName());

    /**
     * Entry point for dynamic attach via {@code VirtualMachine.loadAgent(...)}.
     *
     * @param agentArgs optional arguments (e.g. "portMin=40000,portMax=40100,responseFile=/tmp/port.txt")
     * @param inst      the instrumentation handle (not used for transformation)
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        startAgent(agentArgs);
    }

    /**
     * Entry point for startup via {@code -javaagent} JVM flag.
     *
     * @param agentArgs optional arguments
     * @param inst      the instrumentation handle
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        startAgent(agentArgs);
    }

    private static void startAgent(String agentArgs) {
        try {
            AgentConfig config = AgentConfig.parse(agentArgs);
            AgentServer server = new AgentServer(config);
            server.start();
            LOG.info("Swing MCP Agent started on port " + server.getPort());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to start Swing MCP Agent", e);
        }
    }
}
