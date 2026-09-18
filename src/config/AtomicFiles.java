package config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFiles {
    @FunctionalInterface
    interface Writer {
        void write(Path temporary) throws IOException;
    }

    private AtomicFiles() {}

    static void write(Path target, Writer writer) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("File has no parent directory: " + target);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        try {
            writer.write(temporary);
            // Force the temp file's bytes to storage before the rename so a
            // crash cannot persist the name without its contents.
            try (var channel = java.nio.channels.FileChannel.open(
                    temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(false);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // Best effort: persist the directory entry too.
            try (var dirChannel = java.nio.channels.FileChannel.open(parent)) {
                dirChannel.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
