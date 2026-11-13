import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Сервис графического интерфейса: выполнение SQL и сохранение изменений.
 * Используется последовательно одним фоновым заданием за раз.
 */
public final class DatabaseSession {
    private final Path file;
    private final Database database;
    private final SqlEngine engine;
    private boolean dirty;

    /**
     * Загружает базу или создаёт стандартную схему.
     *
     * @param file файл базы
     * @throws IOException при ошибке загрузки
     */
    public DatabaseSession(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "Файл базы не задан").toAbsolutePath();
        database = Storage.load(this.file);
        engine = new SqlEngine(database);
    }

    /**
     * Выполняет одну команду и сохраняет каждое изменение.
     *
     * @param sql SQL-команда
     * @return текст и табличные данные результата
     * @throws IOException если изменения в памяти не удалось сохранить
     */
    public SqlEngine.ExecutionResult execute(String sql) throws IOException {
        SqlEngine.ExecutionResult result = engine.execute(sql);
        if (result.changed()) {
            dirty = true;
            save();
        }
        return result;
    }

    /**
     * Сохраняет текущее состояние, в том числе после ошибки автосохранения.
     *
     * @throws IOException при ошибке записи
     */
    public void save() throws IOException {
        Storage.save(database, file);
        dirty = false;
    }

    /** @return есть ли изменения, ещё не сохранённые на диск */
    public boolean isDirty() {
        return dirty;
    }

    /** @return абсолютный путь к файлу базы */
    public Path getFile() {
        return file;
    }

    /** @return база для получения снимков таблиц в фоновом задании */
    public Database getDatabase() {
        return database;
    }

    /**
     * Разделяет SQL-скрипт по точкам с запятой вне строковых литералов.
     * Учитывает SQL-экранирование апострофа двумя апострофами.
     *
     * @param script текст скрипта
     * @return отдельные команды без пустых элементов
     */
    public static List<String> splitStatements(String script) {
        Objects.requireNonNull(script, "Скрипт не задан");
        List<String> statements = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            if (character == '\'' && quoted && index + 1 < script.length()
                    && script.charAt(index + 1) == '\'') {
                index++;
            } else if (character == '\'') {
                quoted = !quoted;
            } else if (character == ';' && !quoted) {
                addStatement(statements, script.substring(start, index));
                start = index + 1;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Незакрытая строка в SQL-скрипте");
        }
        addStatement(statements, script.substring(start));
        return List.copyOf(statements);
    }

    private static void addStatement(List<String> statements, String text) {
        if (!text.isBlank()) {
            statements.add(text.trim());
        }
    }
}
