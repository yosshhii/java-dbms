import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Проверяет изменение строк и использование индексов.
 */
public final class CrudIndexTest {
    private CrudIndexTest() {
    }

    /**
     * Запускает проверки CRUD и индексов.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        selectsUpdatesAndDeletesRows();
        maintainsIndexAfterChanges();
        protectsReferencedRows();
        System.out.println("CRUD and index tests passed.");
    }

    private static void selectsUpdatesAndDeletesRows() {
        Database database = databaseWithCar();
        Table.Condition expensive = new Table.Condition(
                "price", Table.Operator.GREATER, 20_000.0);
        Table.QueryResult selected = database.select("cars", List.of("model", "price"), expensive);
        check(selected.rows().size() == 1);
        check(!selected.indexUsed());
        int updated = database.update(
                "cars",
                Map.of("price", 30_000.0, "available", false),
                new Table.Condition("id", Table.Operator.EQUALS, 10));
        check(updated == 1);
        Map<String, Object> row = database.getTable("cars").getRows().get(0);
        check(row.get("price").equals(30_000.0));
        check(row.get("available").equals(false));
        int deleted = database.delete(
                "cars",
                new Table.Condition("id", Table.Operator.EQUALS, 10));
        check(deleted == 1);
        check(database.getTable("cars").getRows().isEmpty());
    }

    private static void maintainsIndexAfterChanges() {
        Database database = databaseWithCar();
        database.createIndex("idx_cars_model", "cars", "model");
        Table.QueryResult initial = database.select(
                "cars",
                List.of(),
                new Table.Condition("model", Table.Operator.EQUALS, "Camry"));
        check(initial.indexUsed());
        check(initial.rows().size() == 1);
        database.update(
                "cars",
                Map.of("model", "Corolla"),
                new Table.Condition("id", Table.Operator.EQUALS, 10));
        Table.QueryResult oldValue = database.select(
                "cars",
                List.of(),
                new Table.Condition("model", Table.Operator.EQUALS, "Camry"));
        Table.QueryResult newValue = database.select(
                "cars",
                List.of(),
                new Table.Condition("model", Table.Operator.EQUALS, "Corolla"));
        check(oldValue.indexUsed());
        check(oldValue.rows().isEmpty());
        check(newValue.indexUsed());
        check(newValue.rows().size() == 1);
    }

    private static void protectsReferencedRows() {
        Database database = databaseWithCar();
        expectFailure(() -> database.delete(
                "brands",
                new Table.Condition("id", Table.Operator.EQUALS, 1)));
        database.delete("cars", null);
        int deleted = database.delete(
                "brands",
                new Table.Condition("id", Table.Operator.EQUALS, 1));
        check(deleted == 1);
    }

    private static Database databaseWithCar() {
        Database database = Database.createDefault();
        database.insert("brands", Map.of("id", 1, "name", "Toyota"));
        database.insert("cars", Map.of(
                "id", 10,
                "brand_id", 1,
                "model", "Camry",
                "price", 25_000.0,
                "manufactured", LocalDate.of(2024, 5, 20),
                "available", true));
        return database;
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
