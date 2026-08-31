FROM maifeeulasad/java:24

# Discovery reads the working directory, so where the sources land and where
# the process runs have to be the same place. Inheriting this from the base
# image worked, but a base image change would have separated them silently.
WORKDIR /app

COPY *.java .

# `exec` so the JVM replaces the shell as PID 1 and receives SIGTERM directly.
# Without it /bin/sh is PID 1, does not forward the signal, and the container
# is SIGKILLed after the stop timeout with the shutdown hook never running.
CMD java DependencyDownloader.java && exec java -cp $(java ListDependency.java) Fluid.java
