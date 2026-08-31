import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Entry point for framework and user metrics.
 *
 * <p>Disabled unless {@code FLUID_METRICS} names a backend. When disabled the
 * active recorder is {@link NoOpMetricsRecorder}, so instrumentation sites
 * never branch on whether metrics are on.
 *
 * <p>Backends are resolved by name and instantiated reflectively. That is what
 * keeps the framework core free of any metrics dependency: a service that does
 * not enable metrics never loads a metrics class, and the jars are not even
 * downloaded.
 */
public final class FluidMetrics {

    private static final Logger logger = LoggerFactory.getLogger(FluidMetrics.class);

    /** Every metric this framework exposes carries this prefix. */
    public static final String PREFIX = "fluid_";

    public static final String METRICS_ENV = "FLUID_METRICS";
    public static final String PORT_ENV = "FLUID_METRICS_PORT";
    public static final String PATH_ENV = "FLUID_METRICS_PATH";

    /**
     * Address of a Prometheus Pushgateway, e.g. {@code pushgateway:9091}.
     *
     * <p>A pull-only setup cannot observe a process that exits: a producer
     * that finishes its batch and stops takes its endpoint with it, and a
     * scrape that never coincided with its lifetime records nothing. Pushing
     * on shutdown closes that gap.
     */
    public static final String PUSHGATEWAY_ENV = "FLUID_METRICS_PUSHGATEWAY";

    /** Job label attached to pushed metrics. */
    public static final String JOB_ENV = "FLUID_METRICS_JOB";

    public static final int DEFAULT_PORT = 9400;
    public static final String DEFAULT_PATH = "/metrics";

    /**
     * Backend name to implementing class. The values are referenced by name
     * rather than by type so that selecting one does not drag the others — or
     * their dependencies — onto the classpath.
     */
    private static final java.util.Map<String, String> BACKENDS = java.util.Map.of(
        "prometheus", "PrometheusMetricsRecorder");

    private static volatile MetricsRecorder recorder = new NoOpMetricsRecorder();
    private static volatile FrameworkMetrics framework = new FrameworkMetrics(recorder);
    private static volatile boolean enabled;
    private static volatile boolean initialised;

    private FluidMetrics() {
    }

    /** The active recorder; never null. */
    public static MetricsRecorder recorder() {
        return recorder;
    }

    /**
     * The framework's own instruments. Safe to call when metrics are
     * disabled — it is then backed by the no-op recorder.
     *
     * <p>Initialises on first use if a backend is configured and
     * {@link #initialise()} has not already run. A service supplying its own
     * {@code Fluid.java} — a producer, typically — replaces the framework's
     * entry point and so never reaches the explicit call. Requiring such a
     * service to know it must initialise metrics would make "out of the box"
     * untrue for exactly the case that looks simplest.
     */
    public static FrameworkMetrics framework() {
        if (!initialised) {
            initialise();
        }
        return framework;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Backend selected by {@link #METRICS_ENV}, or blank when disabled. */
    public static String configuredBackend() {
        String configured = System.getenv(METRICS_ENV);
        return configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
    }

    public static int configuredPort() {
        return intFromEnv(PORT_ENV, DEFAULT_PORT);
    }

    /** Pushgateway address, or blank when pushing is not configured. */
    public static String configuredPushgateway() {
        String configured = System.getenv(PUSHGATEWAY_ENV);
        return configured == null ? "" : configured.trim();
    }

    /**
     * Job label for pushed metrics. Defaults to the hostname, which in a
     * container is the container id — enough to tell two instances apart.
     */
    public static String configuredJob() {
        String configured = System.getenv(JOB_ENV);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank()) ? "fluid" : host.trim();
    }

    public static String configuredPath() {
        String configured = System.getenv(PATH_ENV);
        return (configured == null || configured.isBlank()) ? DEFAULT_PATH : configured.trim();
    }

    /**
     * Installs the configured backend, or leaves metrics disabled.
     *
     * <p>A backend that cannot be started is reported and the framework
     * continues with metrics off. Observability failing should not take down
     * the service it is observing.
     */
    public static synchronized void initialise() {
        if (initialised) {
            return;
        }
        initialised = true;

        String backend = configuredBackend();
        if (backend.isEmpty()) {
            logger.debug("Metrics disabled; set {} to enable", METRICS_ENV);
            return;
        }

        String className = BACKENDS.get(backend);
        if (className == null) {
            logger.error("⚠️ Unknown {}={}; known backends are {}. Metrics stay disabled.",
                METRICS_ENV, backend, BACKENDS.keySet());
            return;
        }

        try {
            MetricsRecorder created = (MetricsRecorder) Class.forName(className)
                .getDeclaredConstructor().newInstance();
            created.start();
            recorder = created;
            framework = new FrameworkMetrics(created);
            enabled = true;

            // The framework's own shutdown path calls shutdown(), but a
            // service supplying its own Fluid.java never reaches it. Without
            // this hook a batch producer would exit having pushed nothing,
            // which is precisely the case the Pushgateway exists for.
            Runtime.getRuntime().addShutdownHook(new Thread(FluidMetrics::shutdown,
                "fluid-metrics-shutdown"));

            logger.info("📊 Metrics enabled via {} on port {}{}",
                backend, configuredPort(), configuredPath());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            logger.error("⚠️ {} backend selected but its library is not on the classpath. "
                + "DependencyDownloader adds it when {} is set at build time; metrics stay disabled.",
                backend, METRICS_ENV);
        } catch (Throwable t) {
            logger.error("⚠️ Could not start the {} metrics backend; metrics stay disabled: {}",
                backend, t.getMessage(), t);
        }
    }

    /** Stops the active backend and reverts to the no-op recorder. */
    public static synchronized void shutdown() {
        MetricsRecorder active = recorder;
        recorder = new NoOpMetricsRecorder();
        framework = new FrameworkMetrics(recorder);
        enabled = false;
        initialised = false;
        try {
            active.close();
        } catch (Exception e) {
            logger.error("Error closing metrics backend: {}", e.getMessage(), e);
        }
    }

    /**
     * Rejects a name that does not carry {@link #PREFIX}.
     *
     * <p>Enforced rather than documented: a convention that is only written
     * down gets broken, and a metric namespace is very hard to change once
     * dashboards depend on it.
     */
    public static String requirePrefixed(String name) {
        if (name == null || !name.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                "metric name must start with '" + PREFIX + "': " + name);
        }
        return name;
    }

    private static int intFromEnv(String key, int fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("⚠️ {}={} is not a number; using {}", key, raw, fallback);
            return fallback;
        }
    }
}
