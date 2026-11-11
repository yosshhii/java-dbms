import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Управляет таблицами, связями и индексами базы данных.
 */
@SuppressWarnings("serial")
public final class Database implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Описывает внешний ключ между двумя таблицами.
     *
     * @param table таблица с внешним ключом
     * @param column столбец внешнего ключа
     * @param referencedTable связанная таблица
     * @param referencedColumn связанный столбец
     */
    public record ForeignKey(
            String table,
            String column,
            String referencedTable,
            String referencedColumn) implements Serializable {
        /**
         * Проверяет и нормализует описание внешнего ключа.
         *
         * @param table таблица с внешним ключом
         * @param column столбец внешнего ключа
         * @param referencedTable связанная таблица
         * @param referencedColumn связанный столбец
         */
        public ForeignKey {
            table = Table.normalizeIdentifier(table);
            column = Table.normalizeIdentifier(column);
            referencedTable = Table.normalizeIdentifier(referencedTable);
            referencedColumn = Table.normalizeIdentifier(referencedColumn);
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final List<ForeignKey> foreignKeys = new ArrayList<>();

    /**
     * Создаёт учебную базу из связанных таблиц brands и cars.
     *
     * @return подготовленная база данных
     */
    public static Database createDefault() {
        Database database = new Database();
        database.addTable(new Table("brands", List.of(
                new Table.Column("id", Table.DataType.INTEGER, true),
                new Table.Column("name", Table.DataType.STRING, true)
        ), "id"));
        database.addTable(new Table("cars", List.of(
                new Table.Column("id", Table.DataType.INTEGER, true),
                new Table.Column("brand_id", Table.DataType.INTEGER, true),
                new Table.Column("model", Table.DataType.STRING, true),
                new Table.Column("price", Table.DataType.DOUBLE, true),
                new Table.Column("manufactured", Table.DataType.DATE, true),
                new Table.Column("available", Table.DataType.BOOLEAN, true)
        ), "id"));
        database.addForeignKey(new ForeignKey("cars", "brand_id", "brands", "id"));
        return database;
    }

    /**
     * Добавляет таблицу в базу данных.
     *
     * @param table новая таблица
     */
    public void addTable(Table table) {
        Objects.requireNonNull(table, "Таблица не задана");
        if (tables.putIfAbsent(table.getName(), table) != null) {
            throw new IllegalArgumentException("Таблица уже существует: " + table.getName());
        }
    }

    /**
     * Удаляет пустую и не связанную таблицу.
     *
     * @param tableName имя таблицы
     */
    public void dropTable(String tableName) {
        String normalizedName = Table.normalizeIdentifier(tableName);
        Table table = getTable(normalizedName);
        if (!table.getRows().isEmpty()) {
            throw new IllegalArgumentException("Удалять можно только пустую таблицу");
        }
        boolean hasRelation = foreignKeys.stream().anyMatch(key ->
                key.table().equals(normalizedName) || key.referencedTable().equals(normalizedName));
        if (hasRelation) {
            throw new IllegalArgumentException("Таблица участвует во внешнем ключе");
        }
        tables.remove(normalizedName);
    }

    /**
     * Добавляет связь по внешнему ключу.
     *
     * @param foreignKey описание связи
     */
    public void addForeignKey(ForeignKey foreignKey) {
        Objects.requireNonNull(foreignKey, "Внешний ключ не задан");
        Table table = getTable(foreignKey.table());
        Table referencedTable = getTable(foreignKey.referencedTable());
        if (!table.hasColumn(foreignKey.column())) {
            throw new IllegalArgumentException("Столбец внешнего ключа не найден");
        }
        if (!referencedTable.hasColumn(foreignKey.referencedColumn())) {
            throw new IllegalArgumentException("Связанный столбец не найден");
        }
        if (!referencedTable.getPrimaryKey().equals(foreignKey.referencedColumn())) {
            throw new IllegalArgumentException("Внешний ключ должен ссылаться на первичный ключ");
        }
        if (foreignKeys.contains(foreignKey)) {
            throw new IllegalArgumentException("Внешний ключ уже существует");
        }
        foreignKeys.add(foreignKey);
    }

    /**
     * Добавляет строку в указанную таблицу.
     *
     * @param tableName имя таблицы
     * @param values значения строки
     */
    public void insert(String tableName, Map<String, Object> values) {
        insertAll(tableName, List.of(values));
    }

    /**
     * Атомарно добавляет несколько строк в указанную таблицу.
     *
     * @param tableName имя таблицы
     * @param valuesList добавляемые строки
     */
    public void insertAll(String tableName, List<Map<String, Object>> valuesList) {
        String normalizedTableName = Table.normalizeIdentifier(tableName);
        Objects.requireNonNull(valuesList, "Строки не заданы");
        for (Map<String, Object> values : valuesList) {
            validateForeignKeyAssignments(normalizedTableName, values);
        }
        getTable(normalizedTableName).insertAll(valuesList);
    }

    /**
     * Выполняет выборку из таблицы.
     *
     * @param tableName имя таблицы
     * @param columns выбранные столбцы
     * @param condition условие или null
     * @return результат выборки
     */
    public Table.QueryResult select(
            String tableName,
            List<String> columns,
            Table.Condition condition) {
        return getTable(tableName).select(columns, condition);
    }

    /**
     * Обновляет строки таблицы с проверкой внешних ключей.
     *
     * @param tableName имя таблицы
     * @param assignments новые значения
     * @param condition условие или null
     * @return количество обновлённых строк
     */
    public int update(
            String tableName,
            Map<String, Object> assignments,
            Table.Condition condition) {
        String normalizedTableName = Table.normalizeIdentifier(tableName);
        validateForeignKeyAssignments(normalizedTableName, assignments);
        return getTable(normalizedTableName).update(assignments, condition);
    }

    /**
     * Удаляет строки с проверкой ссылочной целостности.
     *
     * @param tableName имя таблицы
     * @param condition условие или null
     * @return количество удалённых строк
     */
    public int delete(String tableName, Table.Condition condition) {
        String normalizedTableName = Table.normalizeIdentifier(tableName);
        Table table = getTable(normalizedTableName);
        List<Map<String, Object>> selectedRows = table.select(List.of(), condition).rows();
        for (ForeignKey foreignKey : foreignKeys) {
            if (!foreignKey.referencedTable().equals(normalizedTableName)) {
                continue;
            }
            Table referencingTable = getTable(foreignKey.table());
            for (Map<String, Object> row : selectedRows) {
                Object referencedValue = row.get(foreignKey.referencedColumn());
                if (referencingTable.containsValue(foreignKey.column(), referencedValue)) {
                    throw new IllegalArgumentException("Строка используется во внешнем ключе");
                }
            }
        }
        return table.delete(condition);
    }

    /**
     * Создаёт индекс с уникальным в базе именем.
     *
     * @param indexName имя индекса
     * @param tableName имя таблицы
     * @param columnName имя столбца
     */
    public void createIndex(String indexName, String tableName, String columnName) {
        String normalizedIndexName = Table.normalizeIdentifier(indexName);
        for (Table table : tables.values()) {
            if (table.hasIndex(normalizedIndexName)) {
                throw new IllegalArgumentException("Индекс уже существует: " + indexName);
            }
        }
        getTable(tableName).createIndex(normalizedIndexName, columnName);
    }

    /**
     * Возвращает таблицу по имени.
     *
     * @param name имя таблицы
     * @return найденная таблица
     */
    public Table getTable(String name) {
        String normalizedName = Table.normalizeIdentifier(name);
        Table table = tables.get(normalizedName);
        if (table == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + name);
        }
        return table;
    }

    /**
     * Возвращает снимок списка таблиц.
     *
     * @return таблицы базы данных
     */
    public List<Table> getTables() {
        return List.copyOf(tables.values());
    }

    /**
     * Возвращает снимок внешних ключей.
     *
     * @return внешние ключи
     */
    public List<ForeignKey> getForeignKeys() {
        return List.copyOf(foreignKeys);
    }

    private void validateForeignKeyAssignments(String tableName, Map<String, Object> values) {
        Objects.requireNonNull(values, "Значения не заданы");
        for (ForeignKey foreignKey : foreignKeys) {
            if (!foreignKey.table().equals(tableName) || !containsKey(values, foreignKey.column())) {
                continue;
            }
            Object value = findValue(values, foreignKey.column());
            if (value != null && !getTable(foreignKey.referencedTable())
                    .containsValue(foreignKey.referencedColumn(), value)) {
                throw new IllegalArgumentException("Нарушена связь по внешнему ключу");
            }
        }
    }

    private boolean containsKey(Map<String, Object> values, String columnName) {
        return values.keySet().stream()
                .map(Table::normalizeIdentifier)
                .anyMatch(columnName::equals);
    }

    private Object findValue(Map<String, Object> values, String columnName) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (Table.normalizeIdentifier(entry.getKey()).equals(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
