from maifeeulasad/java:24

COPY *.java .

ARG ENTRY_FILENAME=Fluid.java
ENV ENTRY_FILENAME=${ENTRY_FILENAME}

CMD java DependencyDownloader.java && java -cp $(java ListDependency.java) $ENTRY_FILENAME