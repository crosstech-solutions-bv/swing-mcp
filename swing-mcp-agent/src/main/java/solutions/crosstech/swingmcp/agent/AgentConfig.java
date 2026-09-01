package solutions.crosstech.swingmcp.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration parsed from agent arguments string.
 * Format: key=value,key=value (comma-separated pairs).
 */
public record AgentConfig(
    int portMin,
    int portMax,
    String responseFile,
    boolean evaluateEnabled
) {

    private static final int DEFAULT_PORT_MIN = 40000;
    private static final int DEFAULT_PORT_MAX = 40100;

    /**
     * Parses an agent argument string into an {@link AgentConfig}.
     *
     * @param agentArgs comma-separated key=value pairs, or null/empty for defaults
     * @return parsed configuration
     */
    public static AgentConfig parse(String agentArgs) {
        Map<String, String> params = new HashMap<>();
        if (agentArgs != null && !agentArgs.isBlank()) {
            for (String pair : agentArgs.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        int portMin = Integer.parseInt(params.getOrDefault("portMin", String.valueOf(DEFAULT_PORT_MIN)));
        int portMax = Integer.parseInt(params.getOrDefault("portMax", String.valueOf(DEFAULT_PORT_MAX)));
        String responseFile = params.get("responseFile");
        boolean evaluateEnabled = Boolean.parseBoolean(params.getOrDefault("evaluateEnabled", "false"));
        return new AgentConfig(portMin, portMax, responseFile, evaluateEnabled);
    }
}
