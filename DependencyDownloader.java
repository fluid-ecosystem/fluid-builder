import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class DependencyDownloader {

    // Minimal required deps: groupId, artifactId, version
    private static final Dependency[] MINIMAL_REQUIRED_DEPS = new Dependency[] {
            new Dependency("com.google.code.gson", "gson", "2.8.9"),
            new Dependency("org.slf4j", "slf4j-api", "2.0.17"),
            new Dependency("org.slf4j", "slf4j-simple", "2.0.7"),
            new Dependency("org.apache.kafka", "kafka-clients", "3.7.1"),
            new Dependency("com.github.spotbugs", "spotbugs-annotations", "4.8.3")
    };

    static class Dependency {
        String groupId;
        String artifactId;
        String version;

        Dependency(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    public static List<Dependency> readPomFile(String pomFilePath) {
        List<Dependency> dependencies = new ArrayList<>();
        try {
            File file = new File(pomFilePath);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(true); // Needed for Maven XML
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(file);
            doc.getDocumentElement().normalize();

            Map<String, String> properties = readProperties(doc);
            NodeList dependencyNodes = doc.getElementsByTagNameNS("*", "dependency");

            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Node depNode = dependencyNodes.item(i);
                if (depNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element depElement = (Element) depNode;

                    // Test-scoped artifacts belong to the build, not the
                    // runtime the container assembles.
                    String scope = getTagValue(depElement, "scope");
                    if ("test".equals(scope) || "provided".equals(scope)) {
                        continue;
                    }

                    String groupId = resolve(getTagValue(depElement, "groupId"), properties);
                    String artifactId = resolve(getTagValue(depElement, "artifactId"), properties);
                    String version = resolve(getTagValue(depElement, "version"), properties);

                    dependencies.add(new Dependency(groupId, artifactId, version));
                }
            }
        } catch (FileNotFoundException e) {
            System.err.println("Error: File not found at " + pomFilePath);
        } catch (Exception e) {
            System.err.println("Error parsing XML in " + pomFilePath + ": " + e.getMessage());
        }
        return dependencies;
    }

    /**
     * Reads the pom's {@code <properties>} block.
     *
     * <p>Only direct children of {@code <properties>} are taken, so nested
     * configuration elements elsewhere in the pom cannot be mistaken for
     * version properties.
     */
    private static Map<String, String> readProperties(Document doc) {
        Map<String, String> properties = new HashMap<>();

        NodeList blocks = doc.getElementsByTagNameNS("*", "properties");
        for (int i = 0; i < blocks.getLength(); i++) {
            NodeList children = blocks.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    properties.put(child.getLocalName(), child.getTextContent().trim());
                }
            }
        }

        return properties;
    }

    /**
     * Substitutes {@code ${...}} placeholders against the pom's properties.
     *
     * <p>Real poms declare versions as properties, so without this a
     * coordinate reads literally as {@code gson-${gson.version}.jar} and the
     * download 404s. Placeholders with no matching property are left intact
     * so the failure names the unresolved key rather than a mangled URL.
     */
    private static String resolve(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }

        String resolved = value;
        // Bounded: properties may refer to other properties, but a cycle must
        // not spin here.
        for (int pass = 0; pass < 10 && resolved.contains("${"); pass++) {
            String before = resolved;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            if (resolved.equals(before)) {
                break;
            }
        }

        return resolved;
    }

    private static String getTagValue(Element element, String tag) {
        NodeList list = element.getElementsByTagNameNS("*", tag);
        if (list.getLength() > 0 && list.item(0).getTextContent() != null) {
            return list.item(0).getTextContent();
        }
        return null;
    }

    private static boolean isDependencyPresent(List<Dependency> dependencies, Dependency requiredDep) {
        for (Dependency dep : dependencies) {
            if (dep.groupId != null && dep.artifactId != null &&
                    dep.groupId.equals(requiredDep.groupId) &&
                    dep.artifactId.equals(requiredDep.artifactId)) {
                return true;
            }
        }
        return false;
    }

    private static List<Dependency> addMissingMinimalDeps(List<Dependency> pomDependencies) {
        List<Dependency> finalDependencies = new ArrayList<>(pomDependencies);

        for (Dependency requiredDep : MINIMAL_REQUIRED_DEPS) {
            if (!isDependencyPresent(pomDependencies, requiredDep)) {
                System.out.println("Adding missing minimal dependency: " + requiredDep);
                finalDependencies.add(requiredDep);
            }
        }

        return finalDependencies;
    }

    /**
     * Downloads one dependency into {@code outputDir}.
     *
     * @return true if the jar is present afterwards
     */
    public static boolean downloadDependency(Dependency dep, String outputDir) {
        String jarName = dep.artifactId + "-" + dep.version + ".jar";
        Path target = Paths.get(outputDir, jarName);

        try {
            Files.createDirectories(Paths.get(outputDir));

            if (Files.exists(target)) {
                System.out.println(jarName + " already present, skipping.");
                return true;
            }

            String path = dep.groupId.replace('.', '/') + "/" + dep.artifactId + "/" + dep.version;
            String url = "https://repo.maven.apache.org/maven2/" + path + "/" + jarName;
            System.out.println("Downloading " + jarName + " from " + url);

            // URI.create also validates, which `new URL(String)` did not. The
            // string is assembled from pom coordinates, so a malformed
            // groupId should fail here rather than as a mystery 404.
            HttpURLConnection conn =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);

            int status = conn.getResponseCode();
            if (status != 200) {
                System.err.println("Failed to download " + jarName + ": HTTP " + status);
                return false;
            }

            // Download beside the target and move into place, so an
            // interrupted transfer cannot leave a truncated jar that later
            // surfaces as an unreadable-class error.
            Path partial = Paths.get(outputDir, jarName + ".part");
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);

            System.out.println(jarName + " downloaded successfully.");
            return true;

        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Error downloading " + jarName + ": " + e.getMessage());
            return false;
        }
    }

    public static void downloadDependenciesFromPom(String pomFile, String outputDir) {
        List<Dependency> pomDependencies = readPomFile(pomFile);
        List<Dependency> allDependencies = new ArrayList<>(addMissingMinimalDeps(pomDependencies));

        for (Dependency dep : metricsDependencies()) {
            if (!isDependencyPresent(allDependencies, dep)) {
                allDependencies.add(dep);
            }
        }

        List<String> failed = new ArrayList<>();

        for (Dependency dep : allDependencies) {
            if (dep.groupId != null && dep.artifactId != null && dep.version != null) {
                if (!downloadDependency(dep, outputDir)) {
                    failed.add(dep.toString());
                }
            } else {
                System.err.println("Missing information for dependency: " + dep);
                failed.add(String.valueOf(dep));
            }
        }

        if (!failed.isEmpty()) {
            // Continuing would produce a partial `lib/`, and the real symptom
            // would arrive much later as NoClassDefFoundError with nothing
            // pointing back to the download.
            throw new IllegalStateException(
                "Could not download " + failed.size() + " dependency(ies): " + failed);
        }
    }

    /**
     * Extra dependencies pulled in only when a metrics backend is selected.
     *
     * <p>Metrics are off by default, so an image that does not use them stays
     * exactly as small as before. Setting FLUID_METRICS is the only step
     * needed to make them work.
     */
    private static final String PROMETHEUS_VERSION = "1.3.1";

    /**
     * Every artifact the Prometheus backend needs, listed in full.
     *
     * <p>This downloader resolves no transitive dependencies — it fetches
     * exactly what it is told. Naming only `prometheus-metrics-core` and the
     * HTTP exporter would leave nine jars missing and the backend would fail
     * at class-load time with a message pointing at the wrong thing.
     */
    private static final Map<String, Dependency[]> METRICS_BACKEND_DEPS = Map.of(
        "prometheus", prometheus(
            "prometheus-metrics-core",
            "prometheus-metrics-model",
            "prometheus-metrics-config",
            "prometheus-metrics-exposition-formats",
            "prometheus-metrics-shaded-protobuf",
            "prometheus-metrics-tracer-common",
            "prometheus-metrics-tracer-initializer",
            "prometheus-metrics-tracer-otel",
            "prometheus-metrics-tracer-otel-agent",
            "prometheus-metrics-exporter-common",
            "prometheus-metrics-exporter-httpserver"));

    private static Dependency[] prometheus(String... artifactIds) {
        Dependency[] deps = new Dependency[artifactIds.length];
        for (int i = 0; i < artifactIds.length; i++) {
            deps[i] = new Dependency("io.prometheus", artifactIds[i], PROMETHEUS_VERSION);
        }
        return deps;
    }

    /** Backend dependencies for the configured metrics type, if any. */
    static List<Dependency> metricsDependencies() {
        String backend = System.getenv("FLUID_METRICS");
        if (backend == null || backend.isBlank()) {
            return List.of();
        }

        Dependency[] deps = METRICS_BACKEND_DEPS.get(backend.trim().toLowerCase());
        if (deps == null) {
            System.err.println("Unknown FLUID_METRICS=" + backend
                + "; known backends are " + METRICS_BACKEND_DEPS.keySet());
            return List.of();
        }

        System.out.println("Metrics backend '" + backend.trim()
            + "' selected; adding its dependencies.");
        return List.of(deps);
    }

    public static void main(String[] args) {
        String pomFile = "./pom.xml";
        String outputDir = "lib";
        downloadDependenciesFromPom(pomFile, outputDir);
    }
}