package io.github.yanhuicsdn.atomcode.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Startup integrity of the pinned ARM64 userspace's boot/terminal dependencies. */
final class RuntimeIntegrity {
    interface Source { InputStream open() throws IOException; }
    static final Set<String> BOOT_FILES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "usr/bin/env", "usr/bin/bash", "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/libc.so.6", "usr/lib/aarch64-linux-gnu/libtinfo.so.6.4")));
    private static final Set<String> EXECUTABLES = new LinkedHashSet<>(Arrays.asList(
            "usr/bin/env", "usr/bin/bash", "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"));
    private static final Map<String, String> BOOT_LINKS;
    private static String cachedSourceHash;
    private static Map<String, String> cachedDigests;
    static {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("bin", "usr/bin");
        links.put("lib", "usr/lib");
        links.put("usr/lib/ld-linux-aarch64.so.1", "aarch64-linux-gnu/ld-linux-aarch64.so.1");
        links.put("usr/lib/aarch64-linux-gnu/libtinfo.so.6", "libtinfo.so.6.4");
        BOOT_LINKS = Collections.unmodifiableMap(links);
    }
    private RuntimeIntegrity() {}

    static synchronized Map<String, String> sourceDigests(String expectedHash, Source source) throws IOException {
        if (expectedHash.equals(cachedSourceHash)) return cachedDigests;
        // The cache is keyed by the immutable APK asset's verified full digest,
        // never by mutable extracted files or an unsigned on-disk hash index.
        try (InputStream input = source.open()) {
            if (!RuntimeFiles.sha256(input).equals(expectedHash)) throw new IOException("运行环境 SHA-256 校验失败");
        }
        Map<String, String> digests = new LinkedHashMap<>();
        try (ZipInputStream archive = new ZipInputStream(source.open())) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (BOOT_FILES.contains(entry.getName())) {
                    if (entry.isDirectory() || digests.containsKey(entry.getName()))
                        throw new IOException("运行环境启动文件无效");
                    digests.put(entry.getName(), RuntimeFiles.sha256(archive));
                }
                archive.closeEntry();
            }
        }
        if (!digests.keySet().equals(BOOT_FILES)) throw new IOException("运行环境启动文件缺失");
        cachedSourceHash = expectedHash;
        cachedDigests = Collections.unmodifiableMap(digests);
        return cachedDigests;
    }

    static void validate(Path root, Map<String, String> digests) throws IOException {
        if (!digests.keySet().equals(BOOT_FILES)) throw new IOException("运行环境启动校验信息无效");
        for (Map.Entry<String, String> file : digests.entrySet()) {
            Path path = RuntimeFiles.child(root, file.getKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("运行环境启动文件缺失或类型错误");
            try (InputStream input = Files.newInputStream(path)) {
                if (!RuntimeFiles.sha256(input).equals(file.getValue())) throw new IOException("运行环境启动文件损坏，需要重新安装");
            }
            if (EXECUTABLES.contains(file.getKey()) && !Files.isExecutable(path))
                throw new IOException("运行环境启动文件执行权限损坏，需要重新安装");
        }
        for (Map.Entry<String, String> link : BOOT_LINKS.entrySet()) {
            // These are the intentionally installed Linux aliases. Content
            // validation above uses real usr/... paths, never traverses them.
            Path relative = java.nio.file.Paths.get(link.getKey());
            Path parent = relative.getParent() == null ? root : RuntimeFiles.child(root, relative.getParent().toString());
            Path path = parent.resolve(relative.getFileName());
            // child() deliberately rejects a link at its final component;
            // validate parents independently, then compare the link text.
            if (!Files.isSymbolicLink(path) || !Files.readSymbolicLink(path).toString().equals(link.getValue()))
                throw new IOException("运行环境启动链接损坏，需要重新安装");
        }
    }
}
