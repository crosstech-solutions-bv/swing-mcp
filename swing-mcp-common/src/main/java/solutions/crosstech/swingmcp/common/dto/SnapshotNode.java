package solutions.crosstech.swingmcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Root node of a Swing window component tree snapshot.
 *
 * <p>{@code truncated} is set when the snapshot was cut short by the
 * {@code maxNodes}/{@code maxDepth} limits, so a client knows the tree it
 * received is incomplete and can narrow the request (e.g. via
 * {@code find_component}) instead of assuming it saw everything.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotNode(
    String windowTitle,
    String windowClass,
    int x,
    int y,
    int width,
    int height,
    List<ComponentDescriptor> components,
    Boolean truncated
) {
    /** Backward-compatible constructor for a complete (non-truncated) snapshot. */
    public SnapshotNode(String windowTitle, String windowClass, int x, int y, int width, int height,
                        List<ComponentDescriptor> components) {
        this(windowTitle, windowClass, x, y, width, height, components, null);
    }
}
