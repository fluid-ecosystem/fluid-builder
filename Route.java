import java.util.Locale;

/**
 * One directed edge in a service's message flow.
 *
 * @param from    handler or topic the message leaves
 * @param to      topic the message arrives at
 * @param kind    how the edge came to exist
 * @param dynamic true when the destination could not be determined before the
 *                edge was first used
 */
public record Route(String from, String to, Kind kind, boolean dynamic) {

    /**
     * Why an edge exists. Kept distinct because a viewer should be able to
     * tell an error path from a success path without inspecting names.
     */
    public enum Kind {
        /** A handler consuming a topic. */
        CONSUMES,
        /** A handler's return value forwarded by {@code @SendTo}. */
        SEND_TO,
        /** A handler's failure diverted by {@code @ShortCircuit}. */
        SHORT_CIRCUIT,
        /** A direct send through {@code KafkaMessenger}. */
        PRODUCES;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Route {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("route endpoints are required: " + from + " -> " + to);
        }
    }

    public static Route declared(String from, String to, Kind kind) {
        return new Route(from, to, kind, false);
    }

    /**
     * An edge whose destination was only known once it was used — a send to a
     * computed topic. It cannot appear as a declared route, so a viewer must
     * be able to distinguish "never taken" from "not knowable in advance".
     */
    public static Route discovered(String from, String to, Kind kind) {
        return new Route(from, to, kind, true);
    }

    @Override
    public String toString() {
        return from + " -" + kind.label() + "-> " + to + (dynamic ? " (dynamic)" : "");
    }
}
