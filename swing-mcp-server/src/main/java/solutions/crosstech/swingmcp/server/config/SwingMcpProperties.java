package solutions.crosstech.swingmcp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Swing MCP server, bound from the
 * {@code swing.mcp} prefix in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "swing.mcp")
public class SwingMcpProperties {

    /** Lower bound of the port range the in-process agent may bind to. */
    private int agentPortMin = 40000;

    /** Upper bound of the port range the in-process agent may bind to. */
    private int agentPortMax = 40100;

    /** Path to the swing-mcp-agent shaded jar used for launch/attach. */
    private String agentJar = "";

    /** Directory where screenshots are written. */
    private String screenshotDir = System.getProperty("java.io.tmpdir") + "/swing-mcp-screenshots";

    /** Timeout in milliseconds for a single agent command round-trip. */
    private long toolTimeoutMs = 30000;

    private final Evaluate evaluate = new Evaluate();

    /** Settings for the {@code evaluate_java} tool. */
    public static class Evaluate {
        /** Whether arbitrary Java evaluation inside the target JVM is allowed. */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public int getAgentPortMin() {
        return agentPortMin;
    }

    public void setAgentPortMin(int agentPortMin) {
        this.agentPortMin = agentPortMin;
    }

    public int getAgentPortMax() {
        return agentPortMax;
    }

    public void setAgentPortMax(int agentPortMax) {
        this.agentPortMax = agentPortMax;
    }

    public String getAgentJar() {
        return agentJar;
    }

    public void setAgentJar(String agentJar) {
        this.agentJar = agentJar;
    }

    public String getScreenshotDir() {
        return screenshotDir;
    }

    public void setScreenshotDir(String screenshotDir) {
        this.screenshotDir = screenshotDir;
    }

    public long getToolTimeoutMs() {
        return toolTimeoutMs;
    }

    public void setToolTimeoutMs(long toolTimeoutMs) {
        this.toolTimeoutMs = toolTimeoutMs;
    }

    public Evaluate getEvaluate() {
        return evaluate;
    }
}
