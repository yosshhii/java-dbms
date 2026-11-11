/**
 * Проверяет разбор и выполнение SQL-подобных команд.
 */
public final class SqlEngineTest {
    private SqlEngineTest() {
    }

    /**
     * Запускает проверки SQL-движка.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        executesCrudCommands();
        createsAndUsesIndex();
        createsAndDropsTable();
        keepsBatchInsertAtomic();
        rejectsInvalidCommands();
        System.out.println("SQL engine tests passed.");
    }

    private static void executesCrudCommands() {
        SqlEngine engine = new SqlEngine(Database.createDefault());
        engine.execute("INSERT INTO brands VALUES (1, 'Toyota');");
        SqlEngine.ExecutionResult inserted = engine.execute(
                "INSERT INTO cars VALUES "
                        + "(1, 1, 'Camry', 25000.0, '2024-05-20', TRUE), "
                        + "(2, 1, 'Corolla', 18000.0, '2023-03-10', FALSE);");
        check(inserted.changed());
        check(inserted.output().contains("2"));
        SqlEngine.ExecutionResult selected = engine.execute(
                "SELECT model, price FROM cars WHERE price > 20000;");
        check(selected.output().contains("Camry"));
        check(!selected.output().contains("Corolla"));
        SqlEngine.ExecutionResult updated = engine.execute(
                "UPDATE cars SET price = 30000, available = TRUE WHERE id = 2;");
        check(updated.output().contains("1"));
        SqlEngine.ExecutionResult afterUpdate = engine.execute(
                "SELECT model FROM cars WHERE price >= 30000;");
        check(afterUpdate.output().contains("Corolla"));
        SqlEngine.ExecutionResult deleted = engine.execute(
                "DELETE FROM cars WHERE model = 'Camry';");
        check(deleted.output().contains("1"));
    }

    private static void createsAndUsesIndex() {
        SqlEngine engine = new SqlEngine(Database.createDefault());
        engine.execute("INSERT INTO brands VALUES (1, 'Toyota');");
        engine.execute("INSERT INTO cars VALUES (1, 1, 'Camry', 25000, '2024-05-20', TRUE);");
        engine.execute("CREATE INDEX idx_cars_model ON cars (model);");
        SqlEngine.ExecutionResult result = engine.execute(
                "SELECT * FROM cars WHERE model = 'Camry';");
        check(result.indexUsed());
        check(result.output().contains("Camry"));
    }

    private static void createsAndDropsTable() {
        SqlEngine engine = new SqlEngine(Database.createDefault());
        engine.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, text STRING);");
        engine.execute("INSERT INTO notes (id) VALUES (1);");
        SqlEngine.ExecutionResult result = engine.execute("SELECT * FROM notes;");
        check(result.output().contains("NULL"));
        engine.execute("DELETE FROM notes;");
        engine.execute("DROP TABLE notes;");
        expectFailure(() -> engine.execute("SELECT * FROM notes;"));
    }

    private static void rejectsInvalidCommands() {
        SqlEngine engine = new SqlEngine(Database.createDefault());
        expectFailure(() -> engine.execute("INSERT INTO brands VALUES ('one', 'Toyota');"));
        expectFailure(() -> engine.execute("SELECT FROM brands;"));
        expectFailure(() -> engine.execute("CREATE TABLE broken (name STRING);"));
        check(engine.execute("SELECT * FROM brands;").output().contains("Rows: 0"));
    }

    private static void keepsBatchInsertAtomic() {
        SqlEngine engine = new SqlEngine(Database.createDefault());
        expectFailure(() -> engine.execute(
                "INSERT INTO brands VALUES (1, 'Toyota'), (1, 'BMW');"));
        check(engine.execute("SELECT * FROM brands;").output().contains("Rows: 0"));
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
