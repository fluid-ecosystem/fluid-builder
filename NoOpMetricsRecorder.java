/**
 * The recorder used when metrics are disabled, which is the default.
 *
 * <p>Every instrument it hands back discards its input, so instrumentation
 * sites can call unconditionally without null checks or an {@code if enabled}
 * guard at every call site.
 */
final class NoOpMetricsRecorder implements MetricsRecorder {

    private static final Counter COUNTER = new Counter() {
        @Override public void increment(String... labelValues) { }
        @Override public void increment(double amount, String... labelValues) { }
    };

    private static final Gauge GAUGE = (value, labelValues) -> { };

    private static final Histogram HISTOGRAM = (value, labelValues) -> { };

    @Override public Counter counter(MetricSpec spec) { return COUNTER; }

    @Override public Gauge gauge(MetricSpec spec) { return GAUGE; }

    @Override public Histogram histogram(MetricSpec spec) { return HISTOGRAM; }

    @Override public void start() { }

    @Override public void close() { }
}
