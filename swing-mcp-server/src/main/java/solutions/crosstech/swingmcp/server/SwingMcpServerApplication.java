package solutions.crosstech.swingmcp.server;

import solutions.crosstech.swingmcp.server.config.SwingMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Swing MCP server.
 * Runs as a non-web Spring Boot application exposing MCP tools over stdio.
 */
@SpringBootApplication
@EnableConfigurationProperties(SwingMcpProperties.class)
public class SwingMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwingMcpServerApplication.class, args);
    }
}
