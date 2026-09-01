package solutions.crosstech.swingmcp.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the quote-aware launch-command tokenizer (1.2.0): paths and
 * arguments containing spaces must survive intact rather than being split.
 */
class CommandTokenizeTest {

    @Test
    void splitsOnWhitespace() {
        assertEquals(List.of("java", "-jar", "app.jar"),
            ApplicationService.tokenizeCommand("java -jar app.jar"));
    }

    @Test
    void keepsDoubleQuotedPathWithSpaces() {
        assertEquals(List.of("java", "-jar", "/Users/My App/app.jar"),
            ApplicationService.tokenizeCommand("java -jar \"/Users/My App/app.jar\""));
    }

    @Test
    void keepsSingleQuotedArg() {
        assertEquals(List.of("java", "-Dname=A B", "-jar", "app.jar"),
            ApplicationService.tokenizeCommand("java '-Dname=A B' -jar app.jar"));
    }

    @Test
    void collapsesRepeatedWhitespace() {
        assertEquals(List.of("java", "app.jar"),
            ApplicationService.tokenizeCommand("  java   app.jar  "));
    }
}
