package solutions.crosstech.swingmcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Describes a single Swing component node in a snapshot tree.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentDescriptor(
    String uid,
    String componentClass,
    String name,
    String text,
    boolean enabled,
    boolean visible,
    boolean focusable,
    int x,
    int y,
    int width,
    int height,
    String selectionState,
    List<ComponentDescriptor> children
) {}
