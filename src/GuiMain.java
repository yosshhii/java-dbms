import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/** Точка входа графического приложения на Swing. */
public final class GuiMain {
    private GuiMain() {
    }

    /**
     * Загружает базу и открывает окно на потоке событий Swing.
     *
     * @param args необязательный путь к файлу базы
     */
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Для GUI нужна графическая среда. Используйте Main для консоли.");
            return;
        }
        configureLookAndFeel();
        try {
            Path file = args.length == 0 ? Path.of("data", "database.bin") : Path.of(args[0]);
            DatabaseSession session = new DatabaseSession(file);
            SwingUtilities.invokeLater(() -> new DatabaseWindow(session).setVisible(true));
        } catch (IOException | IllegalArgumentException exception) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Не удалось загрузить базу:\n" + exception.getMessage(),
                    "Simple DBMS", JOptionPane.ERROR_MESSAGE));
        }
    }

    static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Стандартная тема Swing остаётся доступной на любой поддерживаемой платформе.
        }
        FontUIResource font = new FontUIResource(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 14);
        for (Object key : java.util.List.copyOf(UIManager.getDefaults().keySet())) {
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) {
                UIManager.put(key, font);
            }
        }
    }
}
