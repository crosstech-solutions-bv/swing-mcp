package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.ScreenshotService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for taking screenshots of the target application.
 */
@Component
public class ScreenshotTools {

    private final ScreenshotService screenshotService;

    public ScreenshotTools(ScreenshotService screenshotService) {
        this.screenshotService = screenshotService;
    }

    @Tool(name = "take_screenshot", description = """
        Take a PNG screenshot of the active window, or of a single component if a UID is given. \
        The image is saved to the configured screenshot directory and its path is returned. \
        Set returnImage=true to also receive the base64-encoded PNG data inline.""")
    public String takeScreenshot(
            @ToolParam(description = "Optional component UID; omit to capture the whole active window", required = false) String uid,
            @ToolParam(description = "When true, include the base64-encoded PNG data in the response", required = false) Boolean returnImage) {
        return ToolJson.toJson(screenshotService.screenshot(uid, returnImage));
    }
}
