from maifeeulasad/java:24

COPY DependencyDownloader.java .
COPY ListDependency.java .

CMD java DependencyDownloader.java && java -cp $(java ListDependency.java) Fluid.java