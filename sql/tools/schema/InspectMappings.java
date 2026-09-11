import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Verify the generated schema against actual MyBatis-Plus metadata in the built
 * application, independently of the Python source parser. Does not start Spring
 * or connect to any service. Writes SELECT ... LIMIT 0 probes for MySQL.
 */
public class InspectMappings {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: InspectMappings <repository> <output.sql>");
        }
        Class<?> configClass = Class.forName("com.baomidou.mybatisplus.core.MybatisConfiguration");
        Object configuration = configClass.getConstructor().newInstance();
        configClass.getMethod("setMapUnderscoreToCamelCase", boolean.class).invoke(configuration, true);
        Class<?> configurationBase = Class.forName("org.apache.ibatis.session.Configuration");
        Class<?> builderClass = Class.forName("org.apache.ibatis.builder.MapperBuilderAssistant");
        Class<?> helperClass = Class.forName("com.baomidou.mybatisplus.core.metadata.TableInfoHelper");
        Class<? extends Annotation> tableAnnotation = (Class<? extends Annotation>)
                Class.forName("com.baomidou.mybatisplus.annotation.TableName");
        Class<?> tableInfoClass = Class.forName("com.baomidou.mybatisplus.core.metadata.TableInfo");
        Class<?> fieldInfoClass = Class.forName("com.baomidou.mybatisplus.core.metadata.TableFieldInfo");
        List<Path> paths;
        try (var stream = Files.walk(Path.of(args[0]))) {
            paths = stream.filter(p -> p.toString().contains("/target/classes/"))
                    .filter(p -> p.toString().contains("/dal/dataobject/"))
                    .filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        List<String> queries = new ArrayList<>();
        for (Path path : paths) {
            String className = path.toString().split("/target/classes/", 2)[1]
                    .replace('/', '.').replaceAll("\\.class$", "");
            Class<?> entity = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            if (entity.getAnnotation(tableAnnotation) == null) {
                continue;
            }
            Object builder = builderClass.getConstructor(configurationBase, String.class)
                    .newInstance(configuration, className);
            builderClass.getMethod("setCurrentNamespace", String.class).invoke(builder, className);
            Object info = helperClass.getMethod("initTableInfo", builderClass, Class.class)
                    .invoke(null, builder, entity);
            List<String> columns = new ArrayList<>();
            columns.add(quote((String) tableInfoClass.getMethod("getKeyColumn").invoke(info)));
            for (Object field : (List<?>) tableInfoClass.getMethod("getFieldList").invoke(info)) {
                columns.add(quote((String) fieldInfoClass.getMethod("getColumn").invoke(field)));
            }
            String table = (String) tableInfoClass.getMethod("getTableName").invoke(info);
            queries.add("-- " + className + "\nSELECT " + String.join(", ", columns)
                    + " FROM " + quote(table) + " LIMIT 0;");
        }
        if (queries.isEmpty()) {
            throw new IllegalStateException("No compiled entities found; package all modules first.");
        }
        Files.writeString(Path.of(args[1]), String.join("\n", queries) + "\n");
        System.out.println("Created MyBatis-Plus column probes for " + queries.size() + " entities.");
    }

    private static String quote(String value) {
        if (value == null || !value.replace("`", "").matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported SQL identifier: " + value);
        }
        return "`" + value.replace("`", "") + "`";
    }
}
