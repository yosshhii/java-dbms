import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбирает и выполняет SQL-подобные команды.
 */
public final class SqlEngine {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern INSERT = Pattern.compile(
            "^INSERT\\s+INTO\\s+([a-z_][a-z0-9_]*)(?:\\s*\\(([^)]*)\\))?\\s+VALUES\\s+(.+)$",
            FLAGS);
    private static final Pattern SELECT = Pattern.compile(
            "^SELECT\\s+(.+?)\\s+FROM\\s+([a-z_][a-z0-9_]*)(?:\\s+WHERE\\s+(.+))?$",
            FLAGS);
    private static final Pattern UPDATE = Pattern.compile(
            "^UPDATE\\s+([a-z_][a-z0-9_]*)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$",
            FLAGS);
    private static final Pattern DELETE = Pattern.compile(
            "^DELETE\\s+FROM\\s+([a-z_][a-z0-9_]*)(?:\\s+WHERE\\s+(.+))?$",
            FLAGS);
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^CREATE\\s+INDEX\\s+([a-z_][a-z0-9_]*)\\s+ON\\s+([a-z_][a-z0-9_]*)"
                    + "\\s*\\(\\s*([a-z_][a-z0-9_]*)\\s*\\)$",
            FLAGS);
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^CREATE\\s+TABLE\\s+([a-z_][a-z0-9_]*)\\s*\\((.+)\\)$",
            FLAGS);
    private static final Pattern DROP_TABLE = Pattern.compile(
            "^DROP\\s+TABLE\\s+([a-z_][a-z0-9_]*)$",
            FLAGS);
    private static final Pattern CONDITION = Pattern.compile(
            "^([a-z_][a-z0-9_]*)\\s*(<=|>=|!=|=|<|>)\\s*(.+)$",
            FLAGS);
    private final Database database;

    /**
     * Содержит текст ответа и сведения о выполненной команде.
     *
     * @param output текст ответа
     * @param changed признак изменения базы
     * @param indexUsed признак применения индекса
     * @param columns столбцы результата SELECT
     * @param rows строки результата SELECT
     */
    public record ExecutionResult(String output, boolean changed, boolean indexUsed,
                                  List<String> columns, List<Map<String, Object>> rows) {
        /**
         * Создаёт результат выполнения команды.
         *
         * @param output текст ответа
         * @param changed признак изменения базы
         * @param indexUsed признак применения индекса
         * @param columns столбцы результата SELECT
         * @param rows строки результата SELECT
         */
        public ExecutionResult {
            Objects.requireNonNull(output, "Ответ не задан");
            columns = List.copyOf(columns);
            rows = rows.stream()
                    .map(row -> java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row)))
                    .toList();
        }

        /**
         * Создаёт результат команды без табличных данных.
         *
         * @param output текст ответа
         * @param changed признак изменения базы
         * @param indexUsed признак применения индекса
         */
        public ExecutionResult(String output, boolean changed, boolean indexUsed) {
            this(output, changed, indexUsed, List.of(), List.of());
        }
    }

    /**
     * Создаёт обработчик команд для базы данных.
     *
     * @param database база данных
     */
    public SqlEngine(Database database) {
        this.database = Objects.requireNonNull(database, "База данных не задана");
    }

    /**
     * Выполняет одну SQL-подобную команду.
     *
     * @param sql текст команды
     * @return результат выполнения
     */
    public ExecutionResult execute(String sql) {
        String statement = normalizeStatement(sql);
        String keyword = statement.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        return switch (keyword) {
            case "INSERT" -> executeInsert(statement);
            case "SELECT" -> executeSelect(statement);
            case "UPDATE" -> executeUpdate(statement);
            case "DELETE" -> executeDelete(statement);
            case "CREATE" -> executeCreate(statement);
            case "DROP" -> executeDropTable(statement);
            default -> throw new IllegalArgumentException("Неизвестная команда: " + keyword);
        };
    }

    private ExecutionResult executeInsert(String statement) {
        Matcher matcher = requireMatch(INSERT, statement, "Некорректный INSERT");
        String tableName = matcher.group(1);
        Table table = database.getTable(tableName);
        List<String> columns = matcher.group(2) == null
                ? table.getColumns().stream().map(Table.Column::name).toList()
                : parseIdentifiers(matcher.group(2));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Не указаны столбцы INSERT");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String group : parseValueGroups(matcher.group(3))) {
            List<String> tokens = splitCommaAware(group);
            if (tokens.size() != columns.size()) {
                throw new IllegalArgumentException("Количество значений не совпадает с числом столбцов");
            }
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (int index = 0; index < columns.size(); index++) {
                Table.Column column = table.getColumn(columns.get(index));
                values.put(column.name(), parseLiteral(tokens.get(index), column.type()));
            }
            rows.add(values);
        }
        database.insertAll(tableName, rows);
        return new ExecutionResult("Inserted rows: " + rows.size(), true, false);
    }

    private ExecutionResult executeSelect(String statement) {
        Matcher matcher = requireMatch(SELECT, statement, "Некорректный SELECT");
        String tableName = matcher.group(2);
        Table table = database.getTable(tableName);
        List<String> columns = matcher.group(1).trim().equals("*")
                ? table.getColumns().stream().map(Table.Column::name).toList()
                : parseIdentifiers(matcher.group(1));
        Table.Condition condition = parseCondition(table, matcher.group(3));
        Table.QueryResult queryResult = database.select(tableName, columns, condition);
        return new ExecutionResult(
                formatTable(columns, queryResult.rows()),
                false,
                queryResult.indexUsed(), columns, queryResult.rows());
    }

    private ExecutionResult executeUpdate(String statement) {
        Matcher matcher = requireMatch(UPDATE, statement, "Некорректный UPDATE");
        String tableName = matcher.group(1);
        Table table = database.getTable(tableName);
        Map<String, Object> assignments = parseAssignments(table, matcher.group(2));
        Table.Condition condition = parseCondition(table, matcher.group(3));
        int updated = database.update(tableName, assignments, condition);
        return new ExecutionResult("Updated rows: " + updated, updated > 0, false);
    }

    private ExecutionResult executeDelete(String statement) {
        Matcher matcher = requireMatch(DELETE, statement, "Некорректный DELETE");
        String tableName = matcher.group(1);
        Table table = database.getTable(tableName);
        Table.Condition condition = parseCondition(table, matcher.group(2));
        int deleted = database.delete(tableName, condition);
        return new ExecutionResult("Deleted rows: " + deleted, deleted > 0, false);
    }

    private ExecutionResult executeCreate(String statement) {
        Matcher indexMatcher = CREATE_INDEX.matcher(statement);
        if (indexMatcher.matches()) {
            database.createIndex(indexMatcher.group(1), indexMatcher.group(2), indexMatcher.group(3));
            return new ExecutionResult("Index created: " + indexMatcher.group(1), true, false);
        }
        Matcher tableMatcher = CREATE_TABLE.matcher(statement);
        if (tableMatcher.matches()) {
            createTable(tableMatcher.group(1), tableMatcher.group(2));
            return new ExecutionResult("Table created: " + tableMatcher.group(1), true, false);
        }
        throw new IllegalArgumentException("Некорректная команда CREATE");
    }

    private ExecutionResult executeDropTable(String statement) {
        Matcher matcher = requireMatch(DROP_TABLE, statement, "Некорректный DROP TABLE");
        database.dropTable(matcher.group(1));
        return new ExecutionResult("Table dropped: " + matcher.group(1), true, false);
    }

    private void createTable(String tableName, String definitions) {
        List<Table.Column> columns = new ArrayList<>();
        String primaryKey = null;
        for (String definition : splitCommaAware(definitions)) {
            String[] parts = definition.trim().split("\\s+");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Некорректное описание столбца: " + definition);
            }
            String columnName = parts[0];
            Table.DataType type = parseDataType(parts[1]);
            String modifiers = definition.toUpperCase(Locale.ROOT);
            boolean isPrimaryKey = modifiers.matches(".*\\bPRIMARY\\s+KEY\\b.*");
            boolean required = isPrimaryKey || modifiers.matches(".*\\bNOT\\s+NULL\\b.*");
            if (isPrimaryKey) {
                if (primaryKey != null) {
                    throw new IllegalArgumentException("Допускается только один первичный ключ");
                }
                primaryKey = columnName;
            }
            columns.add(new Table.Column(columnName, type, required));
        }
        if (primaryKey == null) {
            throw new IllegalArgumentException("Не указан PRIMARY KEY");
        }
        database.addTable(new Table(tableName, columns, primaryKey));
    }

    private Map<String, Object> parseAssignments(Table table, String text) {
        LinkedHashMap<String, Object> assignments = new LinkedHashMap<>();
        for (String assignment : splitCommaAware(text)) {
            int equalsIndex = findOutsideQuotes(assignment, '=');
            if (equalsIndex <= 0 || equalsIndex == assignment.length() - 1) {
                throw new IllegalArgumentException("Некорректное присваивание: " + assignment);
            }
            String columnName = assignment.substring(0, equalsIndex).trim();
            Table.Column column = table.getColumn(columnName);
            if (assignments.containsKey(column.name())) {
                throw new IllegalArgumentException("Столбец задан повторно: " + column.name());
            }
            assignments.put(
                    column.name(),
                    parseLiteral(assignment.substring(equalsIndex + 1), column.type()));
        }
        return assignments;
    }

    private Table.Condition parseCondition(Table table, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = requireMatch(CONDITION, text.trim(), "Некорректное условие WHERE");
        Table.Column column = table.getColumn(matcher.group(1));
        Table.Operator operator = switch (matcher.group(2)) {
            case "=" -> Table.Operator.EQUALS;
            case "!=" -> Table.Operator.NOT_EQUALS;
            case "<" -> Table.Operator.LESS;
            case "<=" -> Table.Operator.LESS_OR_EQUALS;
            case ">" -> Table.Operator.GREATER;
            case ">=" -> Table.Operator.GREATER_OR_EQUALS;
            default -> throw new IllegalArgumentException("Неизвестный оператор");
        };
        return new Table.Condition(
                column.name(),
                operator,
                parseLiteral(matcher.group(3), column.type()));
    }

    private Object parseLiteral(String token, Table.DataType type) {
        String value = token.trim();
        if (value.equalsIgnoreCase("NULL")) {
            return null;
        }
        try {
            return switch (type) {
                case INTEGER -> Integer.valueOf(value);
                case DOUBLE -> Double.valueOf(value);
                case STRING -> parseString(value);
                case DATE -> LocalDate.parse(parseString(value));
                case BOOLEAN -> parseBoolean(value);
            };
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw new IllegalArgumentException("Некорректное значение " + type + ": " + value, exception);
        }
    }

    private String parseString(String token) {
        if (token.length() < 2 || token.charAt(0) != '\'' || token.charAt(token.length() - 1) != '\'') {
            throw new IllegalArgumentException("Строка или дата должна находиться в одинарных кавычках");
        }
        return token.substring(1, token.length() - 1).replace("''", "'");
    }

    private Boolean parseBoolean(String token) {
        if (token.equalsIgnoreCase("TRUE")) {
            return true;
        }
        if (token.equalsIgnoreCase("FALSE")) {
            return false;
        }
        throw new IllegalArgumentException("Логическое значение должно быть TRUE или FALSE");
    }

    private Table.DataType parseDataType(String token) {
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "INT", "INTEGER" -> Table.DataType.INTEGER;
            case "DOUBLE", "FLOAT" -> Table.DataType.DOUBLE;
            case "STRING", "VARCHAR" -> Table.DataType.STRING;
            case "DATE" -> Table.DataType.DATE;
            case "BOOL", "BOOLEAN" -> Table.DataType.BOOLEAN;
            default -> throw new IllegalArgumentException("Неизвестный тип данных: " + token);
        };
    }

    private List<String> parseIdentifiers(String text) {
        return splitCommaAware(text).stream().map(Table::normalizeIdentifier).toList();
    }

    private List<String> parseValueGroups(String text) {
        List<String> groups = new ArrayList<>();
        boolean quoted = false;
        int depth = 0;
        int start = -1;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\'' && quoted && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
            } else if (!quoted && character == '(') {
                if (depth == 0) {
                    start = index + 1;
                }
                depth++;
            } else if (!quoted && character == ')') {
                depth--;
                if (depth < 0 || start < 0) {
                    throw new IllegalArgumentException("Некорректный список VALUES");
                }
                if (depth == 0) {
                    groups.add(text.substring(start, index));
                    start = -1;
                }
            } else if (!quoted && depth == 0 && character != ',' && !Character.isWhitespace(character)) {
                throw new IllegalArgumentException("Некорректный список VALUES");
            }
        }
        if (quoted || depth != 0 || groups.isEmpty()) {
            throw new IllegalArgumentException("Некорректный список VALUES");
        }
        return groups;
    }

    private List<String> splitCommaAware(String text) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\'' && quoted && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                addPart(parts, text.substring(start, index));
                start = index + 1;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Незакрытая строка");
        }
        addPart(parts, text.substring(start));
        return parts;
    }

    private void addPart(List<String> parts, String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Обнаружено пустое значение");
        }
        parts.add(trimmed);
    }

    private int findOutsideQuotes(String text, char target) {
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\'' && quoted && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
            } else if (character == target && !quoted) {
                return index;
            }
        }
        return -1;
    }

    private String formatTable(List<String> columns, List<Map<String, Object>> rows) {
        List<Integer> widths = new ArrayList<>();
        for (String column : columns) {
            int width = column.length();
            for (Map<String, Object> row : rows) {
                width = Math.max(width, formatValue(row.get(column)).length());
            }
            widths.add(width);
        }
        String border = buildBorder(widths);
        StringBuilder output = new StringBuilder(border).append(System.lineSeparator());
        appendRow(output, columns, widths);
        output.append(border).append(System.lineSeparator());
        for (Map<String, Object> row : rows) {
            List<String> values = columns.stream().map(column -> formatValue(row.get(column))).toList();
            appendRow(output, values, widths);
        }
        output.append(border).append(System.lineSeparator());
        output.append("Rows: ").append(rows.size());
        return output.toString();
    }

    private String buildBorder(List<Integer> widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append('+');
        }
        return border.toString();
    }

    private void appendRow(StringBuilder output, List<String> values, List<Integer> widths) {
        output.append('|');
        for (int index = 0; index < values.size(); index++) {
            output.append(' ').append(values.get(index));
            output.append(" ".repeat(widths.get(index) - values.get(index).length() + 1));
            output.append('|');
        }
        output.append(System.lineSeparator());
    }

    private String formatValue(Object value) {
        return value == null ? "NULL" : value.toString();
    }

    private Matcher requireMatch(Pattern pattern, String text, String message) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(message);
        }
        return matcher;
    }

    private String normalizeStatement(String sql) {
        Objects.requireNonNull(sql, "Команда не задана");
        String statement = sql.trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("Команда пуста");
        }
        return statement;
    }
}
