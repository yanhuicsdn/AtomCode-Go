package io.github.yanhuicsdn.atomcode.runtime;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Immutable rootfs generations; user home is never inside an extraction tree. */
public final class RuntimeInstaller {
    public interface Progress { void update(String phase, int percent); }
    private final Context context;
    public final RuntimeManifest manifest;
    private final Path base;
    private final AtomicFile current;

    public RuntimeInstaller(Context context) throws IOException {
        this.context = context.getApplicationContext();
        manifest = RuntimeManifest.load(context);
        base = context.getFilesDir().toPath().resolve("runtime");
        current = new AtomicFile(base.resolve("current").toFile());
    }

    public Path home() { return context.getFilesDir().toPath().resolve("home"); }

    public Path rootfs() throws IOException {
        try {
            String active = new String(current.readFully(), StandardCharsets.UTF_8);
            if (!active.matches(java.util.regex.Pattern.quote(manifest.version) + "-[a-f0-9]{32}"))
                throw new IOException("需要安装或更新运行环境");
            Path root = base.resolve(active);
            String marker = new String(Files.readAllBytes(root.resolve(".ready")), StandardCharsets.UTF_8);
            if (!marker.equals(manifest.sha256)) throw new IOException("运行环境安装不完整");
            Path daemon = RuntimeFiles.child(root, "usr/local/bin/atomcode-daemon");
            try (InputStream input = Files.newInputStream(daemon)) {
                String expected = manifest.json.getJSONObject("build").getString("daemonSha256");
                if (!RuntimeFiles.sha256(input).equals(expected)) throw new IOException("引擎文件损坏，请重新安装运行环境");
            }
            return root;
        } catch (JSONException error) {
            throw new IOException("引擎清单损坏", error);
        }
    }

    public boolean isCurrent() {
        try { rootfs(); return true; } catch (IOException error) { return false; }
    }

    public long requiredBytes() { return manifest.expandedBytes + manifest.expandedBytes / 5 + 64L * 1024 * 1024; }

    public void verifyNativeLibraries() throws IOException {
        try {
            JSONObject libraries = manifest.json.getJSONObject("nativeLibraries");
            for (String name : new String[]{"libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"}) {
                Path file = new File(context.getApplicationInfo().nativeLibraryDir, name).toPath();
                try (InputStream input = Files.newInputStream(file)) {
                    if (!RuntimeFiles.sha256(input).equals(libraries.getJSONObject(name).getString("sha256")))
                        throw new IOException("原生运行组件损坏：" + name);
                }
            }
        } catch (JSONException error) { throw new IOException("原生组件清单无效", error); }
    }

    public void install(Progress progress) throws IOException {
        Files.createDirectories(base);
        verifyNativeLibraries();
        if (context.getFilesDir().getUsableSpace() < requiredBytes())
            throw new IOException("空间不足：本次安装至少需要 " + requiredBytes() / (1024 * 1024) + " MB 可用空间");
        progress.update("校验运行环境", 0);
        try (InputStream input = context.getAssets().open("runtime/rootfs.zip")) {
            if (!RuntimeFiles.sha256(input).equals(manifest.sha256))
                throw new IOException("运行环境 SHA-256 校验失败");
        }
        Path stage = base.resolve("staging");
        RuntimeFiles.deleteTree(stage);
        Files.createDirectory(stage);
        try {
            extract(stage, progress);
            // fsync file data alone does not persist the directory entries.
            // Commit nested directories and symlinks before the ready marker.
            Files.walkFileTree(stage, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    syncDirectory(directory);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            writeSynced(stage.resolve(".ready"), manifest.sha256.getBytes(StandardCharsets.UTF_8));
            syncDirectory(stage);
            String generation = manifest.version + "-" + java.util.UUID.randomUUID().toString().replace("-", "");
            Path destination = base.resolve(generation);
            // Every install gets a new immutable directory. Until the pointer
            // commits, the old generation remains usable, including on a
            // same-version repair or interrupted upgrade.
            RuntimeFiles.atomicMove(stage, destination);
            syncDirectory(base);
            FileOutputStream pointer = current.startWrite();
            try {
                pointer.write(generation.getBytes(StandardCharsets.UTF_8));
                current.finishWrite(pointer);
            } catch (IOException error) {
                current.failWrite(pointer);
                throw error;
            }
            syncDirectory(base);
            Files.createDirectories(home().resolve("workspace"));
            progress.update("安装完成", 100);
            // Keep previous and older generations until another successful
            // install. Disk reclamation never runs against the user's home.
        } catch (IOException | RuntimeException error) {
            RuntimeFiles.deleteTree(stage);
            throw error;
        }
    }

    private void extract(Path stage, Progress progress) throws IOException {
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[65536];
        long expanded = 0;
        int files = 0;
        JSONObject metadata = null;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                context.getAssets().open("runtime/rootfs.zip"), 131072))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!seen.add(name)) throw new IOException("运行环境包含重复文件");
                if (seen.size() > manifest.files + 50000) throw new IOException("运行环境文件数超过上限");
                Path target = RuntimeFiles.child(stage, name);
                if (name.equals(".atomcode-rootfs.json")) {
                    try { metadata = new JSONObject(RuntimeManifest.readText(zip, 16 * 1024 * 1024)); }
                    catch (JSONException error) { throw new IOException("运行环境元数据无效", error); }
                } else if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (FileOutputStream output = new FileOutputStream(target.toFile())) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            if (read > manifest.expandedBytes - expanded) throw new IOException("运行环境解压大小超过清单");
                            expanded += read;
                            output.write(buffer, 0, read);
                            progress.update("安装 Linux 和 AtomCode", 20 + (int)(expanded * 75 / manifest.expandedBytes));
                        }
                        output.getFD().sync();
                    }
                    files++;
                }
                zip.closeEntry();
            }
        }
        if (metadata == null || files != manifest.files || expanded != manifest.expandedBytes)
            throw new IOException("运行环境文件缺失或大小不符");
        try {
            if (metadata.getInt("schema") != 1) throw new IOException("不支持的运行环境元数据");
            JSONObject executables = metadata.getJSONObject("executables");
            for (Iterator<String> it = executables.keys(); it.hasNext();) {
                String name = it.next();
                Path target = RuntimeFiles.child(stage, name);
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("可执行文件缺失");
                try { Os.chmod(target.toString(), executables.getInt(name) & 0777); }
                catch (android.system.ErrnoException error) { throw new IOException("设置执行权限失败", error); }
            }
            JSONObject links = metadata.getJSONObject("links");
            for (Iterator<String> it = links.keys(); it.hasNext();) {
                String name = it.next();
                Path target = RuntimeFiles.child(stage, name);
                String value = links.getString(name);
                Path relative = java.nio.file.Paths.get(value);
                if (relative.isAbsolute() || !target.getParent().resolve(relative).normalize().startsWith(stage))
                    throw new IOException("符号链接越界");
                Files.createDirectories(target.getParent());
                Files.createSymbolicLink(target, relative);
            }
            // Also check link chains after every link exists.
            for (Iterator<String> it = links.keys(); it.hasNext();) {
                File link = stage.resolve(it.next()).toFile();
                if (!link.getCanonicalFile().toPath().startsWith(stage)) throw new IOException("符号链接链越界");
            }
        } catch (JSONException error) { throw new IOException("运行环境链接或权限清单无效", error); }
    }

    private static void writeSynced(Path path, byte[] data) throws IOException {
        try (FileOutputStream output = new FileOutputStream(path.toFile())) {
            output.write(data);
            output.getFD().sync();
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.toString(), OsConstants.O_RDONLY, 0);
            Os.fsync(descriptor);
        } catch (android.system.ErrnoException error) { throw new IOException("同步安装状态失败", error); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (android.system.ErrnoException ignored) {} }
    }
}
