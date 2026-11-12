import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Запускает консольное приложение Simple DBMS.
 */
public final class Main {
    private static final Path DATABASE_FILE = Path.of("data", "database.bin");

    private Main() {
    }

    /**
     * Запускает интерактивную консоль.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        run(input, output, DATABASE_FILE);
    }

    static void run(BufferedReader input, PrintWriter output, Path databaseFile) {
        Database database;
        try {
            database = Storage.load(databaseFile);
        } catch (IOException exception) {
            output.println("Cannot load database: " + exception.getMessage());
            return;
        }
        SqlEngine engine = new SqlEngine(database);
        output.println("Simple DBMS");
        output.println("Type help for examples or exit to quit.");
        while (true) {
            output.print("db> ");
            output.flush();
            String line;
            try {
                line = input.readLine();
            } catch (IOException exception) {
                output.println("Input error: " + exception.getMessage());
                return;
            }
            if (line == null || line.trim().equalsIgnoreCase("exit")
                    || line.trim().equalsIgnoreCase("quit")) {
                output.println("Bye.");
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            if (line.trim().equalsIgnoreCase("help")) {
                printHelp(output);
                continue;
            }
            try {
                SqlEngine.ExecutionResult result = engine.execute(line);
                if (result.changed()) {
                    Storage.save(database, databaseFile);
                }
                output.println(result.output());
                if (result.indexUsed()) {
                    output.println("Index used: yes");
                }
            } catch (IllegalArgumentException exception) {
                output.println("Query error: " + exception.getMessage());
            } catch (IOException exception) {
                output.println("Save error: " + exception.getMessage());
            }
        }
    }

    private static void printHelp(PrintWriter output) {
        output.println("INSERT INTO brands VALUES (1, 'Toyota');");
        output.println("INSERT INTO cars VALUES (1, 1, 'Camry', 25000, '2024-05-20', TRUE);");
        output.println("SELECT * FROM cars WHERE price > 20000;");
        output.println("UPDATE cars SET price = 30000 WHERE id = 1;");
        output.println("DELETE FROM cars WHERE id = 1;");
        output.println("CREATE INDEX idx_cars_model ON cars (model);");
    }
}
