import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which routes a service is capable of taking, before it takes any.
 *
 * <p>Two sources, because neither alone is sufficient:
 *
 * <ul>
 *   <li><b>Annotations</b>, by reflection. {@code @KafkaListener} and
 *       {@code @KafkaSubscription} give the topics consumed; {@code @SendTo}
 *       and {@code @ShortCircuit} give the success and failure destinations.</li>
 *   <li><b>Source text</b>, by parsing. A direct
 *       {@code KafkaMessenger.sendMessage("topic", ...)} carries no annotation,
 *       so it is invisible to reflection and would otherwise only appear once
 *       it had already fired.</li>
 * </ul>
 *
 * <p>Parsing is possible because Fluid ships {@code .java} sources into the
 * image and the container carries a full JDK — {@code jdk.compiler} is present
 * and is what compiles those sources at start-up. No extra dependency is
 * involved.
 *
 * <p>A topic computed at runtime cannot be resolved here. Those are reported
 * by {@link #unresolvedSends()} so the gap is visible rather than silently
 * absent, and such edges surface later as dynamic routes when first used.
 */
public final class TopologyScanner {

    private static final Logger logger = LoggerFactory.getLogger(TopologyScanner.class);

    private static final String MESSENGER = "KafkaMessenger";
    private static final String SEND = "sendMessage";

    private final List<String> unresolvedSends = new ArrayList<>();

    /** Routes implied by the annotations on the discovered services. */
    public Set<Route> scanAnnotations(List<Object> services) {
        Set<Route> routes = new LinkedHashSet<>();

        for (Object service : services) {
            Class<?> type = service.getClass();
            for (Method method : type.getDeclaredMethods()) {
                String handler = type.getSimpleName() + "." + method.getName();

                String consumed = consumedTopic(method);
                if (consumed == null) {
                    continue;
                }
                routes.add(Route.declared(consumed, handler, Route.Kind.CONSUMES));

                SendTo sendTo = method.getAnnotation(SendTo.class);
                if (sendTo != null) {
                    routes.add(Route.declared(handler, sendTo.topic(), Route.Kind.SEND_TO));
                }

                ShortCircuit shortCircuit = method.getAnnotation(ShortCircuit.class);
                if (shortCircuit != null) {
                    routes.add(Route.declared(handler, shortCircuit.topic(),
                        Route.Kind.SHORT_CIRCUIT));
                }
            }
        }

        return routes;
    }

    private static String consumedTopic(Method method) {
        KafkaListener listener = method.getAnnotation(KafkaListener.class);
        if (listener != null) {
            return listener.topic();
        }
        KafkaSubscription subscription = method.getAnnotation(KafkaSubscription.class);
        return subscription == null ? null : subscription.topic();
    }

    /**
     * Routes implied by direct {@code KafkaMessenger.sendMessage} calls in the
     * sources found in {@code directory}.
     *
     * <p>Returns empty rather than failing when no compiler is available: a
     * missing topology is a degraded picture, not a reason to refuse to start.
     */
    public Set<Route> scanSources(File directory) {
        Set<Route> routes = new LinkedHashSet<>();

        File[] sources = directory.listFiles((dir, name) -> name.endsWith(".java"));
        if (sources == null || sources.length == 0) {
            return routes;
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            logger.warn("⚠️ No Java compiler available; direct sends will only appear "
                + "in the topology once they have been used.");
            return routes;
        }

        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(sources);
            // -proc:none: parsing only, so an annotation processor on the
            // classpath cannot run against the user's sources here.
            JavacTask task = (JavacTask) compiler.getTask(
                null, files, diagnostic -> { }, List.of("-proc:none"), null, units);

            for (CompilationUnitTree unit : task.parse()) {
                String origin = originOf(unit);
                unit.accept(new SendCollector(origin, routes, unresolvedSends), null);
            }
        } catch (Throwable t) {
            logger.warn("⚠️ Could not derive routes from sources: {}", t.toString());
        }

        return routes;
    }

    private static String originOf(CompilationUnitTree unit) {
        String name = new File(unit.getSourceFile().toUri().getPath()).getName();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    /** Sends whose topic is computed, so not knowable before the call runs. */
    public List<String> unresolvedSends() {
        return List.copyOf(unresolvedSends);
    }

    /** Collects literal topics from {@code sendMessage} invocations. */
    private static final class SendCollector extends TreeScanner<Void, Void> {

        private final String origin;
        private final Set<Route> routes;
        private final List<String> unresolved;

        SendCollector(String origin, Set<Route> routes, List<String> unresolved) {
            this.origin = origin;
            this.routes = routes;
            this.unresolved = unresolved;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            String callee = node.getMethodSelect().toString();
            if (callee.equals(SEND) || callee.endsWith(MESSENGER + "." + SEND)) {
                topicArgument(node.getArguments()).ifPresentOrElse(
                    topic -> routes.add(Route.declared(origin, topic, Route.Kind.PRODUCES)),
                    () -> unresolved.add(origin));
            }
            return super.visitMethodInvocation(node, unused);
        }

        /**
         * The topic argument, when it is a string literal.
         *
         * <p>Two overloads exist: {@code (topic, message)} and
         * {@code (bootstrapServers, topic, key, message)}, so the topic is
         * first or second depending on arity.
         */
        private static java.util.Optional<String> topicArgument(
                List<? extends ExpressionTree> arguments) {
            if (arguments.isEmpty()) {
                return java.util.Optional.empty();
            }
            ExpressionTree topic = arguments.size() >= 4 ? arguments.get(1) : arguments.get(0);
            if (topic instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                return java.util.Optional.of(value);
            }
            return java.util.Optional.empty();
        }
    }
}
