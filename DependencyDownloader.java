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

            NodeList dependencyNodes = doc.getElementsByTagNameNS("*", "dependency");

            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Node depNode = dependencyNodes.item(i);
                if (depNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element depElement = (Element) depNode;

                    String groupId = getTagValue(depElement, "groupId");
                    String artifactId = getTagValue(depElement, "artifactId");
                    String version = getTagValue(depElement, "version");

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
        List<Dependency> allDependencies = addMissingMinimalDeps(pomDependencies);

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

    public static void main(String[] args) {
        String pomFile = "./pom.xml";
        String outputDir = "lib";
        downloadDependenciesFromPom(pomFile, outputDir);
    }
}