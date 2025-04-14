import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DependencyDownloader {

    static class Dependency {
        String groupId;
        String artifactId;
        String version;

        Dependency(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
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

    public static void downloadDependency(Dependency dep, String outputDir) {
        try {
            Files.createDirectories(Paths.get(outputDir));
            String path = dep.groupId.replace('.', '/') + "/" + dep.artifactId + "/" + dep.version;
            String jarName = dep.artifactId + "-" + dep.version + ".jar";
            String url = "https://repo.maven.apache.org/maven2/" + path + "/" + jarName;
            System.out.println("Downloading " + jarName + " from " + url);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                InputStream in = conn.getInputStream();
                Files.copy(in, Paths.get(outputDir, jarName));
                in.close();
                System.out.println(jarName + " downloaded successfully.");
            } else {
                System.out.println("Failed to download " + jarName);
            }

        } catch (IOException e) {
            System.err.println("Error downloading dependency: " + e.getMessage());
        }
    }

    public static void downloadDependenciesFromPom(String pomFile, String outputDir) {
        List<Dependency> dependencies = readPomFile(pomFile);
        for (Dependency dep : dependencies) {
            if (dep.groupId != null && dep.artifactId != null && dep.version != null) {
                downloadDependency(dep, outputDir);
            } else {
                System.out.println("Missing information for dependency: " + dep);
            }
        }
    }

    public static void main(String[] args) {
        String pomFile = "./pom.xml";
        String outputDir = "lib";
        downloadDependenciesFromPom(pomFile, outputDir);
    }
}