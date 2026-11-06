package io.github.yanhuicsdn.atomcode.runtime;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class RuntimeFilesTest {
    @Test public void archiveCannotTraverseOrWriteThroughSymlink() throws Exception {
        Path temporary = Files.createTempDirectory("atomcode-path-test");
        try {
            Path root = Files.createDirectory(temporary.resolve("root"));
            for (String path : new String[]{"../escape", "a/../../escape", "/absolute", "a//b", "a\\b", "a/./b"}) {
                assertThrows(IOException.class, () -> RuntimeFiles.child(root, path));
            }
            Path outside = Files.createDirectory(temporary.resolve("outside"));
            Files.createSymbolicLink(root.resolve("link"), outside);
            assertThrows(IOException.class, () -> RuntimeFiles.child(root, "link/overwrite"));
            assertThrows(IOException.class, () -> RuntimeFiles.child(root.resolve("link"), "overwrite"));
            assertEquals(root.resolve("src/main.rs"), RuntimeFiles.child(root, "src/main.rs"));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }

    @Test public void cleanupDoesNotFollowLinksIntoUserData() throws Exception {
        Path temporary = Files.createTempDirectory("atomcode-delete-test");
        try {
            Path data = Files.createDirectory(temporary.resolve("home"));
            Files.write(data.resolve("important"), "user data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path stage = Files.createDirectory(temporary.resolve("stage"));
            Files.createSymbolicLink(stage.resolve("root"), data);
            RuntimeFiles.deleteTree(stage);
            assertEquals("user data", new String(Files.readAllBytes(data.resolve("important")), java.nio.charset.StandardCharsets.UTF_8));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }
}
