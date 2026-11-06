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
    private record Identity(int pid, String started, boolean proot) {}

    private static String startTime(Path process) throws IOException {
        String stat = new String(Files.readAllBytes(process.resolve("stat")), StandardCharsets.UTF_8);
        String[] fields = stat.substring(stat.lastIndexOf(") ") + 2).trim().split(" +");
        if (fields.length < 20) throw new IOException("进程身份读取失败");
        return fields[19];
    }

    private static List<Identity> scan(String marker) throws IOException {
        File[] processes = new File("/proc").listFiles();
        if (processes == null) throw new IOException("无法检查本机引擎进程");
        List<Identity> result = new ArrayList<>();
        for (File directory : processes) {
            if (!directory.getName().matches("[0-9]+")) continue;
            int pid;
            try { pid = Integer.parseInt(directory.getName()); } catch (NumberFormatException ignored) { continue; }
            if (pid == Process.myPid()) continue;
            try {
                if (Os.stat(directory.toString()).st_uid != Process.myUid()) continue;
                String identity = startTime(directory.toPath());
                String env = new String(Files.readAllBytes(directory.toPath().resolve("environ")), StandardCharsets.UTF_8);
                boolean ours = false;
                for (String item : env.split("\0")) if (item.equals("ATOMCODE_ANDROID_RUNTIME=" + marker)) ours = true;
                if (!ours) continue;
                String command = new String(Files.readAllBytes(directory.toPath().resolve("cmdline")), StandardCharsets.UTF_8);
                String first = command.split("\0", 2)[0];
                result.add(new Identity(pid, identity, first.endsWith("/libproot.so")));
            } catch (android.system.ErrnoException | IOException error) {
                if (directory.exists()) throw new IOException("不能确认旧引擎是否已退出，请停止应用后重试", error);
            }
        }
        return result;
    }

    private static void signal(Identity identity, int signal) throws IOException {
        Path path = new File("/proc/" + identity.pid).toPath();
        try {
            if (Os.stat(path.toString()).st_uid == Process.myUid() && startTime(path).equals(identity.started))
                Os.kill(identity.pid, signal);
        } catch (android.system.ErrnoException | IOException error) {
            if (Files.exists(path)) throw new IOException("停止旧引擎失败", error);
        }
    }

    static void stopAll(String marker) throws IOException, InterruptedException {
        List<Identity> first = scan(marker);
        for (Identity identity : first) if (identity.proot) signal(identity, OsConstants.SIGQUIT);
        if (!first.isEmpty()) Thread.sleep(250);
        for (int pass = 0; pass < 5; pass++) {
            List<Identity> remaining = scan(marker);
            if (remaining.isEmpty()) return;
            for (Identity identity : remaining) signal(identity, OsConstants.SIGKILL);
            Thread.sleep(100);
        }
        if (!scan(marker).isEmpty()) throw new IOException("旧引擎进程仍在运行，未启动新的引擎");
    }
}
