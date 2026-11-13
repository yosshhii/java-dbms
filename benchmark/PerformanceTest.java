import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Сравнивает поиск по равенству с индексом и без него.
 */
public final class PerformanceTest {
    private static final int ROW_COUNT = 50_000;
    private static final int QUERY_COUNT = 200;

    private PerformanceTest() {
    }

    /**
     * Запускает измерение производительности.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        Database database = new Database();
        database.addTable(new Table("items", List.of(
                new Table.Column("id", Table.DataType.INTEGER, true),
                new Table.Column("code", Table.DataType.STRING, true)
        ), "id"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int id = 0; id < ROW_COUNT; id++) {
            rows.add(Map.of("id", id, "code", "code-" + id));
        }
        database.insertAll("items", rows);
        Table.Condition condition = new Table.Condition(
                "code", Table.Operator.EQUALS, "code-49999");
        for (int iteration = 0; iteration < 20; iteration++) {
            database.select("items", List.of("id"), condition);
        }
        long withoutIndex = measure(database, condition);
        database.createIndex("idx_items_code", "items", "code");
        for (int iteration = 0; iteration < 20; iteration++) {
            database.select("items", List.of("id"), condition);
        }
        long withIndex = measure(database, condition);
        double speedup = (double) withoutIndex / withIndex;
        System.out.printf("Rows: %d%n", ROW_COUNT);
        System.out.printf("Queries: %d%n", QUERY_COUNT);
        System.out.printf("Without index: %.3f ms%n", withoutIndex / 1_000_000.0);
        System.out.printf("With index: %.3f ms%n", withIndex / 1_000_000.0);
        System.out.printf("Speedup: %.2fx%n", speedup);
        if (withIndex >= withoutIndex) {
            throw new AssertionError("Index did not improve equality search");
        }
    }

    private static long measure(Database database, Table.Condition condition) {
        long started = System.nanoTime();
        for (int iteration = 0; iteration < QUERY_COUNT; iteration++) {
            Table.QueryResult result = database.select("items", List.of("id"), condition);
            if (result.rows().size() != 1) {
                throw new AssertionError("Unexpected query result");
            }
        }
        return System.nanoTime() - started;
    }
}
