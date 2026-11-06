package io.github.yanhuicsdn.atomcode.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Filesystem policy shared by runtime installation and workspace import. */
public final class RuntimeFiles {
    private RuntimeFiles() {}

    public static Path child(Path root, String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("不安全的归档路径");
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : clean.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("归档路径包含无效目录");
        }
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new IOException("工作区根目录不能是符号链接");
        Path result = absolute.resolve(clean).normalize();
        if (!result.startsWith(absolute) || result.equals(absolute))
            throw new IOException("归档路径越界");
        // Never write through a pre-existing symlink, even if it stays in root.
        for (Path path = result; path != null && !path.equals(absolute); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("归档路径经过符号链接");
        }
        return result;
    }

    public static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
            StringBuilder result = new StringBuilder();
            for (byte value : hash.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        // walkFileTree does not follow links.
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void atomicMove(Path from, Path to) throws IOException {
        // The runtime and staging are deliberately on the same filesystem.
        // A non-atomic fallback could expose half an installation after a kill.
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("存储不支持安全的原子替换", unsupported);
        }
    }
}
