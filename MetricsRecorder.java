import java.util.List;

/**
 * Backend-neutral sink for framework metrics.
 *
 * <p>Implementations are loaded reflectively by {@link FluidMetrics}, so the
 * framework core carries no metrics dependency and an unconfigured service
 * pays nothing.
 *
 * <p>Instruments are <em>registered once</em> and then written to many times.
 * That two-phase shape is deliberate: Prometheus and Micrometer both require
 * label names to be fixed at registration, and an API that accepted arbitrary
 * label maps per call could not be implemented over either without cheating.
 *
 * <p>Nothing backend-specific may appear in this interface. If a Prometheus or
 * Micrometer type leaks through it, the abstraction has failed and a second
 * backend will not fit.
 */
public interface MetricsRecorder extends AutoCloseable {

    /** Registers a monotonically increasing counter. */
    Counter counter(MetricSpec spec);

    /** Registers a gauge whose value is supplied on each scrape. */
    Gauge gauge(MetricSpec spec);

    /** Registers a distribution of observed values. */
    Histogram histogram(MetricSpec spec);

    /**
     * Begins exposing metrics. Called once, after registration.
     *
     * @throws Exception if the endpoint cannot be started; the caller decides
     *         whether that is fatal
     */
    void start() throws Exception;

    /** Stops exposing metrics and releases resources. */
    @Override
    void close();

    /**
     * A counter.
     *
     * <p>Label values are positional and must match the order of
     * {@link MetricSpec#labelNames()}.
     */
    interface Counter {
        void increment(String... labelValues);

        void increment(double amount, String... labelValues);
    }

    /** A gauge that can move in either direction. */
    interface Gauge {
        void set(double value, String... labelValues);
    }

    /** A distribution of observed values. */
    interface Histogram {
        void observe(double value, String... labelValues);
    }

    /**
     * The identity of one instrument.
     *
     * @param name       full metric name, including the {@code fluid_} prefix
     * @param help       one-line description, surfaced by most backends
     * @param labelNames label names in the order values will be supplied
     */
    record MetricSpec(String name, String help, List<String> labelNames) {

        public MetricSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("metric name is required");
            }
            labelNames = labelNames == null ? List.of() : List.copyOf(labelNames);
        }

        public static MetricSpec of(String name, String help, String... labelNames) {
            return new MetricSpec(name, help, List.of(labelNames));
        }
    }
}
