import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the metrics facade, the disabled path, and route topology.
 *
 * <p>No backend is involved: these run with metrics off, which is the state
 * every service is in unless it opts in, and therefore the one that must not
 * break.
 */
class MetricsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("metrics are disabled unless a backend is named")
    void disabledByDefault() {
        assertFalse(FluidMetrics.isEnabled());
        assertEquals("", FluidMetrics.configuredBackend());
    }

    @Test
    @DisplayName("defaults are sane when nothing is configured")
    void defaults() {
        assertEquals(9400, FluidMetrics.configuredPort());
        assertEquals("/metrics", FluidMetrics.configuredPath());
    }

    @Test
    @DisplayName("a name without the fluid_ prefix is rejected")
    void prefixIsEnforced() {
        assertEquals("fluid_thing", FluidMetrics.requirePrefixed("fluid_thing"));
        assertThrows(IllegalArgumentException.class, () -> FluidMetrics.requirePrefixed("thing"));
        assertThrows(IllegalArgumentException.class, () -> FluidMetrics.requirePrefixed(null));
        assertThrows(IllegalArgumentException.class,
            () -> FluidMetrics.requirePrefixed("myapp_fluid_thing"));
    }

    @Test
    @DisplayName("recording while disabled is safe and does nothing")
    void disabledRecordingIsSafe() {
        FrameworkMetrics metrics = FluidMetrics.framework();
        assertDoesNotThrow(() -> {
            metrics.messageConsumed("t", "g", "H.h", 10);
            metrics.messageProduced("t");
            metrics.handlerFailed("H.h", "t");
            metrics.handlerCompleted("H.h", 1_000_000L);
            metrics.partitionRewound("t", 0);
            metrics.consumersActive(2);
            metrics.producersActive(1);
            metrics.routeTaken(Route.declared("t", "H.h", Route.Kind.CONSUMES));
        });
    }

    @Test
    @DisplayName("every framework metric name carries the prefix")
    void frameworkNamesArePrefixed() {
        RecordingRecorder recorder = new RecordingRecorder();
        new FrameworkMetrics(recorder);

        assertFalse(recorder.names.isEmpty(), "no instruments were registered");
        recorder.names.forEach(FluidMetrics::requirePrefixed);
    }

    @Test
    @DisplayName("a declared route is published once, however often it is declared")
    void routesAreDeclaredOnce() {
        FrameworkMetrics metrics = new FrameworkMetrics(new RecordingRecorder());

        Route route = Route.declared("orders", "H.h", Route.Kind.CONSUMES);
        metrics.declareRoute(route);
        metrics.declareRoute(route);

        assertEquals(Set.of(route), metrics.declaredRoutes());
    }

    @Test
    @DisplayName("declaring a route also publishes both of its endpoints")
    void routesPublishTheirNodes() {
        FrameworkMetrics metrics = new FrameworkMetrics(new RecordingRecorder());

        metrics.declareRoute(Route.declared("orders", "H.handle", Route.Kind.CONSUMES));
        metrics.declareRoute(Route.declared("H.handle", "processed", Route.Kind.SEND_TO));

        assertEquals(Set.of("orders", "H.handle", "processed"), metrics.declaredNodes(),
            "a graph needs its vertices, not only its edges");
    }

    @Test
    @DisplayName("a dynamic route is distinguishable from a never-taken one")
    void dynamicRoutesAreMarked() {
        assertFalse(Route.declared("a", "b", Route.Kind.PRODUCES).dynamic());
        assertTrue(Route.discovered("a", "b", Route.Kind.PRODUCES).dynamic());
    }

    @Test
    @DisplayName("a route needs both endpoints")
    void routesNeedEndpoints() {
        assertThrows(IllegalArgumentException.class,
            () -> Route.declared("", "b", Route.Kind.CONSUMES));
        assertThrows(IllegalArgumentException.class,
            () -> Route.declared("a", null, Route.Kind.CONSUMES));
    }

    @Test
    @DisplayName("literal send targets are found in sources, computed ones are reported")
    void scansSourcesForSends() throws Exception {
        Files.writeString(dir.resolve("Sender.java"), """
            public class Sender {
                void go(String m) {
                    KafkaMessenger.sendMessage("audit-log", m);
                    KafkaMessenger.sendMessage("kafka:9092", "dead-letters", null, m);
                    KafkaMessenger.sendMessage(System.getenv("T"), m);
                }
            }
            """);

        TopologyScanner scanner = new TopologyScanner();
        Set<Route> routes = scanner.scanSources(dir.toFile());

        List<String> targets = routes.stream().map(Route::to).sorted().collect(Collectors.toList());
        assertEquals(List.of("audit-log", "dead-letters"), targets);
        assertTrue(routes.stream().allMatch(r -> r.kind() == Route.Kind.PRODUCES));

        assertEquals(List.of("Sender"), scanner.unresolvedSends(),
            "a computed topic must be reported, not silently dropped");
    }

    @Test
    @DisplayName("a directory with no sources yields no routes")
    void emptyDirectoryIsFine() {
        assertTrue(new TopologyScanner().scanSources(dir.toFile()).isEmpty());
    }

    @Test
    @DisplayName("annotations yield consume, send-to and short-circuit routes")
    void scansAnnotations() {
        Set<Route> routes = new TopologyScanner().scanAnnotations(List.of(new RoutedService()));

        assertTrue(routes.contains(
            Route.declared("orders", "RoutedService.handle", Route.Kind.CONSUMES)));
        assertTrue(routes.contains(
            Route.declared("RoutedService.handle", "processed", Route.Kind.SEND_TO)));
        assertTrue(routes.contains(
            Route.declared("RoutedService.handle", "errors", Route.Kind.SHORT_CIRCUIT)));
    }

    /** A service exercising all three annotation-derived route kinds. */
    public static class RoutedService {
        @KafkaListener(topic = "orders", groupId = "g")
        @SendTo(topic = "processed")
        @ShortCircuit(topic = "errors")
        public String handle(String message) {
            return message;
        }
    }

    @Test
    @DisplayName("no metrics dependencies are added unless a backend is configured")
    void noMetricsDependenciesByDefault() {
        assertTrue(DependencyDownloader.metricsDependencies().isEmpty());
    }

    /** Captures registered instrument names without needing a real backend. */
    private static final class RecordingRecorder implements MetricsRecorder {
        private final List<String> names = new java.util.ArrayList<>();

        @Override public Counter counter(MetricSpec spec) {
            names.add(spec.name());
            return new Counter() {
                @Override public void increment(String... l) { }
                @Override public void increment(double a, String... l) { }
            };
        }
        @Override public Gauge gauge(MetricSpec spec) { names.add(spec.name()); return (v, l) -> { }; }
        @Override public Histogram histogram(MetricSpec spec) { names.add(spec.name()); return (v, l) -> { }; }
        @Override public void start() { }
        @Override public void close() { }
    }
}
