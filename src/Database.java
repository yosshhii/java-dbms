import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Управляет таблицами и связями между ними.
 */
public final class Database {
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
            String referencedColumn) {
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
        foreignKeys.add(foreignKey);
    }

    /**
     * Добавляет строку в указанную таблицу.
     *
     * @param tableName имя таблицы
     * @param values значения строки
     */
    public void insert(String tableName, Map<String, Object> values) {
        String normalizedTableName = Table.normalizeIdentifier(tableName);
        Objects.requireNonNull(values, "Значения строки не заданы");
        for (ForeignKey foreignKey : foreignKeys) {
            if (!foreignKey.table().equals(normalizedTableName)) {
                continue;
            }
            Object value = findValue(values, foreignKey.column());
            if (value != null && !getTable(foreignKey.referencedTable())
                    .containsValue(foreignKey.referencedColumn(), value)) {
                throw new IllegalArgumentException("Нарушена связь по внешнему ключу");
            }
        }
        getTable(normalizedTableName).insert(values);
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

    private Object findValue(Map<String, Object> values, String columnName) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (Table.normalizeIdentifier(entry.getKey()).equals(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
