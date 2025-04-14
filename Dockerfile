from maifeeulasad/java:24

COPY DependencyDownloader.java .
COPY ListDependency.java .

ARG ENTRY_FILENAME=Fluid.java

CMD java DependencyDownloader.java && java -cp $(java ListDependency.java) ${ENTRY_FILENAME}