package io.github.yanhuicsdn.atomcode.runtime;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class RuntimeIntegrityTest {
    private static final class Fixture {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final byte[] archive;
        final String hash;
        Fixture(String omit) throws Exception {
            String salt = UUID.randomUUID().toString();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (String name : RuntimeIntegrity.BOOT_FILES) {
                    if (name.equals(omit)) continue;
                    byte[] content = (name + "/" + salt).getBytes(StandardCharsets.UTF_8);
                    files.put(name, content);
                    zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry();
                }
            }
            archive = bytes.toByteArray();
            hash = RuntimeFiles.sha256(new ByteArrayInputStream(archive));
        }
        Map<String, String> expected() throws Exception {
            return RuntimeIntegrity.sourceDigests(hash, () -> new ByteArrayInputStream(archive));
        }
        Path install(Path root) throws Exception {
            Files.createDirectories(root);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Path target = root.resolve(file.getKey());
                Files.createDirectories(target.getParent()); Files.write(target, file.getValue());
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            Files.createSymbolicLink(root.resolve("bin"), java.nio.file.Paths.get("usr/bin"));
            Files.createSymbolicLink(root.resolve("lib"), java.nio.file.Paths.get("usr/lib"));
            Files.createSymbolicLink(root.resolve("usr/lib/ld-linux-aarch64.so.1"), java.nio.file.Paths.get("aarch64-linux-gnu/ld-linux-aarch64.so.1"));
            Files.createSymbolicLink(root.resolve("usr/lib/aarch64-linux-gnu/libtinfo.so.6"), java.nio.file.Paths.get("libtinfo.so.6.4"));
            return root;
        }
    }

    @Test public void criticalFilesAreCheckedAgainstTheVerifiedSourceNotExtractedState() throws Exception {
        Fixture fixture = new Fixture(null);
        Path temporary = Files.createTempDirectory("atomcode-integrity-test");
        try {
            Path root = fixture.install(temporary.resolve("root"));
            Map<String, String> expected = fixture.expected();
            RuntimeIntegrity.validate(root, expected);
            for (Map.Entry<String, byte[]> file : fixture.files.entrySet()) {
                Files.write(root.resolve(file.getKey()), new byte[]{0});
                assertThrows(IOException.class, () -> RuntimeIntegrity.validate(root, expected));
                Files.write(root.resolve(file.getKey()), file.getValue());
                RuntimeIntegrity.validate(root, expected);
            }
        } finally { RuntimeFiles.deleteTree(temporary); }
    }

    @Test public void executablePermissionAndLinuxAliasDamageAreRejected() throws Exception {
        Fixture fixture = new Fixture(null);
        Path temporary = Files.createTempDirectory("atomcode-integrity-mode-test");
        try {
            Path root = fixture.install(temporary.resolve("root"));
            Map<String, String> expected = fixture.expected();
            Files.setPosixFilePermissions(root.resolve("usr/bin/env"), PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IOException.class, () -> RuntimeIntegrity.validate(root, expected));
            Files.setPosixFilePermissions(root.resolve("usr/bin/env"), PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.delete(root.resolve("lib"));
            Files.createSymbolicLink(root.resolve("lib"), temporary);
            assertThrows(IOException.class, () -> RuntimeIntegrity.validate(root, expected));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }

    @Test public void sourceHashAndRequiredFilesMustValidateBeforeCaching() throws Exception {
        Fixture fixture = new Fixture(null);
        assertThrows(IOException.class, () -> RuntimeIntegrity.sourceDigests(
                "0000000000000000000000000000000000000000000000000000000000000000", () -> new ByteArrayInputStream(fixture.archive)));
        Fixture missing = new Fixture("usr/bin/env");
        assertThrows(IOException.class, missing::expected);
    }

    @Test public void sourceCacheAvoidsRepeatedUnpackingButDoesNotCacheInstalledFileValidity() throws Exception {
        Fixture fixture = new Fixture(null);
        AtomicInteger opened = new AtomicInteger();
        RuntimeIntegrity.Source source = () -> { opened.incrementAndGet(); return new ByteArrayInputStream(fixture.archive); };
        Map<String, String> first = RuntimeIntegrity.sourceDigests(fixture.hash, source);
        Map<String, String> second = RuntimeIntegrity.sourceDigests(fixture.hash, source);
        assertSame(first, second);
        assertEquals(2, opened.get()); // one full hash pass and one ZIP pass
        Path temporary = Files.createTempDirectory("atomcode-integrity-cache-test");
        try {
            Path root = fixture.install(temporary.resolve("root"));
            RuntimeIntegrity.validate(root, first);
            Files.delete(root.resolve("usr/bin/bash"));
            Files.createSymbolicLink(root.resolve("usr/bin/bash"), temporary.resolve("outside"));
            assertThrows(IOException.class, () -> RuntimeIntegrity.validate(root, second));
        } finally { RuntimeFiles.deleteTree(temporary); }
    }
}
