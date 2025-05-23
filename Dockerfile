from maifeeulasad/java:24

COPY *.java .

ARG ENTRY_FILENAME=Fluid.java

CMD java DependencyDownloader.java && java -cp $(java ListDependency.java) Fluid.java