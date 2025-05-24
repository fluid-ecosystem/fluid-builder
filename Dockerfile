from maifeeulasad/java:24

COPY *.java .

CMD java DependencyDownloader.java && java -cp $(java ListDependency.java) Fluid.java