import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class Fluid {
    public static void main(String[] args) throws Exception {
        System.out.println("🌊 Fluid Framework Booting 🌀");

        List<Object> services = discoverServiceClasses();
        if (services.isEmpty()) {
            System.out.println("⚠️  No service classes found.");
        } else {
            System.out.println("✅ Discovered " + services.size() + " service(s):");
            services.forEach(svc -> System.out.println("  🔹 " + svc.getClass().getSimpleName()));
        }

        KafkaProcessor.processListeners(services.toArray());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("⛔ Shutting down Fluid...");
            KafkaProcessor.shutdown();
        }));

        // Keep it running
        Thread.sleep(60_000);
    }

    private static List<Object> discoverServiceClasses() throws Exception {
        List<Object> instances = new ArrayList<>();
        File currentDir = new File(".");

        for (File file : currentDir.listFiles((dir, name) -> name.endsWith("Service.class"))) {
            String className = file.getName().replace(".class", "");
            try {
                Class<?> clazz = Class.forName(className);
                if (!Modifier.isAbstract(clazz.getModifiers())) {
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    instances.add(instance);
                }
            } catch (Throwable t) {
                System.err.println("❌ Could not load " + className + ": " + t);
            }
        }

        return instances;
    }
}
