import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.stream.Stream;
import java.util.stream.Collectors;

public class ListDependency{
    public static void main(String[] args) {
        List<String> deps = new ArrayList<String>();

        String libDir = "lib";

        File dir = new File(libDir);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
            if (files != null) {
                for (File file : files) {
                    deps.add(file.getName());
                }
            } 
        }
        // let'use join the list with ":" and libDir/ prefix
        String listString = deps.stream().map(name -> libDir + "/" + name).collect(Collectors.joining(":"));
        System.out.println(listString);
    }
}