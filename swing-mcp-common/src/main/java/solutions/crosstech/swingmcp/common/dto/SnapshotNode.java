package solutions.crosstech.swingmcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Root node of a Swing window component tree snapshot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotNode(
    String windowTitle,
    String windowClass,
    int x,
    int y,
    int width,
    int height,
    List<ComponentDescriptor> components
) {}
