package solutions.crosstech.swingmcp.server.domain;

import solutions.crosstech.swingmcp.common.enums.AttachMode;

/**
 * Summary of the currently active application session.
 */
public record SessionInfo(
    AttachMode mode,
    long pid,
    int agentPort,
    String description
) {}
