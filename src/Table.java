import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Хранит схему таблицы и её строки в памяти.
 */
public final class Table {
    /**
     * Поддерживаемые типы значений столбцов.
     */
    public enum DataType {
        /** Целое число. */
        INTEGER,
        /** Число с плавающей точкой. */
        DOUBLE,
        /** Строка. */
        STRING,
        /** Календарная дата. */
        DATE,
        /** Логическое значение. */
        BOOLEAN
    }

    /**
     * Описывает столбец таблицы.
     *
     * @param name имя столбца
     * @param type тип данных
     * @param required признак обязательного значения
     */
    public record Column(String name, DataType type, boolean required) {
        /**
         * Проверяет и нормализует описание столбца.
         *
         * @param name имя столбца
         * @param type тип данных
         * @param required признак обязательного значения
         */
        public Column {
            name = normalizeIdentifier(name);
            Objects.requireNonNull(type, "Тип столбца не задан");
        }

        private Object normalizeValue(Object value) {
            if (value == null) {
                if (required) {
                    throw new IllegalArgumentException("Столбец " + name + " обязателен");
                }
                return null;
            }
            return switch (type) {
                case INTEGER -> requireType(value, Integer.class);
                case DOUBLE -> normalizeDouble(value);
                case STRING -> requireType(value, String.class);
                case DATE -> requireType(value, LocalDate.class);
                case BOOLEAN -> requireType(value, Boolean.class);
            };
        }

        private Object normalizeDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            throw invalidType(value);
        }

        private Object requireType(Object value, Class<?> expectedType) {
            if (expectedType.isInstance(value)) {
                return value;
            }
            throw invalidType(value);
        }

        private IllegalArgumentException invalidType(Object value) {
            return new IllegalArgumentException(
                    "Столбец " + name + " ожидает " + type + ", получен "
                            + value.getClass().getSimpleName());
        }
    }

    private final String name;
    private final List<Column> columns;
    private final Map<String, Column> columnsByName;
    private final String primaryKey;
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Set<Object> primaryKeyValues = new HashSet<>();

    /**
     * Создаёт пустую таблицу с заданной схемой.
     *
     * @param name имя таблицы
     * @param columns столбцы таблицы
     * @param primaryKey имя столбца первичного ключа
     */
    public Table(String name, List<Column> columns, String primaryKey) {
        this.name = normalizeIdentifier(name);
        Objects.requireNonNull(columns, "Список столбцов не задан");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Таблица должна содержать столбцы");
        }
        this.columnsByName = new LinkedHashMap<>();
        for (Column column : columns) {
            Objects.requireNonNull(column, "Столбец не задан");
            if (columnsByName.putIfAbsent(column.name(), column) != null) {
                throw new IllegalArgumentException("Столбец уже существует: " + column.name());
            }
        }
        this.columns = List.copyOf(columnsByName.values());
        this.primaryKey = normalizeIdentifier(primaryKey);
        Column primaryKeyColumn = columnsByName.get(this.primaryKey);
        if (primaryKeyColumn == null) {
            throw new IllegalArgumentException("Первичный ключ отсутствует в схеме");
        }
        if (!primaryKeyColumn.required()) {
            throw new IllegalArgumentException("Первичный ключ должен быть обязательным");
        }
    }

    /**
     * Возвращает имя таблицы.
     *
     * @return имя таблицы
     */
    public String getName() {
        return name;
    }

    /**
     * Возвращает неизменяемый список столбцов.
     *
     * @return столбцы таблицы
     */
    public List<Column> getColumns() {
        return columns;
    }

    /**
     * Возвращает имя первичного ключа.
     *
     * @return имя столбца первичного ключа
     */
    public String getPrimaryKey() {
        return primaryKey;
    }

    /**
     * Добавляет строку после проверки схемы и уникальности первичного ключа.
     *
     * @param values значения по именам столбцов
     */
    public void insert(Map<String, Object> values) {
        Objects.requireNonNull(values, "Значения строки не заданы");
        Map<String, Object> normalizedValues = normalizeKeys(values);
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (Column column : columns) {
            row.put(column.name(), column.normalizeValue(normalizedValues.get(column.name())));
        }
        Object keyValue = row.get(primaryKey);
        if (primaryKeyValues.contains(keyValue)) {
            throw new IllegalArgumentException("Первичный ключ уже существует: " + keyValue);
        }
        primaryKeyValues.add(keyValue);
        rows.add(Collections.unmodifiableMap(row));
    }

    /**
     * Возвращает неизменяемый снимок строк таблицы.
     *
     * @return строки таблицы
     */
    public List<Map<String, Object>> getRows() {
        return List.copyOf(rows);
    }

    boolean hasColumn(String columnName) {
        return columnsByName.containsKey(normalizeIdentifier(columnName));
    }

    boolean containsValue(String columnName, Object value) {
        String normalizedName = normalizeIdentifier(columnName);
        if (!columnsByName.containsKey(normalizedName)) {
            throw new IllegalArgumentException("Неизвестный столбец: " + columnName);
        }
        return rows.stream().anyMatch(row -> Objects.equals(row.get(normalizedName), value));
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String columnName = normalizeIdentifier(entry.getKey());
            if (!columnsByName.containsKey(columnName)) {
                throw new IllegalArgumentException("Неизвестный столбец: " + entry.getKey());
            }
            if (normalized.containsKey(columnName)) {
                throw new IllegalArgumentException("Столбец задан повторно: " + columnName);
            }
            normalized.put(columnName, entry.getValue());
        }
        return normalized;
    }

    static String normalizeIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "Идентификатор не задан");
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Некорректный идентификатор: " + identifier);
        }
        return normalized;
    }
}
