package solutions.crosstech.swingmcp.server.service;

/**
 * Validation of required tool parameters, so a missing one is reported by name
 * instead of surfacing as a bare {@code NullPointerException} from {@code Map.of}.
 */
final class Params {

    private Params() {
    }

    /**
     * Returns {@code value}, or throws when it is null.
     *
     * @throws IllegalArgumentException naming the missing parameter
     */
    static <T> T require(String name, T value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        return value;
    }
}
