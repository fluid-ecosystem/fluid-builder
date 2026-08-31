import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the source-text pre-filter.
 *
 * <p>Under the source launcher, loading a class compiles it, and a
 * compilation failure aborts the process rather than throwing. An unrelated
 * file that cannot compile must therefore never be loaded at all — the guard
 * has to be a text check, before any class loading.
 */
class FluidDiscoveryTest {

    @TempDir
    Path dir;

    private Path write(String name, String body) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, body);
        return file;
    }

    @Test
    @DisplayName("a file declaring @KafkaListener is considered")
    void listenerIsConsidered() throws Exception {
        Path f = write("OrderHandler.java", """
            public class OrderHandler {
                @KafkaListener(topic = "t", groupId = "g")
                public void h(String m) { }
            }
            """);
        assertTrue(Fluid.mentionsListener(f.toFile()));
    }

    @Test
    @DisplayName("a file declaring @KafkaSubscription is considered")
    void subscriptionIsConsidered() throws Exception {
        Path f = write("Tuned.java", """
            public class Tuned {
                @KafkaSubscription(topic = "t", groupId = "g")
                public void h(String m) { }
            }
            """);
        assertTrue(Fluid.mentionsListener(f.toFile()));
    }

    @Test
    @DisplayName("an unrelated file is never loaded, however broken it is")
    void unrelatedBrokenFileIsSkipped() throws Exception {
        Path f = write("NeedsMissingJar.java", """
            import com.example.totally.Absent;
            public class NeedsMissingJar { public void x() { new Absent(); } }
            """);
        assertFalse(Fluid.mentionsListener(f.toFile()),
            "loading this would abort the process, not throw");
    }

    @Test
    @DisplayName("an ordinary class without listeners is skipped")
    void plainClassIsSkipped() throws Exception {
        Path f = write("Plain.java", "public class Plain { public void x() { } }");
        assertFalse(Fluid.mentionsListener(f.toFile()));
    }

    @Test
    @DisplayName("an unreadable path is skipped rather than throwing")
    void missingFileIsSkipped() {
        assertFalse(Fluid.mentionsListener(dir.resolve("NoSuchFile.java").toFile()));
    }
}
