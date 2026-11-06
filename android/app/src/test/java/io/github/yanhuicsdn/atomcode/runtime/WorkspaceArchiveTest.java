package io.github.yanhuicsdn.atomcode.runtime;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.Arrays;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
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

    private int signature(byte[] archive, int value, int start) {
        for (int i = start; i <= archive.length - 4; i++) {
            int actual = (archive[i] & 255) | ((archive[i + 1] & 255) << 8)
                    | ((archive[i + 2] & 255) << 16) | ((archive[i + 3] & 255) << 24);
            if (actual == value) return i;
        }
        throw new AssertionError("ZIP fixture signature not found");
    }

    private void assertRejectedWithoutChangingWorkspace(byte[] archive) throws Exception {
        Path root = Files.createTempDirectory("atomcode-corrupt-import-test");
        try {
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Files.write(workspace.resolve("keep"), new byte[]{42});
            assertThrows(IOException.class, () -> WorkspaceArchive.importProject(new ByteArrayInputStream(archive), workspace));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(workspace.resolve("keep")));
            try (java.util.stream.Stream<Path> files = Files.list(workspace)) { assertEquals(1, files.count()); }
            try (java.util.stream.Stream<Path> files = Files.list(root)) { assertEquals(1, files.count()); }
        } finally { RuntimeFiles.deleteTree(root); }
    }

    @Test public void truncatedSecondLocalHeaderDoesNotImportACompletePrefix() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            for (String name : new String[]{"first.txt", "second.txt"}) {
                archive.putNextEntry(new ZipEntry(name));
                archive.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        byte[] full = output.toByteArray();
        int second = signature(full, 0x04034b50, 4);
        assertRejectedWithoutChangingWorkspace(Arrays.copyOf(full, second + 10));
    }

    @Test public void missingCentralDirectoryDoesNotCommitAnApparentlyCompleteImport() throws Exception {
        byte[] full = zip("complete.txt", new byte[]{1, 2, 3});
        int central = signature(full, 0x02014b50, 0);
        assertRejectedWithoutChangingWorkspace(Arrays.copyOf(full, central));
    }

    @Test public void crcCorruptionDoesNotCommitModifiedFileBytes() throws Exception {
        byte[] data = "valid stored content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CRC32 checksum = new CRC32();
        checksum.update(data);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            ZipEntry entry = new ZipEntry("stored.txt");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(checksum.getValue());
            archive.putNextEntry(entry);
            archive.write(data);
            archive.closeEntry();
        }
        byte[] corrupt = output.toByteArray();
        int nameLength = (corrupt[26] & 255) | ((corrupt[27] & 255) << 8);
        int extraLength = (corrupt[28] & 255) | ((corrupt[29] & 255) << 8);
        corrupt[30 + nameLength + extraLength] ^= 1;
        assertRejectedWithoutChangingWorkspace(corrupt);
    }

    @Test public void exportImportPreservesBytesAndCreatesANewProjectEachTime() throws Exception {
        Path root = Files.createTempDirectory("atomcode-roundtrip-test");
        try {
            Path source = Files.createDirectory(root.resolve("source"));
            Files.createDirectories(source.resolve("src"));
            byte[] content = "中文代码\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(source.resolve("src/main.rs"), content);
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(source.resolve("src/main.rs")));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(source.resolve("src/main.rs"), permissions);
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            WorkspaceArchive.exportWorkspace(source, archive);
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Path first = WorkspaceArchive.importProject(new ByteArrayInputStream(archive.toByteArray()), workspace);
            Path second = WorkspaceArchive.importProject(new ByteArrayInputStream(archive.toByteArray()), workspace);
            assertNotEquals(first, second);
            assertArrayEquals(content, Files.readAllBytes(first.resolve("src/main.rs")));
            assertArrayEquals(content, Files.readAllBytes(second.resolve("src/main.rs")));
            assertTrue(Files.getPosixFilePermissions(first.resolve("src/main.rs")).contains(PosixFilePermission.OWNER_EXECUTE));
            assertTrue(Files.getPosixFilePermissions(second.resolve("src/main.rs")).contains(PosixFilePermission.OWNER_EXECUTE));
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
