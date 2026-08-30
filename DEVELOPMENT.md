# 🛠️ Development Guide for `fluid-builder`

This guide covers how to build and test `fluid-builder`, and how to build, tag
and push its Docker image.

---

## 🏗️ Two build paths, one set of sources

The framework sources are flat, package-less `.java` files at the repository
root. There is no `src/main/java`, and there is no copy of them anywhere.

**The source launcher is how the framework actually runs.** The Docker image
copies the `.java` files in and compiles them at container start:

```
CMD java DependencyDownloader.java && java -cp $(java ListDependency.java) Fluid.java
```

**Maven exists for tests, linting and CI only.** Its output is never shipped.
It compiles the same root files in place via `<sourceDirectory>${project.basedir}</sourceDirectory>`,
so the two paths can never drift apart.

### Run it the way it ships

```bash
java DependencyDownloader.java          # populates lib/
java -cp "lib/*" Fluid.java
```

### Run the tests

```bash
mvn test
```

Requires JDK 24 or newer. Test sources live in `src/test/java` and are
package-less, matching the framework sources.

---

## ⚠️ Two constraints worth knowing

**The compiler release is pinned to 24, not the newest JDK available.** The
container base image is Java 24 and compiles the sources itself at start-up,
so a Java 25+ construct that Maven accepted would break the container at
runtime with nothing to catch it. `<maven.compiler.release>24</maven.compiler.release>`
is what prevents that.

**The POM deliberately excludes transitive dependencies.** `kafka-clients`
pulls `snappy-java`, `lz4-java` and `zstd-jni`; `spotbugs-annotations` pulls
`jsr305`. `DependencyDownloader` fetches none of them, so leaving them in
would give Maven a classpath the container does not have — `compression.type=snappy`
would pass CI and fail in production. They are excluded so the build tests
what actually ships.

The dependency list therefore exists in two places that must stay in step:

| Where | What it feeds |
|---|---|
| `DependencyDownloader.MINIMAL_REQUIRED_DEPS` | the running container |
| `pom.xml` `<dependencies>` | tests and CI |

Adding a dependency means adding it to **both**.

Verify they still agree:

```bash
mvn dependency:tree | grep -E "compile|runtime"
```

---

## 🧨 Name collisions

With no packages, every top-level class competes with every imported type.
`KafkaProducer` and `KafkaConsumer` are the obvious traps — the framework's
own client wrappers are named `MessageProducer` and `MessageConsumer`
precisely to stay clear of `org.apache.kafka.clients.*`.

The same applies to annotations: `@KafkaListener` is Fluid's own, and shares
its simple name with Spring's. `@KafkaSubscription` is the fuller form.

---

## 📦 Build the Docker Image

Build the `fluid-builder` image from the local `Dockerfile`:

```bash
docker build -t fluid-builder -f Dockerfile .
````

---

## 🏷️ Tag & Push to Docker Hub

Tag the image with different versions and push to your Docker Hub repository.

### 🔄 Push as `latest`

```bash
docker tag fluid-builder maifeeulasad/fluid-builder:latest
docker push maifeeulasad/fluid-builder:latest
```

### 📌 Push as `v1.0.0`

```bash
docker tag fluid-builder maifeeulasad/fluid-builder:v1.0.0
docker push maifeeulasad/fluid-builder:v1.0.0
```

### 📅 Push with date-based version `v20250523`

```bash
docker tag fluid-builder maifeeulasad/fluid-builder:v20250523
docker push maifeeulasad/fluid-builder:v20250523
```

---

## ✅ Notes

* Ensure you are logged in to Docker Hub:

  ```bash
  docker login
  ```


* We follow both semantic versioning (`vX.Y.Z`) or date-based versioning (`vYYYYMMDD`) for consistency.

---

Happy Hacking 🚀
