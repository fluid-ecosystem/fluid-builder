import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers pom parsing only — nothing here reaches the network.
 *
 * <p>Regression cover for the CI break introduced when a {@code pom.xml} was
 * added to this repository: the downloader read {@code ${gson.version}}
 * literally and every coordinate 404'd.
 */
class DependencyDownloaderTest {

    @TempDir
    Path dir;

    private List<String> coordinatesOf(String pom) throws Exception {
        Path file = dir.resolve("pom.xml");
        Files.writeString(file, pom);
        return DependencyDownloader.readPomFile(file.toString())
            .stream()
            .map(Object::toString)
            .collect(Collectors.toList());
    }

    @Test
    @DisplayName("version properties are substituted")
    void resolvesVersionProperties() throws Exception {
        List<String> deps = coordinatesOf("""
            <project>
              <properties>
                <gson.version>2.8.9</gson.version>
                <kafka.version>3.7.1</kafka.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>com.google.code.gson</groupId>
                  <artifactId>gson</artifactId>
                  <version>${gson.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.apache.kafka</groupId>
                  <artifactId>kafka-clients</artifactId>
                  <version>${kafka.version}</version>
                </dependency>
              </dependencies>
            </project>
            """);

        assertEquals(List.of(
            "com.google.code.gson:gson:2.8.9",
            "org.apache.kafka:kafka-clients:3.7.1"), deps);
    }

    @Test
    @DisplayName("literal versions are untouched")
    void keepsLiteralVersions() throws Exception {
        List<String> deps = coordinatesOf("""
            <project><dependencies>
              <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>2.0.17</version>
              </dependency>
            </dependencies></project>
            """);

        assertEquals(List.of("org.slf4j:slf4j-api:2.0.17"), deps);
    }

    @Test
    @DisplayName("test and provided scopes are left out of the runtime set")
    void skipsNonRuntimeScopes() throws Exception {
        List<String> deps = coordinatesOf("""
            <project><dependencies>
              <dependency>
                <groupId>org.apache.kafka</groupId><artifactId>kafka-clients</artifactId>
                <version>3.7.1</version>
              </dependency>
              <dependency>
                <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <version>5.10.1</version><scope>test</scope>
              </dependency>
              <dependency>
                <groupId>x</groupId><artifactId>y</artifactId>
                <version>1.0</version><scope>provided</scope>
              </dependency>
            </dependencies></project>
            """);

        assertEquals(List.of("org.apache.kafka:kafka-clients:3.7.1"), deps);
    }

    @Test
    @DisplayName("an unresolved placeholder is left intact so the failure names it")
    void leavesUnknownPlaceholdersIntact() throws Exception {
        List<String> deps = coordinatesOf("""
            <project>
              <properties><known.version>1.0</known.version></properties>
              <dependencies>
                <dependency>
                  <groupId>g</groupId><artifactId>a</artifactId>
                  <version>${missing.version}</version>
                </dependency>
              </dependencies>
            </project>
            """);

        assertEquals(List.of("g:a:${missing.version}"), deps);
    }

    @Test
    @DisplayName("this repository's own pom resolves to the runtime dependency set")
    void thisRepositoryPomResolves() {
        List<String> deps = DependencyDownloader.readPomFile("pom.xml")
            .stream()
            .map(Object::toString)
            .collect(Collectors.toList());

        assertFalse(deps.isEmpty(), "pom.xml should yield dependencies");
        assertTrue(deps.stream().noneMatch(d -> d.contains("${")),
            "no placeholder may survive resolution: " + deps);
        assertTrue(deps.stream().noneMatch(d -> d.contains("junit") || d.contains("mockito")),
            "test-scoped artifacts are not runtime dependencies: " + deps);
    }
}
