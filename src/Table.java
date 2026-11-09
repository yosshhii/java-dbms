import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Хранит схему, строки и индексы одной таблицы.
 */
@SuppressWarnings("serial")
public final class Table implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

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
     * Операторы условия выборки.
     */
    public enum Operator {
        /** Равенство. */
        EQUALS,
        /** Неравенство. */
        NOT_EQUALS,
        /** Меньше. */
        LESS,
        /** Меньше или равно. */
        LESS_OR_EQUALS,
        /** Больше. */
        GREATER,
        /** Больше или равно. */
        GREATER_OR_EQUALS
    }

    /**
     * Описывает столбец таблицы.
     *
     * @param name имя столбца
     * @param type тип данных
     * @param required признак обязательного значения
     */
    public record Column(String name, DataType type, boolean required) implements Serializable {
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

    /**
     * Описывает простое условие над одним столбцом.
     *
     * @param column имя столбца
     * @param operator оператор сравнения
     * @param value значение сравнения
     */
    public record Condition(String column, Operator operator, Object value) {
        /**
         * Проверяет и нормализует условие.
         *
         * @param column имя столбца
         * @param operator оператор сравнения
         * @param value значение сравнения
         */
        public Condition {
            column = normalizeIdentifier(column);
            Objects.requireNonNull(operator, "Оператор не задан");
        }
    }

    /**
     * Содержит строки результата и признак применения индекса.
     *
     * @param rows найденные строки
     * @param indexUsed признак применения индекса
     */
    public record QueryResult(List<Map<String, Object>> rows, boolean indexUsed) {
        /**
         * Создаёт неизменяемый результат запроса.
         *
         * @param rows найденные строки
         * @param indexUsed признак применения индекса
         */
        public QueryResult {
            rows = List.copyOf(rows);
        }
    }

    private final String name;
    private final List<Column> columns;
    private final Map<String, Column> columnsByName;
    private final String primaryKey;
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Set<Object> primaryKeyValues = new HashSet<>();
    private final Map<String, String> indexColumns = new LinkedHashMap<>();
    private final Map<String, Map<Object, Set<Object>>> indexes = new LinkedHashMap<>();

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
     * Возвращает столбец по имени.
     *
     * @param columnName имя столбца
     * @return описание столбца
     */
    public Column getColumn(String columnName) {
        String normalizedName = normalizeIdentifier(columnName);
        Column column = columnsByName.get(normalizedName);
        if (column == null) {
            throw new IllegalArgumentException("Неизвестный столбец: " + columnName);
        }
        return column;
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
     * Возвращает имена индексов и индексируемых столбцов.
     *
     * @return описание индексов
     */
    public Map<String, String> getIndexes() {
        return Map.copyOf(indexColumns);
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
        addRowToIndexes(row);
    }

    /**
     * Выбирает и проецирует строки таблицы.
     *
     * @param selectedColumns выбранные столбцы или пустой список для всех столбцов
     * @param condition условие или null
     * @return результат выборки
     */
    public QueryResult select(List<String> selectedColumns, Condition condition) {
        List<String> projection = normalizeProjection(selectedColumns);
        Condition normalizedCondition = normalizeCondition(condition);
        List<Map<String, Object>> source = rows;
        boolean indexUsed = false;
        if (normalizedCondition != null && normalizedCondition.operator() == Operator.EQUALS) {
            String indexName = findIndexForColumn(normalizedCondition.column());
            if (indexName != null) {
                indexUsed = true;
                Set<Object> keys = indexes.get(indexName)
                        .getOrDefault(normalizedCondition.value(), Set.of());
                source = rows.stream()
                        .filter(row -> keys.contains(row.get(primaryKey)))
                        .toList();
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : source) {
            if (!matches(row, normalizedCondition)) {
                continue;
            }
            LinkedHashMap<String, Object> projectedRow = new LinkedHashMap<>();
            for (String column : projection) {
                projectedRow.put(column, row.get(column));
            }
            result.add(Collections.unmodifiableMap(projectedRow));
        }
        return new QueryResult(result, indexUsed);
    }

    /**
     * Обновляет строки, соответствующие условию.
     *
     * @param assignments новые значения столбцов
     * @param condition условие или null для всех строк
     * @return количество обновлённых строк
     */
    public int update(Map<String, Object> assignments, Condition condition) {
        Objects.requireNonNull(assignments, "Новые значения не заданы");
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("Список изменений пуст");
        }
        Map<String, Object> normalizedAssignments = normalizeKeys(assignments);
        if (normalizedAssignments.containsKey(primaryKey)) {
            throw new IllegalArgumentException("Изменение первичного ключа запрещено");
        }
        LinkedHashMap<String, Object> checkedAssignments = new LinkedHashMap<>();
        for (Map.Entry<String, Object> assignment : normalizedAssignments.entrySet()) {
            checkedAssignments.put(
                    assignment.getKey(),
                    columnsByName.get(assignment.getKey()).normalizeValue(assignment.getValue()));
        }
        Condition normalizedCondition = normalizeCondition(condition);
        int updated = 0;
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            if (!matches(row, normalizedCondition)) {
                continue;
            }
            LinkedHashMap<String, Object> changedRow = new LinkedHashMap<>(row);
            changedRow.putAll(checkedAssignments);
            rows.set(index, Collections.unmodifiableMap(changedRow));
            updated++;
        }
        if (updated > 0) {
            rebuildIndexes();
        }
        return updated;
    }

    /**
     * Удаляет строки, соответствующие условию.
     *
     * @param condition условие или null для всех строк
     * @return количество удалённых строк
     */
    public int delete(Condition condition) {
        Condition normalizedCondition = normalizeCondition(condition);
        int sizeBefore = rows.size();
        rows.removeIf(row -> matches(row, normalizedCondition));
        int deleted = sizeBefore - rows.size();
        if (deleted > 0) {
            primaryKeyValues.clear();
            for (Map<String, Object> row : rows) {
                primaryKeyValues.add(row.get(primaryKey));
            }
            rebuildIndexes();
        }
        return deleted;
    }

    /**
     * Создаёт индекс для столбца и заполняет его существующими строками.
     *
     * @param indexName имя индекса
     * @param columnName индексируемый столбец
     */
    public void createIndex(String indexName, String columnName) {
        String normalizedIndexName = normalizeIdentifier(indexName);
        String normalizedColumnName = getColumn(columnName).name();
        if (indexColumns.containsKey(normalizedIndexName)) {
            throw new IllegalArgumentException("Индекс уже существует: " + indexName);
        }
        indexColumns.put(normalizedIndexName, normalizedColumnName);
        rebuildIndex(normalizedIndexName);
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

    boolean hasIndex(String indexName) {
        return indexColumns.containsKey(normalizeIdentifier(indexName));
    }

    boolean containsValue(String columnName, Object value) {
        String normalizedName = getColumn(columnName).name();
        Object normalizedValue = columnsByName.get(normalizedName).normalizeValue(value);
        return rows.stream().anyMatch(row -> Objects.equals(row.get(normalizedName), normalizedValue));
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String columnName = getColumn(entry.getKey()).name();
            if (normalized.containsKey(columnName)) {
                throw new IllegalArgumentException("Столбец задан повторно: " + columnName);
            }
            normalized.put(columnName, entry.getValue());
        }
        return normalized;
    }

    private List<String> normalizeProjection(List<String> selectedColumns) {
        if (selectedColumns == null || selectedColumns.isEmpty()) {
            return columns.stream().map(Column::name).toList();
        }
        List<String> projection = new ArrayList<>();
        for (String columnName : selectedColumns) {
            String normalizedName = getColumn(columnName).name();
            if (projection.contains(normalizedName)) {
                throw new IllegalArgumentException("Столбец выбран повторно: " + columnName);
            }
            projection.add(normalizedName);
        }
        return List.copyOf(projection);
    }

    private Condition normalizeCondition(Condition condition) {
        if (condition == null) {
            return null;
        }
        Column column = getColumn(condition.column());
        Object value = column.normalizeValue(condition.value());
        if (column.type() == DataType.BOOLEAN
                && condition.operator() != Operator.EQUALS
                && condition.operator() != Operator.NOT_EQUALS) {
            throw new IllegalArgumentException("Логические значения поддерживают только = и !=");
        }
        return new Condition(column.name(), condition.operator(), value);
    }

    private boolean matches(Map<String, Object> row, Condition condition) {
        if (condition == null) {
            return true;
        }
        Object left = row.get(condition.column());
        Object right = condition.value();
        if (condition.operator() == Operator.EQUALS) {
            return Objects.equals(left, right);
        }
        if (condition.operator() == Operator.NOT_EQUALS) {
            return !Objects.equals(left, right);
        }
        if (left == null || right == null) {
            return false;
        }
        int comparison = compare(left, right);
        return switch (condition.operator()) {
            case LESS -> comparison < 0;
            case LESS_OR_EQUALS -> comparison <= 0;
            case GREATER -> comparison > 0;
            case GREATER_OR_EQUALS -> comparison >= 0;
            case EQUALS, NOT_EQUALS -> throw new IllegalStateException("Оператор уже обработан");
        };
    }

    @SuppressWarnings("unchecked")
    private int compare(Object left, Object right) {
        if (left instanceof Comparable<?> comparable && left.getClass().isInstance(right)) {
            return ((Comparable<Object>) comparable).compareTo(right);
        }
        throw new IllegalArgumentException("Значения нельзя сравнить");
    }

    private String findIndexForColumn(String columnName) {
        for (Map.Entry<String, String> entry : indexColumns.entrySet()) {
            if (entry.getValue().equals(columnName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void addRowToIndexes(Map<String, Object> row) {
        for (String indexName : indexColumns.keySet()) {
            String columnName = indexColumns.get(indexName);
            indexes.computeIfAbsent(indexName, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.get(columnName), ignored -> new LinkedHashSet<>())
                    .add(row.get(primaryKey));
        }
    }

    private void rebuildIndexes() {
        indexes.clear();
        for (String indexName : indexColumns.keySet()) {
            rebuildIndex(indexName);
        }
    }

    private void rebuildIndex(String indexName) {
        String columnName = indexColumns.get(indexName);
        Map<Object, Set<Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            index.computeIfAbsent(row.get(columnName), ignored -> new LinkedHashSet<>())
                    .add(row.get(primaryKey));
        }
        indexes.put(indexName, index);
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
