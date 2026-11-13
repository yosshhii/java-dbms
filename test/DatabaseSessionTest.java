import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Проверяет сценарии GUI через сервис без открытия графического окна. */
public final class DatabaseSessionTest {
    private DatabaseSessionTest() {
    }

    /**
     * Проверяет скрипты, структурированные результаты и сохранение изменений.
     *
     * @param args не используются
     * @throws Exception при ошибке временного хранилища
     */
    public static void main(String[] args) throws Exception {
        splitsQuotedScripts();
        Path directory = Files.createTempDirectory("simple-dbms-session-");
        Path file = directory.resolve("database.bin");
        try {
            DatabaseSession session = new DatabaseSession(file);
            String script = "CREATE TABLE notes (id INTEGER PRIMARY KEY, text STRING, price DOUBLE);\n"
                    + "INSERT INTO notes VALUES (1, 'Driver''s car; blue', 10.5), (2, NULL, NULL);\n"
                    + "CREATE INDEX idx_notes_text ON notes (text);\n"
                    + "SELECT\nTEXT, PRICE\nFROM notes WHERE text = 'Driver''s car; blue';";
            SqlEngine.ExecutionResult result = null;
            for (String sql : DatabaseSession.splitStatements(script)) {
                result = session.execute(sql);
            }
            check(result != null && result.indexUsed(), "SELECT должен использовать индекс");
            check(result.columns().equals(List.of("text", "price")), "Нужны заголовки SELECT");
            check(result.rows().size() == 1, "Нужна строка SELECT");
            check(result.rows().get(0).get("text").equals("Driver's car; blue"), "SQL-экранирование");
            check(!session.isDirty(), "Автосохранение");
            SqlEngine.ExecutionResult empty = session.execute("SELECT price FROM notes WHERE id = 99;");
            check(empty.columns().equals(List.of("price")) && empty.rows().isEmpty(), "Пустая выборка с заголовками");
            SqlEngine.ExecutionResult nullable = session.execute("SELECT text, price FROM notes WHERE id = 2;");
            check(nullable.rows().get(0).get("text") == null, "NULL должен оставаться NULL в GUI");
            session.execute("UPDATE notes SET text = NULL WHERE id = 1;");
            DatabaseSession restored = new DatabaseSession(file);
            check(restored.execute("SELECT * FROM notes WHERE text = NULL;").rows().size() == 2,
                    "NULL и индекс восстанавливаются с диска");
            try {
                restored.execute("INSERT INTO notes (id) VALUES (1);");
                throw new AssertionError("Дублирование ключа должно быть запрещено");
            } catch (IllegalArgumentException expected) {
                check(!restored.isDirty(), "Ошибка SQL не оставляет изменения");
            }
            restored.execute("DELETE FROM notes;");
            restored.execute("DROP TABLE notes;");
            check(new DatabaseSession(file).getDatabase().getTables().size() == 2, "DROP TABLE сохраняется");
            handlesSaveFailure(directory);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".bak"));
            Files.deleteIfExists(Path.of(file + ".tmp"));
            Files.deleteIfExists(directory);
        }
        System.out.println("Database session tests passed.");
    }

    private static void handlesSaveFailure(Path directory) throws IOException {
        Path blocked = directory.resolve("blocked");
        Path file = blocked.resolve("database.bin");
        DatabaseSession session = new DatabaseSession(file);
        Files.writeString(blocked, "A file blocks creation of the database directory");
        try {
            try {
                session.execute("INSERT INTO brands VALUES (1, 'Toyota');");
                throw new AssertionError("Ожидалась ошибка сохранения");
            } catch (IOException expected) {
                check(session.isDirty(), "Ошибка сохранения должна оставлять признак изменений");
                check(session.getDatabase().getTable("brands").getRows().size() == 1,
                        "Изменение остаётся в памяти для повторного сохранения");
            }
            Files.delete(blocked);
            session.save();
            check(!session.isDirty(), "Повторное сохранение сбрасывает признак изменений");
            check(Storage.load(file).getTable("brands").getRows().size() == 1,
                    "Повторное сохранение записывает данные");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".bak"));
            Files.deleteIfExists(Path.of(file + ".tmp"));
            Files.deleteIfExists(blocked);
        }
    }

    private static void splitsQuotedScripts() {
        check(DatabaseSession.splitStatements(" ; SELECT * FROM brands;;\n").size() == 1,
                "Пустые команды пропускаются");
        check(DatabaseSession.splitStatements("INSERT INTO brands VALUES (1, 'a; b''c');\n"
                + "SELECT * FROM brands;").size() == 2, "Точка с запятой внутри строки");
        try {
            DatabaseSession.splitStatements("INSERT INTO brands VALUES (1, 'broken);");
            throw new AssertionError("Незакрытая строка должна быть отклонена");
        } catch (IllegalArgumentException expected) {
            // Скрипт проверяется до выполнения первой команды.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
