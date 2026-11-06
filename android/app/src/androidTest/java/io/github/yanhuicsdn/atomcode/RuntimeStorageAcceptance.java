package io.github.yanhuicsdn.atomcode;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeFiles;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeInstaller;
import io.github.yanhuicsdn.atomcode.runtime.WorkspaceArchive;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Device filesystem checks using actual bundled assets and an isolated cache home. */
final class RuntimeStorageAcceptance {
    private final LocalRuntimeSmoke runner;
    RuntimeStorageAcceptance(LocalRuntimeSmoke runner) { this.runner = runner; }
    private static final class Canceled extends RuntimeException {}

    private static Context isolated(Context target, Path root, boolean noSpace) {
        File files = noSpace ? new File(root.toString()) {
            @Override public long getUsableSpace() { return 0; }
        } : root.toFile();
        return new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
    }

    void run() {
        Bundle result = new Bundle();
        Path root = null;
        String step = "create isolated test home";
        boolean passed = false;
        try {
            Context target = runner.getTargetContext();
            root = Files.createTempDirectory(target.getCacheDir().toPath(), "storage-acceptance-");
            RuntimeInstaller installer = new RuntimeInstaller(isolated(target, root, false));
            Path workspace = Files.createDirectories(installer.home().resolve("workspace"));
            Path canary = workspace.resolve("keep.txt");
            byte[] keep = "existing workspace\n".getBytes(StandardCharsets.UTF_8);
            Files.write(canary, keep);
            step = "install bundled runtime";
            installer.install((message, percent) -> {});
            Path firstRoot = installer.rootfs();
            Path pointer = root.resolve("runtime/current");
            byte[] firstPointer = Files.readAllBytes(pointer);
            LocalRuntimeSmoke.require(installer.isCurrent(), "installed generation must validate");
            runner.pass("Actual bundled runtime installs and validates in isolated Android storage");

            step = "cancel repair before commit";
            boolean canceled = false;
            try { installer.install((message, percent) -> { if (percent >= 30 && percent < 100) throw new Canceled(); }); }
            catch (Canceled expected) { canceled = true; }
            LocalRuntimeSmoke.require(canceled, "repair must reach injected cancellation");
            LocalRuntimeSmoke.require(Arrays.equals(firstPointer, Files.readAllBytes(pointer)), "canceled repair must preserve active pointer");
            LocalRuntimeSmoke.require(installer.rootfs().equals(firstRoot), "old generation must remain valid");
            LocalRuntimeSmoke.require(!Files.exists(root.resolve("runtime/staging")), "canceled extraction must clean staging");
            LocalRuntimeSmoke.require(Arrays.equals(keep, Files.readAllBytes(canary)), "canceled repair must preserve user data");
            runner.pass("Canceled extraction preserves active generation and home, then cleans staging");

            step = "insufficient-space preflight";
            RuntimeInstaller lowSpace = new RuntimeInstaller(isolated(target, root, true));
            boolean denied = false;
            try { lowSpace.install((message, percent) -> {}); }
            catch (IOException expected) { denied = expected.getMessage() != null && expected.getMessage().contains("空间不足"); }
            LocalRuntimeSmoke.require(denied, "zero available-space preflight must fail visibly");
            LocalRuntimeSmoke.require(Arrays.equals(firstPointer, Files.readAllBytes(pointer)), "space failure must preserve active pointer");
            LocalRuntimeSmoke.require(Arrays.equals(keep, Files.readAllBytes(canary)), "space failure must preserve user data");
            runner.pass("Injected zero-usable-space preflight rejects installation without changing existing data");

            step = "detect and repair installed daemon corruption";
            Files.write(firstRoot.resolve("usr/local/bin/atomcode-daemon"), new byte[]{0});
            LocalRuntimeSmoke.require(!installer.isCurrent(), "corrupt installed daemon must not validate");
            installer.install((message, percent) -> {});
            LocalRuntimeSmoke.require(installer.isCurrent() && !installer.rootfs().equals(firstRoot), "repair must commit a fresh verified generation");
            LocalRuntimeSmoke.require(Arrays.equals(keep, Files.readAllBytes(canary)), "repair must preserve home");
            runner.pass("Installed daemon corruption is detected and repaired into a fresh generation preserving home");

            step = "workspace transfer roundtrip";
            Path script = workspace.resolve("run.sh");
            byte[] content = "#!/bin/sh\nprintf '中文\\n'\n".getBytes(StandardCharsets.UTF_8);
            Files.write(script, content);
            Set<PosixFilePermission> mode = new HashSet<>(Files.getPosixFilePermissions(script));
            mode.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(script, mode);
            Files.createDirectory(workspace.resolve("empty"));
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            WorkspaceArchive.exportWorkspace(workspace, archive);
            byte[] zipped = archive.toByteArray();
            Path imports = root.resolve("imports");
            Path first = WorkspaceArchive.importProject(new ByteArrayInputStream(zipped), imports);
            Path second = WorkspaceArchive.importProject(new ByteArrayInputStream(zipped), imports);
            LocalRuntimeSmoke.require(!first.equals(second), "repeated import must create different projects");
            LocalRuntimeSmoke.require(Arrays.equals(content, Files.readAllBytes(first.resolve("run.sh"))) && Files.isExecutable(first.resolve("run.sh")), "transfer must preserve bytes and owner execute bit");
            LocalRuntimeSmoke.require(Files.isDirectory(first.resolve("empty")), "transfer must preserve empty directories");
            runner.pass("Android workspace ZIP roundtrip preserves UTF-8 bytes, empty directories and execute bit without overwriting projects");

            step = "reject malformed imports";
            rejectedImport(Arrays.copyOf(zipped, zipped.length - 22), imports);
            ByteArrayOutputStream malicious = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(malicious)) {
                zip.putNextEntry(new ZipEntry("../keep.txt")); zip.write(0); zip.closeEntry();
            }
            rejectedImport(malicious.toByteArray(), imports);
            LocalRuntimeSmoke.require(Arrays.equals(keep, Files.readAllBytes(canary)), "malformed import must not overwrite existing data");
            try (java.util.stream.Stream<Path> entries = Files.list(root)) {
                LocalRuntimeSmoke.require(entries.noneMatch(p -> p.getFileName().toString().startsWith(".import-")), "failed import must clean staging");
            }
            runner.pass("Truncated and traversal ZIP imports are rejected on Android without residual staging or data loss");
            passed = true;
        } catch (Throwable failure) {
            runner.evidence.append("FAIL: ").append(step).append(" (").append(failure.getClass().getSimpleName()).append(")\n");
        } finally {
            if (root != null) try { RuntimeFiles.deleteTree(root); }
            catch (IOException failure) { passed = false; runner.evidence.append("FAIL: isolated test cleanup\n"); }
        }
        result.putString("stream", runner.evidence.toString());
        runner.finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void rejectedImport(byte[] bytes, Path workspace) throws IOException {
        boolean rejected = false;
        try { WorkspaceArchive.importProject(new ByteArrayInputStream(bytes), workspace); }
        catch (IOException expected) { rejected = true; }
        LocalRuntimeSmoke.require(rejected, "malformed ZIP must be rejected");
    }
}
