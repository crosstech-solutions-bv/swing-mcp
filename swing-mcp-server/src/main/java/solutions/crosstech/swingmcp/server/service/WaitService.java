package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Waits for UI conditions (window title, component text/visibility/enabled state)
 * in the target application.
 */
@Service
public class WaitService {

    private final SessionRegistry registry;

    public WaitService(SessionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Waits until a condition is met or the timeout elapses.
     *
     * @param conditionType one of WINDOW_TITLE, COMPONENT_TEXT, COMPONENT_VISIBLE, COMPONENT_ENABLED
     * @param uid           component UID (required for component conditions)
     * @param expectedValue expected value for the condition
     * @param timeoutMs     optional timeout in milliseconds
     */
    public Object waitFor(String conditionType, String uid, String expectedValue, Long timeoutMs) {
        Map<String, Object> params = new HashMap<>();
        params.put("conditionType", conditionType);
        if (uid != null) {
            params.put("uid", uid);
        }
        if (expectedValue != null) {
            params.put("expectedValue", expectedValue);
        }
        if (timeoutMs != null) {
            params.put("timeoutMs", timeoutMs);
        }
        return registry.require().send(CommandType.WAIT_FOR, params);
    }
}
