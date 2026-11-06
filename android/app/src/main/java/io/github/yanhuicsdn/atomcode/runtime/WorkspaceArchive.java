package io.github.yanhuicsdn.atomcode.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Explicit workspace transfer, always into a new project with no overwrite. */
public final class WorkspaceArchive {
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100000;
    private WorkspaceArchive() {}

    public static Path importProject(InputStream input, Path workspace) throws IOException {
        Files.createDirectories(workspace);
        if (Files.isSymbolicLink(workspace)) throw new IOException("工作区根目录不能是符号链接");
        String identifier = UUID.randomUUID().toString().replace("-", "");
        Path stage = workspace.getParent().resolve(".import-" + identifier);
        Files.createDirectory(stage);
        long expanded = 0;
        Set<String> names = new HashSet<>();
        boolean committed = false;
        try {
            try (ZipInputStream archive = new ZipInputStream(input)) {
                ZipEntry entry;
                byte[] buffer = new byte[65536];
                while ((entry = archive.getNextEntry()) != null) {
                    if (!names.add(entry.getName()) || names.size() > MAX_ENTRIES)
                        throw new IOException("压缩包包含重复路径或文件数过多");
                    Path destination = RuntimeFiles.child(stage, entry.getName());
                    if (entry.isDirectory()) Files.createDirectories(destination);
                    else {
                        Files.createDirectories(destination.getParent());
                        try (OutputStream output = Files.newOutputStream(destination)) {
                            int read;
                            while ((read = archive.read(buffer)) != -1) {
                                if (read > MAX_BYTES - expanded) throw new IOException("工作区解压大小超过 2 GB 上限");
                                expanded += read;
                                output.write(buffer, 0, read);
                            }
                        }
                        // Files are never installed as links. Restore only the
                        // owner execute bit from our optional private ZIP extra.
                        if (executable(entry.getExtra())) {
                            Set<PosixFilePermission> mode = new HashSet<>(Files.getPosixFilePermissions(destination));
                            mode.add(PosixFilePermission.OWNER_EXECUTE);
                            Files.setPosixFilePermissions(destination, mode);
                        }
                    }
                    archive.closeEntry();
                }
            }
            if (names.isEmpty()) throw new IOException("压缩包为空或不是可读取的 ZIP 文件");
            Path project = workspace.resolve("imported-" + identifier);
            if (Files.exists(project, LinkOption.NOFOLLOW_LINKS)) throw new IOException("导入目标已经存在，未覆盖文件");
            RuntimeFiles.atomicMove(stage, project);
            committed = true;
            return project;
        } finally {
            if (!committed) RuntimeFiles.deleteTree(stage);
        }
    }

    public static void exportWorkspace(Path workspace, OutputStream destination) throws IOException {
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) throw new IOException("工作区不存在");
        try (ZipOutputStream archive = new ZipOutputStream(destination)) {
            long[] bytes = {0};
            int[] count = {0};
            Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
                private String relative(Path path) { return workspace.relativize(path).toString().replace('\\', '/'); }
                private void add(ZipEntry entry) throws IOException {
                    if (++count[0] > MAX_ENTRIES) throw new IOException("工作区文件数超过导出上限");
                    entry.setTime(0);
                    archive.putNextEntry(entry);
                }
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (!directory.equals(workspace)) { add(new ZipEntry(relative(directory) + "/")); archive.closeEntry(); }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(path))
                        throw new IOException("工作区包含符号链接或特殊文件，未导出：" + relative(path));
                    ZipEntry entry = new ZipEntry(relative(path));
                    if (Files.isExecutable(path)) entry.setExtra(new byte[]{(byte)0xc0, (byte)0xa7, 1, 0, 1});
                    add(entry);
                    try (InputStream source = Files.newInputStream(path)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = source.read(buffer)) != -1) {
                            if (read > MAX_BYTES - bytes[0]) throw new IOException("工作区超过 2 GB 导出上限");
                            bytes[0] += read;
                            archive.write(buffer, 0, read);
                        }
                    }
                    archive.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static boolean executable(byte[] extra) {
        if (extra == null) return false;
        for (int i = 0; i + 4 <= extra.length;) {
            int id = (extra[i] & 255) | ((extra[i + 1] & 255) << 8);
            int length = (extra[i + 2] & 255) | ((extra[i + 3] & 255) << 8);
            if (length > extra.length - i - 4) return false;
            if (id == 0xa7c0 && length == 1) return extra[i + 4] == 1;
            i += 4 + length;
        }
        return false;
    }
}
