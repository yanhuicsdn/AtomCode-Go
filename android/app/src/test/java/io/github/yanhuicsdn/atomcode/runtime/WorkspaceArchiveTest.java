package io.github.yanhuicsdn.atomcode.runtime;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class WorkspaceArchiveTest {
    private byte[] zip(String name, byte[] data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry();
        }
        return output.toByteArray();
    }

    @Test public void maliciousImportCannotOverwriteExistingWorkspace() throws Exception {
        Path root = Files.createTempDirectory("atomcode-import-test");
        try {
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Files.write(workspace.resolve("keep"), new byte[]{42});
            assertThrows(IOException.class, () -> WorkspaceArchive.importProject(
                    new ByteArrayInputStream(zip("../workspace/keep", new byte[]{0})), workspace));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(workspace.resolve("keep")));
            try (java.util.stream.Stream<Path> files = Files.list(root)) { assertEquals(1, files.count()); }
        } finally { RuntimeFiles.deleteTree(root); }
    }

    @Test public void exportImportPreservesBytesAndCreatesANewProjectEachTime() throws Exception {
        Path root = Files.createTempDirectory("atomcode-roundtrip-test");
        try {
            Path source = Files.createDirectory(root.resolve("source"));
            Files.createDirectories(source.resolve("src"));
            byte[] content = "中文代码\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(source.resolve("src/main.rs"), content);
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            WorkspaceArchive.exportWorkspace(source, archive);
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Path first = WorkspaceArchive.importProject(new ByteArrayInputStream(archive.toByteArray()), workspace);
            Path second = WorkspaceArchive.importProject(new ByteArrayInputStream(archive.toByteArray()), workspace);
            assertNotEquals(first, second);
            assertArrayEquals(content, Files.readAllBytes(first.resolve("src/main.rs")));
            assertArrayEquals(content, Files.readAllBytes(second.resolve("src/main.rs")));
        } finally { RuntimeFiles.deleteTree(root); }
    }

    @Test public void exportRejectsLinksInsteadOfReadingOutsideTheWorkspace() throws Exception {
        Path root = Files.createTempDirectory("atomcode-export-test");
        try {
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Files.write(root.resolve("secret"), new byte[]{99});
            Files.createSymbolicLink(workspace.resolve("link"), root.resolve("secret"));
            assertThrows(IOException.class, () -> WorkspaceArchive.exportWorkspace(workspace, new ByteArrayOutputStream()));
        } finally { RuntimeFiles.deleteTree(root); }
    }
}
