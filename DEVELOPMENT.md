# 🛠️ Development Guide for `fluid-builder`

This guide covers how to build, tag, and push the Docker image for the `fluid-builder`.

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
