package solutions.crosstech.swingmcp.server.config;

import solutions.crosstech.swingmcp.server.tools.ApplicationTools;
import solutions.crosstech.swingmcp.server.tools.ClipboardTools;
import solutions.crosstech.swingmcp.server.tools.DialogTools;
import solutions.crosstech.swingmcp.server.tools.InteractionTools;
import solutions.crosstech.swingmcp.server.tools.ScreenshotTools;
import solutions.crosstech.swingmcp.server.tools.SnapshotTools;
import solutions.crosstech.swingmcp.server.tools.UtilityTools;
import solutions.crosstech.swingmcp.server.tools.WindowTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers all Swing MCP tool facades with the MCP server via a
 * {@link MethodToolCallbackProvider}.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider swingToolCallbacks(
            ApplicationTools applicationTools,
            SnapshotTools snapshotTools,
            WindowTools windowTools,
            InteractionTools interactionTools,
            DialogTools dialogTools,
            ClipboardTools clipboardTools,
            ScreenshotTools screenshotTools,
            UtilityTools utilityTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(
                applicationTools,
                snapshotTools,
                windowTools,
                interactionTools,
                dialogTools,
                clipboardTools,
                screenshotTools,
                utilityTools)
            .build();
    }
}
