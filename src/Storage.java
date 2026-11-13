import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Сохраняет базу данных на диск и восстанавливает её при запуске.
 */
public final class Storage {
    private Storage() {
    }

    /**
     * Загружает базу данных или создаёт стандартную схему при отсутствии файла.
     *
     * @param file файл базы данных
     * @return загруженная или новая база данных
     * @throws IOException при ошибке чтения
     */
    public static Database load(Path file) throws IOException {
        Objects.requireNonNull(file, "Путь к файлу не задан");
        if (Files.notExists(file)) {
            if (Files.exists(backupPath(file.toAbsolutePath()))) {
                return restoreBackup(file);
            }
            return Database.createDefault();
        }
        try {
            return read(file);
        } catch (IOException exception) {
            Path backupFile = backupPath(file.toAbsolutePath());
            if (Files.notExists(backupFile)) {
                throw exception;
            }
            return restoreBackup(file);
        }
    }

    private static Database restoreBackup(Path file) throws IOException {
        Path backupFile = backupPath(file.toAbsolutePath());
        Database recovered = read(backupFile);
        Files.copy(backupFile, file, StandardCopyOption.REPLACE_EXISTING);
        return recovered;
    }

    private static Database read(Path file) throws IOException {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            Object value = input.readObject();
            if (!(value instanceof Database database)) {
                throw new IOException("Файл не содержит базу данных");
            }
            return database;
        } catch (ClassNotFoundException exception) {
            throw new IOException("Не удалось восстановить структуру базы данных", exception);
        }
    }

    /**
     * Атомарно сохраняет базу данных в файл.
     *
     * @param database база данных
     * @param file файл назначения
     * @throws IOException при ошибке записи
     */
    public static void save(Database database, Path file) throws IOException {
        Objects.requireNonNull(database, "База данных не задана");
        Objects.requireNonNull(file, "Путь к файлу не задан");
        Path absoluteFile = file.toAbsolutePath();
        Path parent = absoluteFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = parent.resolve(absoluteFile.getFileName() + ".tmp");
        Path backupFile = backupPath(absoluteFile);
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(
                temporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))) {
            output.writeObject(database);
            output.flush();
        }
        if (Files.exists(absoluteFile)) {
            Files.copy(absoluteFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(
                    temporaryFile,
                    absoluteFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, absoluteFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path backupPath(Path file) {
        return file.getParent().resolve(file.getFileName() + ".bak");
    }
}
