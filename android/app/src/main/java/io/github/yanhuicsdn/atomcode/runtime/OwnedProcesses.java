package io.github.yanhuicsdn.atomcode.runtime;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Only same-UID processes carrying our exact inherited runtime marker. */
final class OwnedProcesses {
    private static volatile String lastDiagnostic = "stage=none;exception=none;errno=0;gone=false;zombie=false;identityChanged=false";
    static String diagnostic() { return lastDiagnostic; }

    private static Path path(int pid, String member) { return new File("/proc/" + pid + "/" + member).toPath(); }

    private static ProcessOwnership.Failure memberFailure(ProcessOwnership.Stage stage, IOException error) {
        int errno = error instanceof java.nio.file.NoSuchFileException ? OsConstants.ENOENT
                : error instanceof java.nio.file.AccessDeniedException ? OsConstants.EACCES : 0;
        // A missing member alone is not proof that its process directory is gone.
        return new ProcessOwnership.Failure(stage, error, errno, false);
    }

    private static final class ProcAccess implements ProcessOwnership.Access {
        @Override public int uid(int pid) throws ProcessOwnership.Failure {
            try { return Os.stat("/proc/" + pid).st_uid; }
            catch (android.system.ErrnoException error) {
                boolean gone = error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH;
                throw new ProcessOwnership.Failure(ProcessOwnership.Stage.UID, error, error.errno, gone);
            }
        }
        @Override public ProcessOwnership.Stat stat(int pid) throws ProcessOwnership.Failure {
            try { return ProcessOwnership.parseStat(new String(Files.readAllBytes(path(pid, "stat")), StandardCharsets.UTF_8)); }
            catch (IOException error) { throw memberFailure(ProcessOwnership.Stage.STAT, error); }
        }
        @Override public byte[] environ(int pid) throws ProcessOwnership.Failure {
            try { return Files.readAllBytes(path(pid, "environ")); }
            catch (IOException error) { throw memberFailure(ProcessOwnership.Stage.ENVIRON, error); }
        }
        @Override public boolean proot(int pid) throws ProcessOwnership.Failure {
            try {
                String command = new String(Files.readAllBytes(path(pid, "cmdline")), StandardCharsets.UTF_8);
                return command.split("\0", 2)[0].endsWith("/libproot.so");
            } catch (IOException error) { throw memberFailure(ProcessOwnership.Stage.CMDLINE, error); }
        }
        @Override public void signal(int pid, int signal) throws ProcessOwnership.Failure {
            try { Os.kill(pid, signal); }
            catch (android.system.ErrnoException error) {
                throw new ProcessOwnership.Failure(ProcessOwnership.Stage.SIGNAL, error, error.errno,
                        error.errno == OsConstants.ESRCH);
            }
        }
        @Override public void pause(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
    }

    private static List<ProcessOwnership.Identity> scan(ProcessOwnership ownership, String marker) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            File[] processes = new File("/proc").listFiles();
            if (processes != null) {
                List<Integer> listed = new ArrayList<>();
                for (File directory : processes) {
                    if (!directory.getName().matches("[0-9]+")) continue;
                    try {
                        int pid = Integer.parseInt(directory.getName());
                        if (pid != Process.myPid()) listed.add(pid);
                    } catch (NumberFormatException ignored) {}
                }
                return ownership.scan(listed, marker);
            }
            ownership.report(new ProcessOwnership.Failure(ProcessOwnership.Stage.LIST, new IOException(), 0, false));
            if (attempt < 3) Thread.sleep(25L * (attempt + 1));
        }
        throw new IOException("无法检查本机引擎进程");
    }

    static void stopAll(String marker) throws IOException, InterruptedException {
        lastDiagnostic = "stage=none;exception=none;errno=0;gone=false;zombie=false;identityChanged=false";
        ProcessOwnership ownership = new ProcessOwnership(Process.myUid(), new ProcAccess(), value -> lastDiagnostic = value);
        List<ProcessOwnership.Identity> first = scan(ownership, marker);
        for (ProcessOwnership.Identity identity : first) if (identity.proot()) ownership.signal(identity, OsConstants.SIGQUIT);
        if (!first.isEmpty()) Thread.sleep(250);
        for (int pass = 0; pass < 5; pass++) {
            List<ProcessOwnership.Identity> remaining = scan(ownership, marker);
            if (remaining.isEmpty()) return;
            for (ProcessOwnership.Identity identity : remaining) ownership.signal(identity, OsConstants.SIGKILL);
            Thread.sleep(100);
        }
        if (!scan(ownership, marker).isEmpty()) throw new IOException("旧引擎进程仍在运行，未启动新的引擎");
    }
}
