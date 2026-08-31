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

**No build tool.** There is no Maven or Gradle step to ship a service. Your
`.java` files are copied into the image and compiled at container start.

---

## 📦 Getting Started

A service is a `Dockerfile`, a `pom.xml` listing your dependencies, and your
Java files. Nothing else.

```dockerfile
FROM maifeeulasad/fluid-builder:latest

COPY pom.xml .
COPY *.java .
```

At container start Fluid downloads the dependencies your `pom.xml` names,
compiles your sources, finds your listeners, and starts consuming.

### Write a listener 🎧

Any class with an annotated method is picked up — the file name does not
matter.

```java
public class MessageService {

    @KafkaListener(topic = "orders", groupId = "order-processors")
    public void handleOrder(String message) {
        System.out.println("📥 " + message);
    }
}
```

### Send messages 📤

To write a producer instead of a consumer, supply your own `Fluid.java`. It
replaces the framework's entry point.

```java
public class Fluid {
    public static void main(String[] args) throws InterruptedException {
        try {
            for (int i = 0; i < 1000; i++) {
                KafkaMessenger.sendMessage("orders", "Message " + i);
            }
        } finally {
            KafkaMessenger.shutdown();
        }
    }
}
```

---

## 🧩 Annotations

### `@KafkaListener` — the common case

```java
@KafkaListener(topic = "orders", groupId = "order-processors")
public void handleOrder(String message) { }
```

| Attribute | Default | |
|---|---|---|
| `topic` | required | topic to consume |
| `groupId` | required | consumer group to join |
| `bootstrapServers` | inherits | broker address; blank follows `BOOTSTRAP_SERVERS` |

### `@KafkaSubscription` — full control

Same job, with batching, dead letter routing, topic creation and consumer
tuning. Use it when you need one of those; `@KafkaListener` otherwise.

```java
@KafkaSubscription(
    topic = "orders",
    groupId = "order-processors",
    partitions = 5,
    batchEnabled = true,
    enableDeadLetterQueue = true,
    deadLetterTopic = "order-errors",
    maxPollRecords = 100
)
public void handleOrder(String message) { }
```

Some attributes are accepted but not yet honoured — each is marked
*not yet honoured* in its javadoc, so a setting that does nothing says so
rather than pretending.

A method may carry `@KafkaListener` **or** `@KafkaSubscription`, not both:
each drives its own consumer, so the handler would run twice per record.
Startup rejects it.

### `@SendTo` — forward the result

```java
@KafkaListener(topic = "orders", groupId = "order-processors")
@SendTo(topic = "processed-orders")
public String handleOrder(String message) {
    return "Processed: " + message;
}
```

### `@ShortCircuit` — route failures

```java
@KafkaListener(topic = "orders", groupId = "order-processors")
@ShortCircuit(topic = "order-errors")
public void handleOrder(String message) {
    if (message.contains("fail")) {
        throw new RuntimeException("bad message");
    }
}
```

---

## ⚙️ Configuration

| Variable | Default | |
|---|---|---|
| `BOOTSTRAP_SERVERS` | `kafka:9092` | broker address for everything that does not set one explicitly |
| `KAFKA_COMPRESSION_TYPE` | `gzip` | producer codec |
| `FLUID_METRICS` | unset (off) | metrics backend — currently `prometheus` |
| `FLUID_METRICS_PORT` | `9400` | scrape endpoint port |
| `FLUID_METRICS_PATH` | `/metrics` | scrape endpoint path |

`gzip` is the default because it is the only compressing codec that works
with the dependency set Fluid downloads. `snappy`, `lz4` and `zstd` need
third-party libraries that `kafka-clients` does not bundle — select one and
Fluid tells you which library to add rather than failing later at send time.

---

## 📊 Metrics

Off by default. Set one variable and the backend's jars are fetched at
container start — nothing to add to your `pom.xml`, and an image that does not
use metrics stays exactly as small as before.

```yaml
environment:
  - FLUID_METRICS=prometheus
```

Then scrape `http://your-service:9400/metrics`.

Everything is named `fluid_*`, and the prefix is enforced rather than merely
documented:

```
fluid_messages_consumed_total{topic,group,handler}
fluid_messages_produced_total{topic}
fluid_handler_duration_seconds{handler}
fluid_handler_failures_total{handler,topic}
fluid_partition_rewinds_total{topic,partition}
```

### Routes you can take, and routes you have taken

At start-up Fluid works out every route your service *can* take, before it
takes any — from `@KafkaListener`, `@SendTo` and `@ShortCircuit`, and by
parsing your sources for direct `KafkaMessenger.sendMessage("topic", ...)`
calls.

```
fluid_route_declared{from,to,type,dynamic}          1 for every possible route
fluid_route_traversed_total{from,to,type,dynamic}   times it actually carried a message
```

A route with `declared=1` and no traversals is a path that exists but has never
been used. A topic computed at runtime cannot be known in advance, so it is
marked `dynamic="true"` and appears only once used — which is why the two cases
are distinguishable rather than both looking like absence.

---

## 🛠️ Architecture

```
[Your Producer] ──▶ KafkaMessenger ──▶ [Kafka Broker] ──▶ MessageConsumer ──▶ [Your Listener]
                    MessageProducer                       KafkaProcessor
```

* 🧩 Handlers are found by annotation at startup, not by file name
* 🧵 Records are handled on the poll thread, so per-partition order holds
* ✅ Offsets are committed only after a record has been processed
* 🛑 A failing handler leaves its partition uncommitted and rewinds to retry,
  rather than committing past the failure

---

## 🧪 Building and testing

Run it the way it ships:

```bash
java DependencyDownloader.java   # populates lib/
java -cp "lib/*" Fluid.java
```

Run the tests:

```bash
mvn test
```

Maven exists for tests, linting and CI only — its output is never shipped,
and it compiles the same flat sources in place. See `DEVELOPMENT.md`.

---

## 🔮 Roadmap

* [x] 🛑 Graceful shutdown hooks
* [x] 🔁 At-least-once delivery with per-partition ordering
* [x] 💀 Dead letter routing
* [ ] 📊 Metrics (Prometheus or Micrometer)
* [ ] 💾 Configuration via `fluid.yaml`
* [ ] 🧠 Built-in retry and backoff strategy
* [ ] 🔀 Parallel handling that preserves per-partition order

---

## 🤝 Contributing

PRs are welcome! Open an issue or suggest an improvement — let's make
microservices fun and fast again 🧪

---

## 📜 License

MIT License © 2026 Maifee Ul Asad
