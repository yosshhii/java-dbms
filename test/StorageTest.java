import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * Проверяет сохранение и восстановление базы данных.
 */
public final class StorageTest {
    private StorageTest() {
    }

    /**
     * Запускает проверку файлового хранения.
     *
     * @param args аргументы командной строки
     * @throws Exception при ошибке работы с временным файлом
     */
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("simple-dbms-test-");
        Path file = directory.resolve("database.bin");
        try {
            Database database = Database.createDefault();
            database.insert("brands", Map.of("id", 1, "name", "Toyota"));
            database.insert("cars", Map.of(
                    "id", 10,
                    "brand_id", 1,
                    "model", "Camry",
                    "price", 25_000.0,
                    "manufactured", LocalDate.of(2024, 5, 20),
                    "available", true));
            database.createIndex("idx_cars_model", "cars", "model");
            Storage.save(database, file);
            Database restored = Storage.load(file);
            check(restored.getTable("brands").getRows().size() == 1);
            check(restored.getTable("cars").getRows().size() == 1);
            check(restored.getTable("cars").getRows().get(0).get("model").equals("Camry"));
            check(restored.select(
                    "cars",
                    java.util.List.of(),
                    new Table.Condition("model", Table.Operator.EQUALS, "Camry")).indexUsed());
            check(Files.notExists(Path.of(file + ".tmp")));
            restored.update(
                    "cars",
                    Map.of("model", "Corolla"),
                    new Table.Condition("id", Table.Operator.EQUALS, 10));
            Storage.save(restored, file);
            Files.writeString(file, "broken");
            Database recovered = Storage.load(file);
            check(recovered.getTable("cars").getRows().get(0).get("model").equals("Camry"));
            // Следующее сохранение не должно переносить повреждённый файл в резервную копию.
            Storage.save(recovered, file);
            Files.writeString(file, "broken again");
            check(Storage.load(file).getTable("cars").getRows().get(0).get("model").equals("Camry"));
            // Резервная копия доступна и при отсутствии основного файла.
            Files.delete(file);
            check(Storage.load(file).getTable("cars").getRows().size() == 1);
            check(Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".tmp"));
            Files.deleteIfExists(Path.of(file + ".bak"));
            Files.deleteIfExists(directory);
        }
        System.out.println("Storage tests passed.");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Check failed");
        }
    }
}
