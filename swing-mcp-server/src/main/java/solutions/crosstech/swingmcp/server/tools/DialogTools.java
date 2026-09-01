package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.DialogService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for handling modal dialogs of the target application.
 */
@Component
public class DialogTools {

    private final DialogService dialogService;

    public DialogTools(DialogService dialogService) {
        this.dialogService = dialogService;
    }

    @Tool(name = "list_dialogs", description = """
        List currently open dialogs with their window index, type (option, fileChooser, custom), \
        title, message, modality, and available buttons.""")
    public String listDialogs() {
        return ToolJson.toJson(dialogService.listDialogs());
    }

    @Tool(name = "handle_dialog", description = """
        Respond to an open dialog: click a named button (e.g. "OK", "Cancel", "Yes") \
        or set a file path in a JFileChooser. Targets the first open dialog unless \
        a window index is given.""")
    public String handleDialog(
            @ToolParam(description = "Text of the dialog button to click", required = false) String button,
            @ToolParam(description = "File path to select in a JFileChooser", required = false) String filePath,
            @ToolParam(description = "Optional window index of the dialog (from list_dialogs)", required = false) Integer windowIndex) {
        return ToolJson.toJson(dialogService.handleDialog(button, filePath, windowIndex));
    }
}
