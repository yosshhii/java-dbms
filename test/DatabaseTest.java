import java.time.LocalDate;
import java.util.Map;

/**
 * Проверяет базовую модель данных.
 */
public final class DatabaseTest {
    private DatabaseTest() {
    }

    /**
     * Запускает проверки модели данных.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        supportsRequiredTypesAndRelations();
        rejectsDuplicatePrimaryKey();
        rejectsInvalidType();
        rejectsMissingForeignKey();
        System.out.println("Database model tests passed.");
    }

    private static void supportsRequiredTypesAndRelations() {
        Database database = Database.createDefault();
        database.insert("brands", Map.of("id", 1, "name", "Toyota"));
        database.insert("cars", Map.of(
                "id", 10,
                "brand_id", 1,
                "model", "Camry",
                "price", 25_000.0,
                "manufactured", LocalDate.of(2024, 5, 20),
                "available", true));
        Map<String, Object> row = database.getTable("cars").getRows().get(0);
        check(row.get("manufactured").equals(LocalDate.of(2024, 5, 20)));
        check(row.get("available").equals(true));
        check(row.get("price").equals(25_000.0));
    }

    private static void rejectsDuplicatePrimaryKey() {
        Database database = Database.createDefault();
        database.insert("brands", Map.of("id", 1, "name", "Toyota"));
        expectFailure(() -> database.insert("brands", Map.of("id", 1, "name", "BMW")));
    }

    private static void rejectsInvalidType() {
        Database database = Database.createDefault();
        expectFailure(() -> database.insert("brands", Map.of("id", "one", "name", "Toyota")));
    }

    private static void rejectsMissingForeignKey() {
        Database database = Database.createDefault();
        expectFailure(() -> database.insert("cars", Map.of(
                "id", 10,
                "brand_id", 99,
                "model", "Camry",
                "price", 25_000.0,
                "manufactured", LocalDate.of(2024, 5, 20),
                "available", true)));
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Check failed");
        }
    }
}
