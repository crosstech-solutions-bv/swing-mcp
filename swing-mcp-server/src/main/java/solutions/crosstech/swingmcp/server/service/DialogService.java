package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Dialog handling operations on the target application: listing open dialogs
 * and responding to them (clicking a button or selecting a file).
 */
@Service
public class DialogService {

    private final SessionRegistry registry;

    public DialogService(SessionRegistry registry) {
        this.registry = registry;
    }

    /** Lists currently open dialogs with type, title, message, and buttons. */
    public Object listDialogs() {
        return registry.require().send(CommandType.LIST_DIALOGS, Map.of());
    }

    /**
     * Responds to an open dialog by clicking a button or setting a file path.
     *
     * @param button      text of the button to click (e.g. "OK", "Cancel")
     * @param filePath    file path to select in a JFileChooser
     * @param windowIndex optional index of the dialog window
     */
    public Object handleDialog(String button, String filePath, Integer windowIndex) {
        Map<String, Object> params = new HashMap<>();
        if (button != null && !button.isBlank()) {
            params.put("button", button);
        }
        if (filePath != null && !filePath.isBlank()) {
            params.put("filePath", filePath);
        }
        if (windowIndex != null) {
            params.put("windowIndex", windowIndex);
        }
        return registry.require().send(CommandType.HANDLE_DIALOG, params);
    }
}
