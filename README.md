# 🌊 Fluid

🚀 A **tiny but agile** microservice framework built in **Java 24** with first-class support for **Docker 🐳**, **Kubernetes ☸️**, and **Kafka 📨** event streaming.  
Built for **speed, scale, and simplicity**.

---

## ✨ Features

✅ **Java 24**-powered lightweight core  
✅ 🔁 **Kafka-based event-driven architecture**  
✅ 🐳 **Docker-ready** containers  
✅ ☸️ **Kubernetes-deployable** out of the box  
✅ 🔍 Minimal boilerplate, maximum flexibility  
✅ 🔧 DIY microservice stack for builders and hackers   
✅ 😍 100% open source

---

## 📦 Getting Started

### Build Your Microservice 🛠️

Create a `Fluid.java` class:

```java
public class Fluid {
    public static void main(String[] args) throws InterruptedException {
        try {
            for (int i = 0; i < 1000; i++) {
                String key = "key-" + i;
                String message = "Message " + i;
                KafkaMessenger.sendMessage("test-topic1", message);
            }
        } finally {
            System.out.println("Shutting down Kafka producer...");
            KafkaMessenger.shutdown();
        }
        Thread.sleep(50000); // Keep the app alive for a bit
    }
}
```

---

### Create a Listener Service 🎧

```java
public class Main {
    public static void main(String[] args) throws InterruptedException {
        MessageService service = new MessageService();
        KafkaProcessor.processListeners(service);
        Thread.sleep(5000);
        KafkaProcessor.shutdown();
    }
}

public class MessageService {
    @KafkaListener(topic = "test-topic1", groupId = "test-group")
    public void handleMessage(String message) {
        System.out.println("🕒 Received at " + System.currentTimeMillis());
        System.out.println("📥 Message: " + message);
    }
}
```

## 🛠️ Architecture

```
[Fluid App] ---> [KafkaMessenger] ---> [Kafka Broker] ---> [KafkaProcessor] ---> [Your Listener]
```

* 🔄 Sends and receives messages through **Kafka**
* 🧩 Plug-n-play message handlers via `@KafkaListener`
* 🧵 Simple threading and lifecycle controls

---

## 🔮 Roadmap

* [x] 🧵 Async/parallel message handling
* [ ] 📊 Metrics (Prometheus or Micrometer)
* [x] 🛑 Graceful shutdown hooks
* [ ] 💾 Configuration via `fluid.yaml`
* [ ] 🧠 Built-in retry and backoff strategy

---

## 🤝 Contributing

PRs are welcome! Open an issue or suggest an improvement — let’s make microservices fun and fast again 🧪

---

## 📜 License

MIT License © 2025 Maifee Ul Asad