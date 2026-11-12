import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Проверяет консольный интерфейс и обработку ошибок.
 */
public final class MainTest {
    private MainTest() {
    }

    /**
     * Запускает проверку консольного сценария.
     *
     * @param args аргументы командной строки
     * @throws Exception при ошибке временного файла
     */
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("simple-dbms-main-");
        Path file = directory.resolve("database.bin");
        try {
            String commands = String.join(System.lineSeparator(),
                    "INSERT INTO brands VALUES ('wrong', 'Toyota');",
                    "INSERT INTO brands VALUES (1, 'Toyota');",
                    "SELECT * FROM brands;",
                    "exit");
            StringWriter textOutput = new StringWriter();
            Main.run(
                    new BufferedReader(new StringReader(commands)),
                    new PrintWriter(textOutput, true),
                    file);
            String output = textOutput.toString();
            check(output.contains("Query error:"));
            check(output.contains("Toyota"));
            check(output.contains("Bye."));
            Database restored = Storage.load(file);
            check(restored.getTable("brands").getRows().size() == 1);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".tmp"));
            Files.deleteIfExists(Path.of(file + ".bak"));
            Files.deleteIfExists(directory);
        }
        System.out.println("Main console tests passed.");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Check failed");
        }
    }
}
