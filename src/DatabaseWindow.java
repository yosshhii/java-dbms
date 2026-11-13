import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/**
 * Окно СУБД: таблицы, формы CRUD, SQL-редактор и журнал выполнения.
 * Чтение, изменение и сохранение базы выполняются вне потока событий Swing.
 */
@SuppressWarnings("serial")
public final class DatabaseWindow extends JFrame {
    private static final Color NAVY = new Color(25, 42, 62);
    private static final Color TEAL = new Color(0, 116, 108);
    private static final Color PAPER = new Color(245, 247, 249);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private final DatabaseSession session;
    private final DefaultListModel<String> tableNames = new DefaultListModel<>();
    private final JList<String> tableList = new JList<>(tableNames);
    private final JTextArea schema = new JTextArea();
    private final JTextArea editor = new JTextArea("SELECT * FROM brands;", 6, 60);
    private final JTextArea journal = new JTextArea();
    private final DataModel dataModel = new DataModel();
    private final DataModel queryModel = new DataModel();
    private final JTable dataTable = createTable(dataModel);
    private final JTable queryTable = createTable(queryModel);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel tableTitle = new JLabel("Таблицы");
    private final JLabel resultInfo = new JLabel("Выполните SELECT, чтобы увидеть результат запроса");
    private final JLabel status = new JLabel("Загрузка…");
    private final List<JComponent> controls = new ArrayList<>();
    private List<TableInfo> tables = List.of();
    private TableInfo selectedTable;
    private boolean busy;
    private boolean refreshing;
    private boolean dirty;
    private boolean closeAfterSave;

    private record TableInfo(String name, List<Table.Column> columns, String primaryKey,
                             Map<String, String> indexes, List<Database.ForeignKey> relations,
                             int rowCount) {
    }

    private record LogEntry(String sql, String output, double milliseconds, boolean indexUsed) {
    }

    private record Outcome(List<TableInfo> tables, String selection, List<String> columns,
                           List<Map<String, Object>> rows, List<LogEntry> log,
                           SqlEngine.ExecutionResult query, Exception error, boolean dirty) {
    }

    /**
     * Создаёт окно для загруженной базы; вызывается на потоке событий Swing.
     *
     * @param session сервис выполнения и сохранения запросов
     */
    public DatabaseWindow(DatabaseSession session) {
        super("Simple DBMS — лабораторная работа 2");
        this.session = session;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(920, 680));
        setSize(1200, 820);
        setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(PAPER);
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        root.add(createHeader(), BorderLayout.NORTH);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createSidebar(), createWorkspace());
        mainSplit.setBorder(null);
        mainSplit.setDividerLocation(250);
        mainSplit.setResizeWeight(0);
        root.add(mainSplit, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
                "executeSql");
        editor.getActionMap().put("executeSql", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                executeEditor();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWindow();
            }
        });
        runTask(List.of(), null, false);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Simple DBMS");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        title.setForeground(NAVY);
        JLabel subtitle = new JLabel("Таблицы, SQL и индексы · Java / Swing");
        subtitle.setForeground(TEAL);
        JPanel heading = new JPanel(new BorderLayout(0, 4));
        heading.setOpaque(false);
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.SOUTH);
        header.add(heading, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setOpaque(false);
        actions.add(button("Сохранить", () -> runTask(List.of(), currentName(), true)));
        actions.add(button("Справка", this::showHelp));
        header.add(actions, BorderLayout.EAST);
        header.setToolTipText(session.getFile().toString());
        return header;
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 10));
        sidebar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        tableList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tableList.setFixedCellHeight(32);
        tableList.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        controls.add(tableList);
        tableList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !refreshing && !busy) {
                runTask(List.of(), tableList.getSelectedValue(), false);
            }
        });
        JPanel listPanel = new JPanel(new BorderLayout(0, 8));
        listPanel.add(new JLabel("ТАБЛИЦЫ БАЗЫ"), BorderLayout.NORTH);
        listPanel.add(new JScrollPane(tableList), BorderLayout.CENTER);
        schema.setEditable(false);
        schema.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        schema.setLineWrap(true);
        schema.setWrapStyleWord(true);
        schema.setMargin(new Insets(10, 8, 10, 8));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                listPanel, new JScrollPane(schema));
        split.setResizeWeight(0.35);
        split.setDividerLocation(180);
        split.setBorder(null);
        sidebar.add(split, BorderLayout.CENTER);
        sidebar.add(button("Создать таблицу…", () -> setEditor(
                "CREATE TABLE notes (\n  id INTEGER PRIMARY KEY,\n  text STRING,\n"
                        + "  created DATE,\n  active BOOLEAN\n);")), BorderLayout.SOUTH);
        return sidebar;
    }

    private JSplitPane createWorkspace() {
        JPanel browser = new JPanel(new BorderLayout(0, 8));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(button("Добавить запись…", () -> editRow(false)));
        toolbar.add(button("Изменить…", () -> editRow(true)));
        toolbar.add(button("Удалить", this::deleteRow));
        toolbar.add(button("Создать индекс…", this::createIndex));
        toolbar.add(button("Обновить", () -> runTask(List.of(), currentName(), false)));
        JPanel dataPanel = new JPanel(new BorderLayout(0, 8));
        tableTitle.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        dataPanel.add(tableTitle, BorderLayout.NORTH);
        dataPanel.add(new JScrollPane(dataTable), BorderLayout.CENTER);
        JPanel queryPanel = new JPanel(new BorderLayout(0, 8));
        resultInfo.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        queryPanel.add(resultInfo, BorderLayout.NORTH);
        queryPanel.add(new JScrollPane(queryTable), BorderLayout.CENTER);
        journal.setEditable(false);
        journal.setFont(MONO);
        journal.setMargin(new Insets(8, 10, 8, 10));
        tabs.addTab("Данные", dataPanel);
        tabs.addTab("Результат SQL", queryPanel);
        tabs.addTab("Журнал", new JScrollPane(journal));
        browser.add(toolbar, BorderLayout.NORTH);
        browser.add(tabs, BorderLayout.CENTER);

        JPanel sqlPanel = new JPanel(new BorderLayout(0, 8));
        sqlPanel.setBorder(BorderFactory.createEmptyBorder(10, 8, 4, 8));
        JPanel sqlToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sqlToolbar.add(new JLabel("SQL-редактор"));
        JButton execute = button("Выполнить  ·  Ctrl+Enter", this::executeEditor);
        execute.setForeground(TEAL);
        sqlToolbar.add(execute);
        JComboBox<String> examples = new JComboBox<>(new String[]{"Примеры команд…",
                "Добавить бренды", "Добавить автомобили", "Выборка по цене",
                "Обновить цену", "Удалить по условию", "Создать индекс",
                "Поиск с индексом", "Удалить таблицу"});
        controls.add(examples);
        examples.addActionListener(event -> {
            String sql = switch (examples.getSelectedIndex()) {
                case 1 -> "INSERT INTO brands VALUES (1, 'Toyota'), (2, 'BMW');\nSELECT * FROM brands;";
                case 2 -> "INSERT INTO cars VALUES\n"
                        + "  (1, 1, 'Camry', 25000.0, '2024-05-20', TRUE),\n"
                        + "  (2, 2, 'X3', 32000.0, '2025-01-15', FALSE);\nSELECT * FROM cars;";
                case 3 -> "SELECT model, price FROM cars WHERE price > 20000;";
                case 4 -> "UPDATE cars SET price = 30000 WHERE id = 2;\nSELECT * FROM cars;";
                case 5 -> "DELETE FROM cars WHERE id = 2;";
                case 6 -> "CREATE INDEX idx_cars_model ON cars (model);";
                case 7 -> "SELECT * FROM cars WHERE model = 'Camry';";
                case 8 -> "DROP TABLE notes;";
                default -> null;
            };
            if (sql != null) {
                setEditor(sql);
                examples.setSelectedIndex(0);
            }
        });
        sqlToolbar.add(examples);
        editor.setFont(MONO);
        editor.setMargin(new Insets(10, 10, 10, 10));
        editor.setTabSize(2);
        controls.add(editor);
        sqlPanel.add(sqlToolbar, BorderLayout.NORTH);
        sqlPanel.add(new JScrollPane(editor), BorderLayout.CENTER);
        sqlPanel.add(new JLabel("Команды разделяются ;  ·  Скрипт останавливается при первой ошибке"),
                BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, browser, sqlPanel);
        split.setResizeWeight(0.72);
        split.setDividerLocation(410);
        split.setBorder(null);
        return split;
    }

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        controls.add(button);
        return button;
    }

    private static JTable createTable(DataModel model) {
        JTable table = new JTable(model) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                if (component instanceof JLabel label) {
                    label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                }
                return component;
            }
        };
        table.setRowHeight(28);
        table.setAutoCreateRowSorter(true);
        DefaultTableCellRenderer decimalRenderer = new DefaultTableCellRenderer();
        decimalRenderer.setHorizontalAlignment(JLabel.RIGHT);
        table.setDefaultRenderer(Double.class, decimalRenderer);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowGrid(true);
        table.setGridColor(new Color(232, 237, 242));
        table.setIntercellSpacing(new Dimension(1, 1));
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    private String currentName() {
        return selectedTable == null ? null : selectedTable.name();
    }

    private void setEditor(String sql) {
        editor.setText(sql);
        editor.setCaretPosition(0);
        editor.requestFocusInWindow();
    }

    private void executeEditor() {
        if (busy) {
            return;
        }
        try {
            String text = editor.getSelectedText();
            List<String> statements = DatabaseSession.splitStatements(text == null ? editor.getText() : text);
            if (statements.isEmpty()) {
                status.setText("Введите SQL-команду в редакторе");
                return;
            }
            runTask(statements, currentName(), false);
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void runTask(List<String> statements, String selection, boolean saveOnly) {
        if (busy) {
            return;
        }
        setBusy(true);
        status.setText("Выполнение…");
        new SwingWorker<Outcome, Void>() {
            @Override
            protected Outcome doInBackground() {
                List<LogEntry> entries = new ArrayList<>();
                SqlEngine.ExecutionResult lastQuery = null;
                Exception error = null;
                try {
                    if (saveOnly) {
                        session.save();
                    }
                    for (String sql : statements) {
                        long started = System.nanoTime();
                        try {
                            SqlEngine.ExecutionResult result = session.execute(sql);
                            entries.add(new LogEntry(sql, result.output(),
                                    (System.nanoTime() - started) / 1_000_000.0, result.indexUsed()));
                            if (!result.columns().isEmpty()) {
                                lastQuery = result;
                            }
                        } catch (IOException | IllegalArgumentException exception) {
                            entries.add(new LogEntry(sql, "Ошибка: " + exception.getMessage(),
                                    (System.nanoTime() - started) / 1_000_000.0, false));
                            throw exception;
                        }
                    }
                } catch (IOException | IllegalArgumentException exception) {
                    error = exception;
                }
                Database database = session.getDatabase();
                List<TableInfo> snapshots = database.getTables().stream().map(table ->
                        new TableInfo(table.getName(), table.getColumns(), table.getPrimaryKey(),
                                table.getIndexes(), database.getForeignKeys().stream().filter(key ->
                                        key.table().equals(table.getName())
                                                || key.referencedTable().equals(table.getName())).toList(),
                                table.getRows().size())).toList();
                String chosen = snapshots.stream().anyMatch(table -> table.name().equals(selection))
                        ? selection : snapshots.isEmpty() ? null : snapshots.get(0).name();
                List<String> columns = List.of();
                List<Map<String, Object>> rows = List.of();
                if (chosen != null) {
                    Table table = database.getTable(chosen);
                    columns = table.getColumns().stream().map(Table.Column::name).toList();
                    rows = table.getRows();
                }
                return new Outcome(snapshots, chosen, columns, rows, entries, lastQuery,
                        error, session.isDirty());
            }

            @Override
            protected void done() {
                try {
                    applyOutcome(get(), saveOnly);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showError("Операция прервана");
                } catch (ExecutionException exception) {
                    showError("Не удалось выполнить операцию: " + exception.getCause().getMessage());
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void applyOutcome(Outcome outcome, boolean saved) {
        tables = outcome.tables();
        dirty = outcome.dirty();
        refreshing = true;
        try {
            tableNames.clear();
            tables.forEach(table -> tableNames.addElement(table.name()));
            tableList.setSelectedValue(outcome.selection(), true);
        } finally {
            refreshing = false;
        }
        selectedTable = tables.stream().filter(table -> table.name().equals(outcome.selection()))
                .findFirst().orElse(null);
        dataModel.setData(outcome.columns(), outcome.rows());
        tableTitle.setText(selectedTable == null ? "В базе нет таблиц"
                : selectedTable.name() + "  ·  " + selectedTable.rowCount() + " строк");
        schema.setText(describeSchema());
        schema.setCaretPosition(0);
        for (LogEntry entry : outcome.log()) {
            journal.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    + "  " + entry.sql() + "\n" + entry.output() + "\n"
                    + String.format(java.util.Locale.ROOT, "%.3f мс", entry.milliseconds())
                    + (entry.indexUsed() ? " · использован индекс" : "") + "\n\n");
        }
        journal.setCaretPosition(journal.getDocument().getLength());
        if (outcome.query() != null) {
            SqlEngine.ExecutionResult query = outcome.query();
            queryModel.setData(query.columns(), query.rows());
            resultInfo.setText(query.rows().size() + " строк · "
                    + (query.indexUsed() ? "Использован индекс" : "Полный просмотр таблицы"));
            tabs.setSelectedIndex(1);
        } else if (!outcome.log().isEmpty()) {
            tabs.setSelectedIndex(0);
        }
        status.setText((dirty ? "Есть несохранённые изменения" : saved ? "База сохранена" : "Готово")
                + "  ·  " + session.getFile());
        if (outcome.error() != null) {
            closeAfterSave = false;
            String message = outcome.error() instanceof IOException
                    ? "Изменения в памяти не удалось сохранить. Нажмите «Сохранить» для повторной попытки.\n"
                            + outcome.error().getMessage()
                    : outcome.error().getMessage();
            status.setText("Ошибка · " + (dirty ? "есть несохранённые изменения · " : "")
                    + outcome.error().getMessage());
            showError(message);
        } else if (closeAfterSave) {
            dispose();
        }
    }

    private String describeSchema() {
        if (selectedTable == null) {
            return "Создайте таблицу командой CREATE TABLE.";
        }
        StringBuilder text = new StringBuilder("СХЕМА / ").append(selectedTable.name()).append("\n\n");
        for (Table.Column column : selectedTable.columns()) {
            text.append(column.name()).append("\n  ").append(column.type());
            if (column.name().equals(selectedTable.primaryKey())) {
                text.append("  [PK]");
            }
            if (column.required()) {
                text.append("  NOT NULL");
            }
            text.append('\n');
        }
        text.append("\nСВЯЗИ\n");
        if (selectedTable.relations().isEmpty()) {
            text.append("Нет внешних ключей\n");
        }
        selectedTable.relations().forEach(key -> text.append(key.table()).append('.').append(key.column())
                .append(" → ").append(key.referencedTable()).append('.').append(key.referencedColumn()).append('\n'));
        text.append("\nИНДЕКСЫ\n");
        if (selectedTable.indexes().isEmpty()) {
            text.append("Индексы не созданы");
        }
        selectedTable.indexes().forEach((name, column) -> text.append(name).append(" (").append(column).append(")\n"));
        return text.toString();
    }

    private void setBusy(boolean value) {
        busy = value;
        controls.forEach(control -> control.setEnabled(!value));
        dataTable.setEnabled(!value);
    }

    private Map<String, Object> selectedRow() {
        if (tabs.getSelectedIndex() != 0 || selectedTable == null || dataTable.getSelectedRow() < 0) {
            showError("Выберите запись на вкладке «Данные»");
            return null;
        }
        return dataModel.rows.get(dataTable.convertRowIndexToModel(dataTable.getSelectedRow()));
    }

    private void editRow(boolean update) {
        if (selectedTable == null) {
            showError("Выберите таблицу");
            return;
        }
        Map<String, Object> row = update ? selectedRow() : Map.of();
        if (row == null) {
            return;
        }
        JPanel form = new JPanel(new GridBagLayout());
        Map<String, JComponent> inputs = new LinkedHashMap<>();
        Map<String, JCheckBox> nulls = new LinkedHashMap<>();
        int position = 0;
        for (Table.Column column : selectedTable.columns()) {
            boolean primary = column.name().equals(selectedTable.primaryKey());
            Object value = row.get(column.name());
            JComponent input = column.type() == Table.DataType.BOOLEAN
                    ? new JComboBox<>(new String[]{"TRUE", "FALSE"})
                    : new JTextField(value == null ? "" : value.toString(), 24);
            if (input instanceof JComboBox<?> combo) {
                combo.setSelectedIndex(Boolean.FALSE.equals(value) ? 1 : 0);
            }
            input.setEnabled(!(update && primary));
            inputs.put(column.name(), input);
            GridBagConstraints cell = new GridBagConstraints();
            cell.insets = new Insets(5, 5, 5, 5);
            cell.gridy = position++;
            cell.anchor = GridBagConstraints.WEST;
            form.add(new JLabel(column.name() + " / " + column.type()
                    + (primary ? " [PK]" : column.required() ? " *" : "")), cell);
            cell.gridx = 1;
            cell.fill = GridBagConstraints.HORIZONTAL;
            cell.weightx = 1;
            form.add(input, cell);
            if (!column.required()) {
                JCheckBox empty = new JCheckBox("NULL", value == null);
                empty.addActionListener(event -> input.setEnabled(!empty.isSelected()));
                input.setEnabled(!empty.isSelected());
                nulls.put(column.name(), empty);
                cell.gridx = 2;
                cell.weightx = 0;
                form.add(empty, cell);
            }
        }
        GridBagConstraints note = new GridBagConstraints();
        note.gridy = position;
        note.gridwidth = 3;
        note.insets = new Insets(12, 5, 5, 5);
        form.add(new JLabel("* Обязательные поля. Дата: YYYY-MM-DD. Кавычки добавляются автоматически."), note);
        int choice = JOptionPane.showConfirmDialog(this, form,
                (update ? "Изменить запись / " : "Добавить запись / ") + currentName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Table.Column column : selectedTable.columns()) {
            if (update && column.name().equals(selectedTable.primaryKey())) {
                continue;
            }
            names.add(column.name());
            JComponent input = inputs.get(column.name());
            String value = input instanceof JTextField field ? field.getText()
                    : String.valueOf(((JComboBox<?>) input).getSelectedItem());
            values.add(nulls.containsKey(column.name()) && nulls.get(column.name()).isSelected()
                    ? "NULL" : literal(column.type(), value));
        }
        if (names.isEmpty()) {
            status.setText("В таблице нет изменяемых столбцов");
            return;
        }
        String sql;
        if (update) {
            List<String> assignments = new ArrayList<>();
            for (int index = 0; index < names.size(); index++) {
                assignments.add(names.get(index) + " = " + values.get(index));
            }
            sql = "UPDATE " + currentName() + " SET " + String.join(", ", assignments)
                    + " WHERE " + selectedTable.primaryKey() + " = " + primaryLiteral(row) + ";";
        } else {
            sql = "INSERT INTO " + currentName() + " (" + String.join(", ", names)
                    + ") VALUES (" + String.join(", ", values) + ");";
        }
        runTask(List.of(sql), currentName(), false);
    }

    private static String literal(Table.DataType type, String value) {
        return type == Table.DataType.STRING || type == Table.DataType.DATE
                ? "'" + value.replace("'", "''") + "'" : value.trim();
    }

    private String primaryLiteral(Map<String, Object> row) {
        Table.Column key = selectedTable.columns().stream()
                .filter(column -> column.name().equals(selectedTable.primaryKey())).findFirst().orElseThrow();
        return literal(key.type(), row.get(key.name()).toString());
    }

    private void deleteRow() {
        Map<String, Object> row = selectedRow();
        if (row == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "Удалить запись " + selectedTable.primaryKey() + " = " + primaryLiteral(row) + "?",
                "Удаление записи", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            runTask(List.of("DELETE FROM " + currentName() + " WHERE " + selectedTable.primaryKey()
                    + " = " + primaryLiteral(row) + ";"), currentName(), false);
        }
    }

    private void createIndex() {
        if (selectedTable == null) {
            showError("Выберите таблицу");
            return;
        }
        JComboBox<String> column = new JComboBox<>(selectedTable.columns().stream()
                .map(Table.Column::name).toArray(String[]::new));
        JTextField name = new JTextField("idx_" + currentName() + "_" + column.getSelectedItem(), 24);
        column.addActionListener(event -> name.setText("idx_" + currentName() + "_" + column.getSelectedItem()));
        Object[] form = {"Столбец", column, "Имя индекса", name};
        if (JOptionPane.showConfirmDialog(this, form, "Создать индекс", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                String identifier = Table.normalizeIdentifier(name.getText());
                runTask(List.of("CREATE INDEX " + identifier + " ON " + currentName()
                        + " (" + column.getSelectedItem() + ");"), currentName(), false);
            } catch (IllegalArgumentException exception) {
                showError(exception.getMessage());
            }
        }
    }

    private void closeWindow() {
        if (busy) {
            status.setText("Дождитесь завершения текущей операции перед закрытием");
            return;
        }
        if (!dirty) {
            dispose();
            return;
        }
        int choice = JOptionPane.showOptionDialog(this,
                "Есть изменения, которые не удалось сохранить на диск.", "Закрытие базы",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new String[]{"Сохранить и выйти", "Выйти без сохранения", "Отмена"}, "Сохранить и выйти");
        if (choice == 0) {
            closeAfterSave = true;
            runTask(List.of(), currentName(), true);
        } else if (choice == 1) {
            dispose();
        }
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(this,
                "1. Выберите таблицу слева. Схема показывает типы, ключи и индексы.\n"
                        + "2. Добавляйте и изменяйте записи через формы на вкладке «Данные».\n"
                        + "3. Введите SQL или выберите пример, затем нажмите Ctrl+Enter.\n"
                        + "   Если выделен текст, выполняется только выделение.\n"
                        + "4. SELECT отображается на вкладке «Результат SQL», сообщения — в журнале.\n"
                        + "5. Каждое изменение автоматически сохраняется вместе с индексами.\n\n"
                        + "Строки и даты в SQL: одинарные кавычки; дата: YYYY-MM-DD.\n"
                        + "Сначала добавьте brands, затем cars с существующим brand_id.\n"
                        + "Несколько команд выполняются последовательно; при ошибке предыдущие\n"
                        + "успешные команды остаются сохранёнными. Индекс ускоряет условие =.\n"
                        + "DROP TABLE разрешён для пустых таблиц без связей.\n\n"
                        + "Файл базы: " + session.getFile(), "Как пользоваться Simple DBMS",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    /** Неизменяемые снимки результата; сортировка выполняется средствами JTable. */
    private static final class DataModel extends AbstractTableModel {
        private List<String> columns = List.of();
        private List<Map<String, Object>> rows = List.of();
        private List<Class<?>> columnClasses = List.of();

        private void setData(List<String> columns, List<Map<String, Object>> rows) {
            this.columns = columns;
            this.rows = rows;
            List<Class<?>> classes = new ArrayList<>();
            for (String column : columns) {
                Class<?> type = Object.class;
                for (Map<String, Object> row : rows) {
                    Object value = row.get(column);
                    if (value == null) {
                        type = Object.class;
                        break;
                    }
                    type = value.getClass();
                }
                classes.add(type);
            }
            columnClasses = List.copyOf(classes);
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            Object value = rows.get(row).get(columns.get(column));
            return value == null ? "NULL" : value;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return columnClasses.get(column);
        }
    }
}
