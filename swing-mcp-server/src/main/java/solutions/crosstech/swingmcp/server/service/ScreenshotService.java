package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.config.SwingMcpProperties;
import solutions.crosstech.swingmcp.server.domain.AgentCommandException;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Takes screenshots of the target application's windows or components and
 * stores them as PNG files in the configured screenshot directory.
 */
@Service
public class ScreenshotService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final SessionRegistry registry;
    private final SwingMcpProperties properties;

    public ScreenshotService(SessionRegistry registry, SwingMcpProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Takes a screenshot of the active window or a specific component.
     *
     * @param uid optional component UID; null for the whole active window
     * @return map with the saved file path and image dimensions
     */
    public Map<String, Object> screenshot(String uid) {
        return screenshot(uid, null);
    }

    /**
     * Takes a screenshot of the active window or a specific component.
     *
     * @param uid         optional component UID; null for the whole active window
     * @param returnImage when true, the base64-encoded PNG data is included in
     *                    the result for clients without local filesystem access
     * @return map with the saved file path, image dimensions, and optionally the image data
     */
    public Map<String, Object> screenshot(String uid, Boolean returnImage) {
        Map<String, Object> params = new HashMap<>();
        if (uid != null && !uid.isBlank()) {
            params.put("uid", uid);
        }
        Object result = registry.require().send(CommandType.TAKE_SCREENSHOT, params);
        if (!(result instanceof Map<?, ?> map)) {
            throw new AgentCommandException("Unexpected screenshot result: " + result);
        }
        String base64 = String.valueOf(map.get("imageBase64"));
        byte[] png = Base64.getDecoder().decode(base64);
        Path file = writePng(png);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toAbsolutePath().toString());
        out.put("mimeType", map.get("mimeType"));
        out.put("width", map.get("width"));
        out.put("height", map.get("height"));
        if (Boolean.TRUE.equals(returnImage)) {
            out.put("imageBase64", base64);
        }
        return out;
    }

    private Path writePng(byte[] png) {
        try {
            Path dir = Path.of(properties.getScreenshotDir());
            Files.createDirectories(dir);
            Path file = dir.resolve("screenshot-" + TS.format(LocalDateTime.now()) + ".png");
            Files.write(file, png);
            return file;
        } catch (IOException e) {
            throw new AgentCommandException("Failed to write screenshot: " + e.getMessage(), e);
        }
    }
}
