import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Prometheus backend for {@link MetricsRecorder}.
 *
 * <p>Every call into the Prometheus client is made reflectively, and that is
 * deliberate rather than lazy. Fluid ships its sources into the image and the
 * source launcher compiles them on demand; a compilation failure aborts the
 * process. If this class named Prometheus types directly it would fail to
 * compile whenever the library is absent — which is the normal case, since
 * metrics are off by default — and would take down every service that had not
 * enabled them.
 *
 * <p>Reflection is confined to this one class. Everything above it works
 * against {@link MetricsRecorder}, so a second backend needs none of this.
 *
 * <p>Requires {@code io.prometheus:prometheus-metrics-core} and
 * {@code prometheus-metrics-exporter-httpserver}, which
 * {@link DependencyDownloader} adds when {@code FLUID_METRICS=prometheus}.
 */
public final class PrometheusMetricsRecorder implements MetricsRecorder {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusMetricsRecorder.class);

    private static final String REGISTRY = "io.prometheus.metrics.model.registry.PrometheusRegistry";
    private static final String COUNTER = "io.prometheus.metrics.core.metrics.Counter";
    private static final String GAUGE = "io.prometheus.metrics.core.metrics.Gauge";
    private static final String HISTOGRAM = "io.prometheus.metrics.core.metrics.Histogram";
    private static final String HTTP_SERVER = "io.prometheus.metrics.exporter.httpserver.HTTPServer";

    private final Object registry;
    private final java.util.concurrent.atomic.AtomicBoolean recordingFailureReported =
        new java.util.concurrent.atomic.AtomicBoolean();
    private Object httpServer;

    public PrometheusMetricsRecorder() throws Exception {
        this.registry = Class.forName(REGISTRY).getDeclaredConstructor().newInstance();
    }

    @Override
    public Counter counter(MetricSpec spec) {
        Object metric = build(COUNTER, spec);
        return new Counter() {
            @Override
            public void increment(String... labelValues) {
                increment(1.0d, labelValues);
            }

            @Override
            public void increment(double amount, String... labelValues) {
                invokeOnPoint(metric, spec, labelValues, "inc", amount);
            }
        };
    }

    @Override
    public Gauge gauge(MetricSpec spec) {
        Object metric = build(GAUGE, spec);
        return (value, labelValues) -> invokeOnPoint(metric, spec, labelValues, "set", value);
    }

    @Override
    public Histogram histogram(MetricSpec spec) {
        Object metric = build(HISTOGRAM, spec);
        return (value, labelValues) -> invokeOnPoint(metric, spec, labelValues, "observe", value);
    }

    @Override
    public void start() throws Exception {
        Class<?> serverType = Class.forName(HTTP_SERVER);
        Object builder = serverType.getMethod("builder").invoke(null);
        Class<?> builderType = builder.getClass();

        builderType.getMethod("port", int.class).invoke(builder, FluidMetrics.configuredPort());
        builderType.getMethod("registry", Class.forName(REGISTRY)).invoke(builder, registry);
        httpServer = builderType.getMethod("buildAndStart").invoke(builder);
    }

    @Override
    public void close() {
        if (httpServer == null) {
            return;
        }
        try {
            httpServer.getClass().getMethod("close").invoke(httpServer);
        } catch (Exception e) {
            logger.error("Error stopping the metrics endpoint: {}", e.getMessage(), e);
        } finally {
            httpServer = null;
        }
    }

    /** Builds and registers one instrument. */
    private Object build(String metricType, MetricSpec spec) {
        try {
            Class<?> type = Class.forName(metricType);
            Object builder = type.getMethod("builder").invoke(null);

            invokeNamed(builder, "name", String.class, spec.name());
            if (spec.help() != null && !spec.help().isBlank()) {
                invokeNamed(builder, "help", String.class, spec.help());
            }
            if (!spec.labelNames().isEmpty()) {
                invokeNamed(builder, "labelNames", String[].class,
                    (Object) spec.labelNames().toArray(String[]::new));
            }

            Method register = findMethod(builder.getClass(), "register", 1);
            return register.invoke(builder, registry);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Could not register Prometheus metric " + spec.name(), e);
        }
    }

    /**
     * Builder methods are declared on a package-private superclass and
     * covariantly overridden, so the method has to be located by name rather
     * than looked up on the concrete type.
     */
    private static void invokeNamed(Object builder, String name, Class<?> argType, Object arg)
            throws Exception {
        Method method = findMethodWithArg(builder.getClass(), name, argType);
        method.setAccessible(true);
        method.invoke(builder, arg);
    }

    /**
     * Finds a single-argument method, searching interfaces as well as
     * superclasses.
     *
     * <p>Data points are returned as interface types and declare their
     * {@code inc} / {@code set} / {@code observe} methods on those interfaces
     * rather than on the implementing class. A superclass-only walk finds
     * nothing, and because recording failures are swallowed, every labelled
     * sample is then silently dropped while the series still appears at zero.
     */
    private static Method findMethodWithArg(Class<?> type, String name, Class<?> argType) {
        Method found = searchWithArg(type, name, argType);
        if (found != null) {
            found.setAccessible(true);
            return found;
        }
        throw new IllegalStateException("No " + name + "(" + argType.getSimpleName()
            + ") on " + type.getName());
    }

    private static Method searchWithArg(Class<?> type, String name, Class<?> argType) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(argType)) {
                    return m;
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                Method viaInterface = searchWithArg(i, name, argType);
                if (viaInterface != null) {
                    return viaInterface;
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int argCount) {
        Method found = search(type, name, argCount);
        if (found != null) {
            found.setAccessible(true);
            return found;
        }
        throw new IllegalStateException("No " + name + "/" + argCount + " on " + type.getName());
    }

    private static Method search(Class<?> type, String name, int argCount) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                    return m;
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                Method viaInterface = search(i, name, argCount);
                if (viaInterface != null) {
                    return viaInterface;
                }
            }
        }
        return null;
    }

    /**
     * Applies an operation to the right data point.
     *
     * <p>A labelled metric is written through {@code labelValues(...)}; an
     * unlabelled one is written directly, because the client offers no
     * zero-argument data point.
     */
    private void invokeOnPoint(Object metric, MetricSpec spec, String[] labelValues,
                               String operation, double value) {
        try {
            Object target = metric;
            List<String> names = spec.labelNames();
            if (!names.isEmpty()) {
                if (labelValues.length != names.size()) {
                    logger.warn("⚠️ {} expects {} label values {} but got {}; ignoring sample",
                        spec.name(), names.size(), names, labelValues.length);
                    return;
                }
                Method labelled = findMethod(metric.getClass(), "labelValues", 1);
                target = labelled.invoke(metric, (Object) labelValues);
            }
            findMethodWithArg(target.getClass(), operation, double.class).invoke(target, value);
        } catch (Exception e) {
            // A metric must never break the thing it measures, so this is not
            // rethrown. It is reported once at warn rather than only at debug:
            // a silently dropped sample still publishes the series, so the
            // failure looks exactly like a legitimate zero. That is precisely
            // how an earlier defect here went unnoticed.
            if (recordingFailureReported.compareAndSet(false, true)) {
                logger.warn("⚠️ Could not record {} on {}; samples are being dropped "
                    + "and affected series will read as zero: {}",
                    operation, spec.name(), e.toString(), e);
            } else {
                logger.debug("Could not record {} on {}: {}", operation, spec.name(), e.toString());
            }
        }
    }
}
