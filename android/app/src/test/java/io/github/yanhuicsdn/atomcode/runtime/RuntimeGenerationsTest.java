package io.github.yanhuicsdn.atomcode.runtime;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class RuntimeGenerationsTest {
    private String name(char id) {
        char[] suffix = new char[32]; java.util.Arrays.fill(suffix, id);
        return "atomcode-0123456789abcdef-" + new String(suffix);
    }
    private Path completed(Path base, char id) throws Exception {
        Path root = Files.createDirectory(base.resolve(name(id)));
        Files.write(root.resolve(".ready"), "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        Files.write(root.resolve("file"), new byte[]{42});
        return root;
    }

    @Test public void keepsActiveAndPreviousCompletedPointerAndNeverDeletesUnknownOrLinkedEntries() throws Exception {
        Path temporary = Files.createTempDirectory("atomcode-generation-test");
        try {
            Path base = Files.createDirectory(temporary.resolve("runtime"));
            Path a = completed(base, 'a'), b = completed(base, 'b'), c = completed(base, 'c'), d = completed(base, 'd');
            Path home = Files.createDirectory(temporary.resolve("home"));
            Files.write(home.resolve("keep"), new byte[]{99});
            Path linked = Files.createSymbolicLink(base.resolve(name('e')), home);
            Path unknown = Files.createDirectory(base.resolve("user-project"));
            Path malformed = completed(base, 'f'); Files.write(malformed.resolve(".ready"), new byte[]{0});
            RuntimeGenerations.pruneCommitted(base, name('d'), name('d') + "\n" + name('c') + "\n");
            assertFalse(Files.exists(a)); assertFalse(Files.exists(b));
            assertTrue(Files.isDirectory(c)); assertTrue(Files.isDirectory(d));
            assertTrue(Files.isSymbolicLink(linked)); assertTrue(Files.isDirectory(unknown)); assertTrue(Files.isDirectory(malformed));
            assertArrayEquals(new byte[]{99}, Files.readAllBytes(home.resolve("keep")));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }

    @Test public void uncommittedJournalCannotPruneButCommittedJournalCanRetryAfterAKill() throws Exception {
        Path temporary = Files.createTempDirectory("atomcode-generation-commit-test");
        try {
            Path base = Files.createDirectory(temporary.resolve("runtime"));
            Path a = completed(base, 'a'), b = completed(base, 'b'), c = completed(base, 'c');
            String journal = name('c') + "\n" + name('b') + "\n";
            assertFalse(RuntimeGenerations.pruneCommitted(base, name('b'), journal));
            assertTrue(Files.exists(a)); assertTrue(Files.exists(b)); assertTrue(Files.exists(c));
            assertTrue(RuntimeGenerations.pruneCommitted(base, name('c'), journal));
            assertFalse(Files.exists(a)); assertTrue(Files.exists(b)); assertTrue(Files.exists(c));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }

    @Test public void malformedRecordOrInvalidActiveGenerationCannotAuthorizeDeletion() throws Exception {
        Path temporary = Files.createTempDirectory("atomcode-generation-invalid-test");
        try {
            Path base = Files.createDirectory(temporary.resolve("runtime"));
            Path a = completed(base, 'a'), b = completed(base, 'b');
            assertThrows(IOException.class, () -> RuntimeGenerations.pruneCommitted(base, name('b'), name('b') + "\n../home\n"));
            Files.write(b.resolve(".ready"), new byte[]{0});
            assertThrows(IOException.class, () -> RuntimeGenerations.pruneCommitted(base, name('b'), name('b') + "\n\n"));
            assertTrue(Files.exists(a)); assertTrue(Files.exists(b));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }

    @Test public void symlinkRuntimeBaseIsRejectedBeforeAnyWalk() throws Exception {
        Path temporary = Files.createTempDirectory("atomcode-generation-base-test");
        try {
            Path outside = Files.createDirectory(temporary.resolve("home"));
            Path a = completed(outside, 'a'), b = completed(outside, 'b');
            Path base = Files.createSymbolicLink(temporary.resolve("runtime"), outside);
            assertThrows(IOException.class, () -> RuntimeGenerations.pruneCommitted(base, name('b'), name('b') + "\n\n"));
            assertTrue(Files.exists(a)); assertTrue(Files.exists(b));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }
}
