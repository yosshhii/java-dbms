import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Проверяет настоящее окно Swing на временной базе, включая формы CRUD. */
public final class DatabaseWindowSmokeTest {
    private static DatabaseWindow window;

    private DatabaseWindowSmokeTest() {
    }

    /**
     * Открывает временное окно, выполняет сценарий и сохраняет снимок в out.
     *
     * @param args не используются
     * @throws Exception при ошибке сценария
     */
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("simple-dbms-gui-");
        Path file = directory.resolve("database.bin");
        try {
            GuiMain.configureLookAndFeel();
            DatabaseSession session = new DatabaseSession(file);
            onEdt(() -> {
                window = new DatabaseWindow(session);
                window.setVisible(true);
                return null;
            });
            awaitReady();
            execute("INSERT INTO brands VALUES (1, 'Toyota'), (2, 'BMW');\n"
                    + "INSERT INTO cars VALUES (1, 1, 'Camry', 25000, '2024-05-20', TRUE),\n"
                    + "(2, 2, 'X3', 32000, '2025-01-15', FALSE);\n"
                    + "CREATE INDEX idx_cars_model ON cars (model);\n"
                    + "SELECT model, price FROM cars WHERE model = 'Camry';");
            onEdt(() -> {
                JTabbedPane tabs = first(window, JTabbedPane.class);
                check(tabs.getSelectedIndex() == 1, "SELECT должен открывать результат");
                JTable result = first(tabs.getComponentAt(1), JTable.class);
                check(result.getRowCount() == 1 && result.getColumnCount() == 2, "Проекция SELECT");
                check(result.getValueAt(0, 0).equals("Camry"), "Данные SELECT");
                first(window, JList.class).setSelectedValue("cars", true);
                return null;
            });
            awaitReady();
            onEdt(() -> {
                JTabbedPane tabs = first(window, JTabbedPane.class);
                tabs.setSelectedIndex(0);
                JTable data = first(tabs.getComponentAt(0), JTable.class);
                data.getRowSorter().toggleSortOrder(0);
                data.getRowSorter().toggleSortOrder(0);
                data.setRowSelectionInterval(0, 0);
                return null;
            });
            JDialog edit = openDialog("Изменить…");
            onEdt(() -> {
                List<JTextField> fields = descendants(edit, JTextField.class);
                check(fields.get(0).getText().equals("2") && !fields.get(0).isEnabled(),
                        "Форма должна изменять выбранную строку после сортировки");
                fields.get(2).setText("Driver's X3");
                fields.get(3).setText("33000.125678");
                edit.getRootPane().getDefaultButton().doClick();
                return null;
            });
            awaitReady();
            onEdt(() -> {
                JTable data = first(first(window, JTabbedPane.class).getComponentAt(0), JTable.class);
                JLabel price = (JLabel) data.prepareRenderer(data.getCellRenderer(1, 3), 1, 3);
                check(price.getText().equals("33000.125678"), "GUI не должен округлять DOUBLE при выводе");
                return null;
            });
            Database restored = Storage.load(file);
            check(restored.select("cars", List.of(),
                    new Table.Condition("model", Table.Operator.EQUALS, "Driver's X3")).rows().size() == 1,
                    "Форма UPDATE и экранирование апострофа");
            check(restored.select("cars", List.of(),
                    new Table.Condition("model", Table.Operator.EQUALS, "X3")).rows().isEmpty(),
                    "Индекс обновлён после формы UPDATE");

            JDialog insert = openDialog("Добавить запись…");
            onEdt(() -> {
                List<JTextField> fields = descendants(insert, JTextField.class);
                String[] values = {"3", "1", "Corolla", "18000", "2023-03-10"};
                for (int index = 0; index < values.length; index++) {
                    fields.get(index).setText(values[index]);
                }
                insert.getRootPane().getDefaultButton().doClick();
                return null;
            });
            awaitReady();
            check(Storage.load(file).getTable("cars").getRows().size() == 3, "Форма INSERT сохраняет запись");
            onEdt(() -> {
                JTable data = first(first(window, JTabbedPane.class).getComponentAt(0), JTable.class);
                data.setRowSelectionInterval(2, 2);
                return null;
            });
            JDialog delete = openDialog("Удалить");
            onEdt(() -> {
                delete.getRootPane().getDefaultButton().doClick();
                return null;
            });
            awaitReady();
            check(Storage.load(file).getTable("cars").getRows().size() == 2, "Форма DELETE сохраняет удаление");
            execute("SELECT model, price FROM cars WHERE id = 99;");
            onEdt(() -> {
                JTable result = first(first(window, JTabbedPane.class).getComponentAt(1), JTable.class);
                check(result.getRowCount() == 0 && result.getColumnCount() == 2,
                        "Заголовки пустого SELECT отображаются");
                return null;
            });
            execute("SELECT * FROM cars WHERE model = 'Camry';");
            onEdt(() -> {
                first(window, JTabbedPane.class).setSelectedIndex(0);
                BufferedImage screenshot = new BufferedImage(window.getWidth(), window.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = screenshot.createGraphics();
                try {
                    window.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                ImageIO.write(screenshot, "png", Path.of("out", "gui-smoke.png").toFile());
                return null;
            });
        } finally {
            onEdt(() -> {
                for (Window item : Window.getWindows()) {
                    item.dispose();
                }
                return null;
            });
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".bak"));
            Files.deleteIfExists(Path.of(file + ".tmp"));
            Files.deleteIfExists(directory);
        }
        System.out.println("GUI smoke tests passed. Screenshot: out/gui-smoke.png");
    }

    private static void execute(String sql) throws Exception {
        onEdt(() -> {
            descendants(window, JTextArea.class).stream().filter(JTextArea::isEditable)
                    .findFirst().orElseThrow().setText(sql);
            button("Выполнить").doClick();
            return null;
        });
        awaitReady();
    }

    private static JDialog openDialog(String buttonText) throws Exception {
        SwingUtilities.invokeLater(() -> button(buttonText).doClick());
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            JDialog dialog = onEdt(() -> {
                for (Window item : Window.getWindows()) {
                    if (item instanceof JDialog found && found.isShowing()) {
                        return found;
                    }
                }
                return null;
            });
            if (dialog != null) {
                return dialog;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Диалог не открылся: " + buttonText);
    }

    private static void awaitReady() throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (onEdt(() -> button("Выполнить").isEnabled())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Окно не завершило операцию");
    }

    private static JButton button(String prefix) {
        return descendants(window, JButton.class).stream()
                .filter(button -> button.getText() != null && button.getText().startsWith(prefix))
                .findFirst().orElseThrow();
    }

    private static <T extends Component> T first(Component root, Class<T> type) {
        return descendants(root, type).stream().findFirst().orElseThrow();
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) {
            result.add(type.cast(root));
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                result.addAll(descendants(child, type));
            }
        }
        return result;
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
