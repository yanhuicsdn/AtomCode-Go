package io.github.yanhuicsdn.atomcode;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.os.SystemClock;
import android.system.Os;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeFiles;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeInstaller;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Real filesystem pressure, exclusively in a host-confirmed owned emulator. */
final class RealSpaceAcceptance {
    static final String CONFIRMATION = "owned-emulator-fs-pressure-v1";
    static final String PREFIX = "real-space-acceptance-";
    private static final long MIB = 1024L * 1024;
    private static final long RESERVE = 256 * MIB;
    private static final long MAX_FS = 16L * 1024 * MIB;
    private static final long MAX_FILL = 6L * 1024 * MIB;
    private static final long MAX_FILL_MS = 120000;
    private static final long MAX_TOTAL_MS = 240000;
    private final LocalRuntimeSmoke host;
    private final String confirmation;
    private final String caseId;
    private static final class GuardedAbort extends RuntimeException {}

    RealSpaceAcceptance(LocalRuntimeSmoke host, String confirmation, String caseId) {
        this.host = host;
        this.confirmation = confirmation;
        this.caseId = caseId;
    }

    private static Context isolated(Context target, File files) {
        // An ordinary File: getUsableSpace is the actual filesystem syscall.
        return new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
    }

    private static void require(boolean condition) {
        if (!condition) throw new GuardedAbort();
    }

    private static String digest(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return RuntimeFiles.sha256(input); }
    }

    private static void withinDeadline(long started, File files) {
        require(SystemClock.elapsedRealtime() - started < MAX_TOTAL_MS && files.getUsableSpace() >= RESERVE);
    }

    void run() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "real available-space preflight; not physical NAND exhaustion or injected zero space");
        report.put("reserveBytes", RESERVE);
        report.put("maxFilesystemBytes", MAX_FS);
        report.put("maxFillerBytes", MAX_FILL);
        report.put("maxFillMilliseconds", MAX_FILL_MS);
        report.put("maxTotalMilliseconds", MAX_TOTAL_MS);
        report.put("credentialsRead", false);
        report.put("modelRequestsSent", false);
        Path fixture = null;
        Path filler = null;
        boolean created = false;
        boolean passed = false;
        String stage = "owned-emulator-guard";
        long started = SystemClock.elapsedRealtime();
        try {
            require(CONFIRMATION.equals(confirmation) && caseId != null && caseId.matches("[a-f0-9]{32}"));
            require("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
            Context target = host.getTargetContext();
            require("io.github.yanhuicsdn.atomcode".equals(target.getPackageName()));
            File realFiles = target.getFilesDir().getCanonicalFile();
            require(realFiles.getClass() == File.class && !Files.isSymbolicLink(realFiles.toPath()));
            fixture = realFiles.toPath().resolve(PREFIX + caseId);
            require(!Files.exists(fixture, LinkOption.NOFOLLOW_LINKS));
            Files.createDirectory(fixture);
            created = true;
            File files = fixture.toFile();
            require(files.getClass() == File.class && Os.stat(files.toString()).st_dev == Os.stat(realFiles.toString()).st_dev);
            report.put("hardware", Build.HARDWARE);
            report.put("fixtureRelativePath", "files/" + PREFIX + caseId);
            StatFs initial = new StatFs(files.toString());
            long total = initial.getTotalBytes();
            require(total > 0 && total <= MAX_FS);
            RuntimeInstaller installer = new RuntimeInstaller(isolated(target, files));
            long required = installer.requiredBytes();
            require(required > RESERVE + 32 * MIB && required < MAX_FS / 2);
            long before = files.getUsableSpace();
            report.put("filesystemTotalBytes", total);
            report.put("initialFileUsableBytes", before);
            report.put("initialStatFsAvailableBytes", initial.getAvailableBytes());
            report.put("requiredBytes", required);
            report.put("runtimeVersion", installer.manifest.version);
            require(before >= 2 * required + RESERVE + 32 * MIB);

            stage = "baseline-real-install";
            installer.install((message, percent) -> withinDeadline(started, files));
            require(installer.isCurrent());
            Path oldRoot = installer.rootfs();
            Path pointer = fixture.resolve("runtime/current");
            byte[] oldPointer = Files.readAllBytes(pointer);
            byte[] oldReady = Files.readAllBytes(oldRoot.resolve(".ready"));
            String oldDaemon = digest(oldRoot.resolve("usr/local/bin/atomcode-daemon"));
            Path canary = Files.createDirectories(installer.home().resolve("workspace")).resolve("keep.bin");
            byte[] canaryBytes = ("Real-space fixture canary " + caseId + "\n").getBytes(StandardCharsets.US_ASCII);
            Files.write(canary, canaryBytes);
            require(Arrays.equals(canaryBytes, Files.readAllBytes(canary)));
            host.pass("Bundled runtime installs and baseline pointer, ready marker and fixture home are recorded");

            stage = "bounded-real-filesystem-fill";
            long baselineAvailable = files.getUsableSpace();
            long targetAvailable = required - 16 * MIB;
            require(targetAvailable >= RESERVE + 16 * MIB && baselineAvailable > required + 32 * MIB);
            require(baselineAvailable - targetAvailable + MIB <= MAX_FILL);
            report.put("baselineFileUsableBytes", baselineAvailable);
            report.put("targetAvailableBytes", targetAvailable);
            filler = fixture.resolve("non-sparse-filler.bin");
            require(!Files.exists(filler, LinkOption.NOFOLLOW_LINKS));
            Files.createFile(filler);
            byte[] block = new byte[(int) MIB];
            new SecureRandom().nextBytes(block);
            long written = 0;
            long fillStarted = SystemClock.elapsedRealtime();
            try (FileOutputStream output = new FileOutputStream(filler.toFile())) {
                while (files.getUsableSpace() > targetAvailable) {
                    withinDeadline(started, files);
                    require(SystemClock.elapsedRealtime() - fillStarted < MAX_FILL_MS);
                    long available = files.getUsableSpace();
                    int count = (int) Math.min(block.length, available - targetAvailable);
                    require(count > 0 && written + count <= MAX_FILL && available - count >= RESERVE + 8 * MIB);
                    output.write(block, 0, count); // sequential writes, no seek/truncate/fallocate
                    written += count;
                    if (written % (8 * MIB) < MIB) output.getFD().sync();
                }
                output.getFD().sync();
            }
            long lowAvailable = files.getUsableSpace();
            long fillElapsed = SystemClock.elapsedRealtime() - fillStarted;
            long allocated = Math.multiplyExact(Os.stat(filler.toString()).st_blocks, 512L);
            require(written > 0 && Files.size(filler) == written && allocated >= written);
            require(Os.stat(filler.toString()).st_dev == Os.stat(realFiles.toString()).st_dev);
            require(lowAvailable >= RESERVE && lowAvailable < required && fillElapsed < MAX_FILL_MS);
            report.put("fillerWrittenBytes", written);
            report.put("fillerAllocatedBytes", allocated);
            report.put("fillElapsedMilliseconds", fillElapsed);
            report.put("lowFileUsableBytes", lowAvailable);
            report.put("lowStatFsAvailableBytes", new StatFs(files.toString()).getAvailableBytes());
            host.pass("Non-sparse same-filesystem writes reduce actual available space below preflight while retaining reserve");

            stage = "actual-insufficient-space-preflight";
            boolean spaceRejected = false;
            int[] callbacks = {0};
            try {
                // If free space changes and preflight unexpectedly succeeds,
                // stop before hashing/extraction. This abort is never a pass.
                installer.install((message, percent) -> { callbacks[0]++; throw new GuardedAbort(); });
            } catch (IOException expected) {
                spaceRejected = expected.getMessage() != null && expected.getMessage().startsWith("空间不足：");
            }
            require(spaceRejected && callbacks[0] == 0);
            require(Arrays.equals(oldPointer, Files.readAllBytes(pointer)) && installer.rootfs().equals(oldRoot));
            require(Arrays.equals(oldReady, Files.readAllBytes(oldRoot.resolve(".ready")))
                    && oldDaemon.equals(digest(oldRoot.resolve("usr/local/bin/atomcode-daemon"))));
            require(Arrays.equals(canaryBytes, Files.readAllBytes(canary))
                    && !Files.exists(fixture.resolve("runtime/staging"), LinkOption.NOFOLLOW_LINKS));
            require(files.getUsableSpace() >= RESERVE);
            report.put("spacePreflightRejected", true);
            report.put("extractionProgressCallbacks", callbacks[0]);
            report.put("oldPointerReadyDaemonCanaryUnchanged", true);
            host.pass("Real insufficient-space preflight rejects installation and preserves committed fixture data");

            stage = "free-filler-and-reinstall";
            Files.delete(filler);
            require(!Files.exists(filler, LinkOption.NOFOLLOW_LINKS));
            long released = files.getUsableSpace();
            report.put("releasedFileUsableBytes", released);
            require(released >= required + 32 * MIB);
            installer.install((message, percent) -> withinDeadline(started, files));
            require(installer.isCurrent() && !installer.rootfs().equals(oldRoot));
            require(!Arrays.equals(oldPointer, Files.readAllBytes(pointer)));
            require(Arrays.equals(canaryBytes, Files.readAllBytes(canary))
                    && Arrays.equals(oldReady, Files.readAllBytes(oldRoot.resolve(".ready")))
                    && oldDaemon.equals(digest(oldRoot.resolve("usr/local/bin/atomcode-daemon"))));
            report.put("reinstallAfterReleaseSucceeded", true);
            report.put("canaryPreservedAfterReinstall", true);
            report.put("finalFileUsableBytes", files.getUsableSpace());
            host.pass("Deleting filler restores real installation success with old generation and fixture home preserved");
            passed = true;
        } catch (Throwable failure) {
            report.put("failureStage", stage);
            report.put("failureClass", failure.getClass().getSimpleName());
            host.evidence.append("FAIL: ").append(stage).append('/').append(failure.getClass().getSimpleName()).append('\n');
        } finally {
            if (created) {
                try {
                    report.put("beforeCleanupFileUsableBytes", fixture.toFile().getUsableSpace());
                    if (filler != null) Files.deleteIfExists(filler);
                    RuntimeFiles.deleteTree(fixture); // exact newly created fixture, never real home
                    require(!Files.exists(fixture, LinkOption.NOFOLLOW_LINKS));
                    report.put("fixtureAndFillerRemoved", true);
                    if (passed) host.pass("Unique low-space fixture and filler are removed without accessing real home");
                } catch (Throwable failure) {
                    passed = false;
                    report.put("cleanupFailureClass", failure.getClass().getSimpleName());
                    host.evidence.append("FAIL: isolated low-space cleanup/").append(failure.getClass().getSimpleName()).append('\n');
                }
            }
        }
        report.put("status", passed ? "passed" : "not-passed");
        report.put("elapsedMilliseconds", SystemClock.elapsedRealtime() - started);
        host.evidence.append("REAL_SPACE_REPORT: ").append(new JSONObject(report)).append('\n');
        Bundle result = new Bundle();
        result.putString("stream", host.evidence.toString());
        host.finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
