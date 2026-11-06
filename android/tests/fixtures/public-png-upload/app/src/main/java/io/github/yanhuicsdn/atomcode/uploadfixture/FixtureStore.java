package io.github.yanhuicsdn.atomcode.uploadfixture;

import android.content.Context;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Only these two exact expected canaries may be created, read or removed. */
final class FixtureStore {
    private static Path directory(Context context) throws IOException {
        return context.getFilesDir().getCanonicalFile().toPath().resolve("public-png-upload-v1");
    }
    private static void verifyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unexpected-fixture-root");
        try (java.util.stream.Stream<Path> children = Files.list(directory)) {
            if (children.anyMatch(path -> !Arrays.asList(Canaries.NAMES).contains(path.getFileName().toString())))
                throw new IOException("unknown-existing-fixture-file");
        }
    }
    private static void verify(Path file, int index) throws IOException {
        byte[] expected = Canaries.bytes(index);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != expected.length
                || !Arrays.equals(Files.readAllBytes(file), expected)) throw new IOException("unexpected-existing-canary");
    }
    static synchronized Path file(Context context, int index) throws IOException {
        if (index < 0 || index >= Canaries.NAMES.length) throw new IOException("unknown-canary-index");
        Path directory = directory(context);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        verifyDirectory(directory);
        // Verify every existing canary before creating any missing one.
        for (int i = 0; i < Canaries.NAMES.length; i++) {
            Path file = directory.resolve(Canaries.NAMES[i]);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) verify(file, i);
        }
        for (int i = 0; i < Canaries.NAMES.length; i++) {
            Path file = directory.resolve(Canaries.NAMES[i]);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                Files.write(file, Canaries.bytes(i), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        Path result = directory.resolve(Canaries.NAMES[index]);
        verify(result, index);
        return result;
    }
    static synchronized void clean(Context context) throws IOException {
        Path directory = directory(context);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        verifyDirectory(directory);
        for (int i = 0; i < Canaries.NAMES.length; i++) {
            Path file = directory.resolve(Canaries.NAMES[i]);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) verify(file, i);
        }
        for (String name : Canaries.NAMES) Files.deleteIfExists(directory.resolve(name));
        Files.delete(directory);
    }
    private FixtureStore() {}
}
