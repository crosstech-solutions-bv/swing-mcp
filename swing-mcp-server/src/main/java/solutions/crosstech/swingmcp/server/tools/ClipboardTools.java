package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.ClipboardService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for reading and writing the target JVM's system clipboard.
 */
@Component
public class ClipboardTools {

    private final ClipboardService clipboardService;

    public ClipboardTools(ClipboardService clipboardService) {
        this.clipboardService = clipboardService;
    }

    @Tool(name = "get_clipboard", description = "Read the system clipboard of the target JVM as text.")
    public String getClipboard() {
        return ToolJson.toJson(clipboardService.getClipboard());
    }

    @Tool(name = "set_clipboard", description = "Write text to the system clipboard of the target JVM.")
    public String setClipboard(
            @ToolParam(description = "Text to place on the clipboard") String text) {
        return ToolJson.toJson(clipboardService.setClipboard(text));
    }
}
