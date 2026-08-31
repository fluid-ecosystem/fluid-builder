import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * The framework's own instruments, exposed as operations rather than as raw
 * counters.
 *
 * <p>Call sites say what happened — {@code messageConsumed(...)} — instead of
 * reaching for an instrument and knowing its label order. That keeps label
 * ordering in one place, which is the part that is easy to get wrong and
 * silently produces mislabelled series.
 *
 * <p>Backed by whatever {@link MetricsRecorder} is active, including the no-op
 * one, so every method is safe to call when metrics are disabled.
 */
public final class FrameworkMetrics {

    private final MetricsRecorder.Counter messagesConsumed;
    private final MetricsRecorder.Counter bytesConsumed;
    private final MetricsRecorder.Counter messagesProduced;
    private final MetricsRecorder.Counter handlerFailures;
    private final MetricsRecorder.Counter partitionRewinds;
    private final MetricsRecorder.Histogram handlerDuration;
    private final MetricsRecorder.Gauge nodeDeclared;
    private final MetricsRecorder.Gauge routeDeclared;
    private final MetricsRecorder.Counter routeTraversed;
    private final MetricsRecorder.Gauge consumersActive;
    private final MetricsRecorder.Gauge producersActive;

    /** Declared routes are re-asserted on each scrape, so remember them. */
    private final Set<Route> declaredRoutes = ConcurrentHashMap.newKeySet();

    /** Endpoints seen so far, so each is published exactly once. */
    private final Set<String> declaredNodes = ConcurrentHashMap.newKeySet();

    FrameworkMetrics(MetricsRecorder recorder) {
        this.messagesConsumed = recorder.counter(MetricsRecorder.MetricSpec.of(
            "fluid_messages_consumed_total", "Records handed to a listener",
            "topic", "group", "handler"));
        this.bytesConsumed = recorder.counter(MetricsRecorder.MetricSpec.of(
            "fluid_bytes_consumed_total", "Record value bytes consumed", "topic"));
        this.messagesProduced = recorder.counter(MetricsRecorder.MetricSpec.of(
            "fluid_messages_produced_total", "Records handed to a producer", "topic"));
        this.handlerFailures = recorder.counter(MetricsRecorder.MetricSpec.of(
            "fluid_handler_failures_total", "Listener invocations that threw",
            "handler", "topic"));
        this.partitionRewinds = recorder.counter(MetricsRecorder.MetricSpec.of(
            "fluid_partition_rewinds_total",
            "Partitions rewound after a handler failure", "topic", "partition"));
        this.handlerDuration = recorder.histogram(MetricsRecorder.MetricSpec.of(
            "fluid_handler_duration_seconds", "Listener invocation time", "handler"));
        this.nodeDeclared = recorder.gauge(MetricsRecorder.MetricSpec.of(
            "fluid_node", "1 for every endpoint in the message flow", "id", "kind"));
        // `id` is redundant with from+to+type, and carried anyway: a renderer
        // needs a stable edge identity, and nothing downstream can build one
        // from separate labels. It adds no series, only a label.
        this.routeDeclared = recorder.gauge(MetricsRecorder.MetricSpec.of(
            "fluid_route_declared", "1 for every route this service can take",
            "id", "from", "to", "type", "dynamic"));
        this.routeTraversed = recorder.counter(MetricsRecorder.MetricSpec.of(
            "fluid_route_traversed_total", "Times a route actually carried a message",
            "id", "from", "to", "type", "dynamic"));
        this.consumersActive = recorder.gauge(MetricsRecorder.MetricSpec.of(
            "fluid_consumers_active", "Consumers currently polling"));
        this.producersActive = recorder.gauge(MetricsRecorder.MetricSpec.of(
            "fluid_producers_active", "Producers currently open"));
    }

    public void messageConsumed(String topic, String group, String handler, long bytes) {
        messagesConsumed.increment(topic, group, handler);
        bytesConsumed.increment(bytes, topic);
    }

    public void messageProduced(String topic) {
        messagesProduced.increment(topic);
    }

    public void handlerFailed(String handler, String topic) {
        handlerFailures.increment(handler, topic);
    }

    public void handlerCompleted(String handler, long nanos) {
        handlerDuration.observe(nanos / 1_000_000_000.0d, handler);
    }

    public void partitionRewound(String topic, int partition) {
        partitionRewinds.increment(topic, String.valueOf(partition));
    }

    public void consumersActive(int count) {
        consumersActive.set(count);
    }

    public void producersActive(int count) {
        producersActive.set(count);
    }

    /**
     * Publishes a route the service is capable of taking, whether or not it
     * ever has. A declared route with no traversals is a path that exists on
     * paper only.
     */
    public void declareRoute(Route route) {
        if (declaredRoutes.add(route)) {
            routeDeclared.set(1.0d, edgeId(route), route.from(), route.to(),
                route.kind().label(), String.valueOf(route.dynamic()));
        }
        declareNode(route.from(), endpointKind(route, true));
        declareNode(route.to(), endpointKind(route, false));
    }

    /**
     * Publishes an endpoint of the flow.
     *
     * <p>A graph needs its vertices, not only its edges. Edges alone leave a
     * renderer to infer the node set, which nothing downstream can reliably
     * do — so the nodes are stated rather than implied.
     */
    private void declareNode(String id, String kind) {
        if (declaredNodes.add(id)) {
            nodeDeclared.set(1.0d, id, kind);
        }
    }

    /**
     * Whether an endpoint is a topic or a handler.
     *
     * <p>Determined by position and edge kind: a handler consumes from a
     * topic and produces to one, so the side an endpoint sits on says what it
     * is.
     */
    private static String endpointKind(Route route, boolean fromSide) {
        boolean handler = switch (route.kind()) {
            case CONSUMES -> !fromSide;
            case SEND_TO, SHORT_CIRCUIT, PRODUCES -> fromSide;
        };
        return handler ? "handler" : "topic";
    }

    /** Records that a route actually carried a message. */
    public void routeTaken(Route route) {
        declareRoute(route);
        routeTraversed.increment(edgeId(route), route.from(), route.to(),
            route.kind().label(), String.valueOf(route.dynamic()));
    }

    /** Stable identity for an edge, used by renderers that need one. */
    private static String edgeId(Route route) {
        return route.from() + "|" + route.kind().label() + "|" + route.to();
    }

    public Set<Route> declaredRoutes() {
        return Set.copyOf(declaredRoutes);
    }

    public Set<String> declaredNodes() {
        return Set.copyOf(declaredNodes);
    }
}
