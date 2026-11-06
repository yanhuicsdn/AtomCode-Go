package io.github.yanhuicsdn.atomcode.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Conservative post-commit reclamation of installer-owned completed directories. */
final class RuntimeGenerations {
    private static final Pattern NAME = Pattern.compile("atomcode-[a-f0-9]{16}-[a-f0-9]{32}");
    private RuntimeGenerations() {}

    static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }

    static boolean completed(Path base, String name) throws IOException {
        if (!validName(name)) return false;
        Path root = base.resolve(name);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
        Path marker = root.resolve(".ready");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 64) return false;
        try (InputStream input = Files.newInputStream(marker)) {
            byte[] bytes = new byte[65];
            int length = 0, read;
            while (length < bytes.length && (read = input.read(bytes, length, bytes.length - length)) != -1) length += read;
            return length == 64 && new String(bytes, 0, length, StandardCharsets.US_ASCII).matches("[a-f0-9]{64}");
        }
    }

    static void prune(Path base, String active, String previous) throws IOException {
        if (Files.isSymbolicLink(base) || !completed(base, active))
            throw new IOException("当前运行环境无效，未清理历史环境");
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(base)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals(active) || name.equals(previous)) continue;
                // Unknown entries, staging, malformed markers and all top-level
                // links remain untouched. walkFileTree never follows nested links.
                if (completed(base, name)) RuntimeFiles.deleteTree(entry);
            }
        }
    }

    static boolean pruneCommitted(Path base, String active, String record) throws IOException {
        String[] names = record.split("\n", -1);
        if (names.length != 3 || !names[2].isEmpty() || !validName(names[0])
                || (!names[1].isEmpty() && !validName(names[1])))
            throw new IOException("历史环境保留记录无效");
        if (!names[0].equals(active)) return false;
        prune(base, active, names[1].isEmpty() ? null : names[1]);
        return true;
    }
}
