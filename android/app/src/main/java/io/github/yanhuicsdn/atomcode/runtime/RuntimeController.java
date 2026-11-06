package io.github.yanhuicsdn.atomcode.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeController {
    public enum Phase { STOPPED, INSTALLING, STARTING, RUNNING, STOPPING, TRANSFERRING, ERROR }
    public interface Listener { void changed(); }
    private static RuntimeController instance;
    public static synchronized RuntimeController get(Context context) {
        if (instance == null) instance = new RuntimeController(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile Process process;
    private volatile LocalGateway gateway;
    private volatile Path run;
    private volatile String token;
    private volatile boolean userStopped;
    private Object serviceOwner;
    public volatile Phase phase = Phase.STOPPED;
    public volatile String message = "运行环境尚未启动";
    public volatile int progress;
    private RuntimeController(Context context) { this.context = context; }

    public void addListener(Listener listener) { listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public String origin() { LocalGateway value = gateway; return phase == Phase.RUNNING && value != null ? value.origin : null; }
    public long connectionGeneration() { return generation.get(); }
    public byte[] certificate() { LocalGateway value = gateway; return value == null ? null : value.certificate.clone(); }
    public String gatewayFailure() { LocalGateway value = gateway; return value == null ? null : value.lastFailure; }
    public String processInspection() { return OwnedProcesses.diagnostic(); }
    public String entryUrl() { String base = origin(); return base == null || token == null ? null : base + "/?token=" + token; }
    public boolean busy() { return phase == Phase.INSTALLING || phase == Phase.STARTING || phase == Phase.STOPPING || phase == Phase.TRANSFERRING; }
    public boolean shouldAutoStart() { return phase == Phase.STOPPED && !userStopped; }

    public synchronized void attachService(Object owner) { serviceOwner = owner; }
    public synchronized void detachService(Object owner) {
        // A retired Service can be destroyed after its replacement starts.
        // Only the current instance may stop the runtime it owns.
        if (serviceOwner != owner) return;
        serviceOwner = null;
        if (phase == Phase.RUNNING || phase == Phase.STARTING || phase == Phase.INSTALLING) stop();
    }

    public interface Transfer { String run(Path workspace) throws Exception; }
    public synchronized void transfer(Transfer operation) {
        if (busy() || phase == Phase.RUNNING) return;
        state(Phase.TRANSFERRING, "正在处理工作区文件");
        worker.execute(() -> {
            try {
                OwnedProcesses.stopAll(context.getFilesDir().getCanonicalPath());
                Path workspace = context.getFilesDir().toPath().resolve("home/workspace");
                Files.createDirectories(workspace);
                String result = operation.run(workspace);
                state(Phase.STOPPED, result);
            } catch (Exception error) { state(Phase.ERROR, "工作区操作失败：" + error.getMessage()); }
        });
    }

    private void state(Phase next, String text) {
        phase = next;
        message = text;
        main.post(() -> { for (Listener listener : listeners) listener.changed(); });
    }

    public synchronized void start() {
        if (busy() || phase == Phase.RUNNING) return;
        long current = generation.incrementAndGet();
        userStopped = false;
        state(Phase.STARTING, "检查本机运行环境");
        worker.execute(() -> launch(current));
    }

    private void launch(long current) {
        try {
            if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new IOException("此安装包需要 ARM64 设备");
            OwnedProcesses.stopAll(context.getFilesDir().getCanonicalPath());
            checkGeneration(current);
            RuntimeInstaller installer = new RuntimeInstaller(context);
            if (!installer.isCurrent()) {
                state(Phase.INSTALLING, "准备安装本机 Linux 环境");
                installer.install((text, percent) -> {
                    if (generation.get() != current) throw new IllegalStateException("安装已取消，可重新开始");
                    if (percent != progress || !message.equals(text)) {
                        progress = percent;
                        state(Phase.INSTALLING, text);
                    }
                });
            }
            checkGeneration(current);
            installer.verifyNativeLibraries();
            Path rootfs = installer.rootfs();
            Files.createDirectories(installer.home().resolve("workspace"));
            Files.createDirectories(installer.home().resolve(".atomcode"));
            Path temporary = context.getCacheDir().toPath().resolve("proot");
            Files.createDirectories(temporary);
            run = context.getFilesDir().toPath().resolve("run").resolve(UUID.randomUUID().toString().substring(0, 8));
            Files.createDirectories(run);
            Path socket = run.resolve("s.sock");
            LocalGateway local = new LocalGateway(socket);
            gateway = local;
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            StringBuilder secret = new StringBuilder();
            for (byte b : random) secret.append(String.format("%02x", b & 255));
            token = secret.toString();
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            String marker = context.getFilesDir().getCanonicalPath();
            refreshDns(rootfs);
            List<String> args = new ArrayList<>(Arrays.asList(nativeDir + "/libproot.so",
                    "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0", "--rootfs=" + rootfs,
                    "--cwd=/root/workspace", "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", "/dev/urandom:/dev/random", "-b", "/proc/self/fd:/dev/fd",
                    "-b", installer.home() + ":/root", "-b", run + ":/run/atomcode",
                    // The outer environment is already allowlisted below.
                    // Strip only host-loader variables, preserving the private
                    // daemon token without placing it in long-lived argv.
                    "/usr/bin/env", "-u", "ANDROID_ROOT", "-u", "ANDROID_DATA", "-u", "LD_LIBRARY_PATH",
                    "-u", "PROOT_TMP_DIR", "-u", "PROOT_LOADER", "-u", "PROOT_L2S_DIR",
                    "HOME=/root", "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
                    "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp", "SHELL=/bin/bash",
                    "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt", "GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt",
                    "ATOMCODE_HOME=/root/.atomcode", "ATOMCODE_ANDROID_RUNTIME=" + marker,
                    "RUST_LOG=error",
                    "/usr/local/bin/atomcode-daemon", "--host", "127.0.0.1", "--port", Integer.toString(local.port()),
                    "--unix-socket", "/run/atomcode/s.sock", "--client", "webui", "--no-telemetry", "--idle-timeout", "0"));
            ProcessBuilder builder = new ProcessBuilder(args).directory(context.getFilesDir()).redirectErrorStream(true);
            Map<String,String> env = builder.environment();
            env.clear();
            env.put("PATH", "/system/bin:/system/xbin");
            env.put("HOME", marker);
            env.put("ANDROID_ROOT", "/system");
            env.put("ANDROID_DATA", "/data");
            env.put("TMPDIR", temporary.toString());
            env.put("PROOT_TMP_DIR", temporary.toString());
            env.put("PROOT_LOADER", nativeDir + "/libproot-loader.so");
            env.put("PROOT_L2S_DIR", installer.home().resolve(".l2s").toString());
            Files.createDirectories(installer.home().resolve(".l2s"));
            env.put("LD_LIBRARY_PATH", nativeDir);
            env.put("ATOMCODE_ANDROID_RUNTIME", marker);
            env.put("ATOMCODE_DAEMON_TOKEN", token);
            state(Phase.STARTING, "正在启动手机本机引擎");
            Process launched;
            synchronized (this) {
                checkGeneration(current);
                launched = builder.start();
                process = launched;
            }
            Thread drain = new Thread(() -> {
                // Consume output to prevent pipe backpressure. Never retain
                // model/tool output, command lines or provider credentials.
                try (InputStream output = launched.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    while (output.read(buffer) != -1) {}
                } catch (IOException ignored) {}
            }, "atomcode-output");
            drain.start();
            boolean ready = false;
            for (int attempt = 0; attempt < 180; attempt++) {
                checkGeneration(current);
                if (!launched.isAlive()) throw new IOException("本机引擎启动失败（退出码 " + launched.exitValue() + "）");
                if (Files.exists(socket)) {
                    try { ready = health(socket, installer.manifest); } catch (IOException ignored) {}
                    if (ready) break;
                }
                Thread.sleep(500);
            }
            if (!ready) throw new IOException("本机引擎未在 90 秒内就绪，可停止后重试");
            checkGeneration(current);
            local.start();
            state(Phase.RUNNING, context.getString(io.github.yanhuicsdn.atomcode.R.string.runtime_running));
            Thread monitor = new Thread(() -> {
                try {
                    int code = launched.waitFor();
                    if (generation.compareAndSet(current, current + 1)) {
                        local.close();
                        token = null;
                        state(Phase.ERROR, "本机引擎已退出（退出码 " + code + "），工作区已保留，可重新启动");
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "atomcode-monitor");
            monitor.start();
        } catch (Exception error) {
            LocalGateway value = gateway;
            if (value != null) value.close();
            token = null;
            try { OwnedProcesses.stopAll(context.getFilesDir().getCanonicalPath()); }
            catch (Exception cleanup) { error.addSuppressed(cleanup); }
            if (generation.get() == current) state(Phase.ERROR, error.getMessage() == null ? "本机引擎启动失败" : error.getMessage());
        }
    }

    private void checkGeneration(long current) throws IOException {
        if (generation.get() != current) throw new IOException("启动已取消");
    }

    private void refreshDns(Path rootfs) throws IOException {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        LinkProperties properties = connectivity.getLinkProperties(connectivity.getActiveNetwork());
        StringBuilder dns = new StringBuilder("# Android active-network DNS\noptions timeout:2 attempts:2\n");
        if (properties != null) for (java.net.InetAddress address : properties.getDnsServers())
            dns.append("nameserver ").append(address.getHostAddress()).append('\n');
        // No hardcoded public DNS: offline startup remains possible and model
        // connection failures are shown by the existing daemon UI.
        Files.write(rootfs.resolve("etc/resolv.conf"), dns.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void networkChanged() {
        long current = generation.get();
        if (phase != Phase.RUNNING) return;
        worker.execute(() -> {
            try {
                if (generation.get() == current && phase == Phase.RUNNING)
                    refreshDns(new RuntimeInstaller(context).rootfs());
            } catch (IOException ignored) {
                // Preserve the running agent and user's work. Actual model
                // transport failures remain visible in the WebUI.
            }
        });
    }

    private static boolean health(Path socket, RuntimeManifest manifest) throws Exception {
        try (LocalSocket connection = new LocalSocket()) {
            connection.connect(new LocalSocketAddress(socket.toString(), LocalSocketAddress.Namespace.FILESYSTEM));
            connection.setSoTimeout(2000);
            connection.getOutputStream().write("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response = RuntimeManifest.readText(connection.getInputStream(), 65536);
            int body = response.indexOf("\r\n\r\n");
            if (!response.startsWith("HTTP/1.1 200 ") || body < 0) return false;
            JSONObject json = new JSONObject(response.substring(body + 4));
            return json.getString("service").equals("atomcode-daemon")
                    && json.getString("binary_hash").equals(manifest.json.getJSONObject("build").getString("daemonSha256"));
        }
    }

    public synchronized void stop() {
        if (phase == Phase.STOPPING || phase == Phase.STOPPED) return;
        userStopped = true;
        generation.incrementAndGet();
        state(Phase.STOPPING, "正在停止本机引擎");
        token = null;
        LocalGateway local = gateway;
        if (local != null) local.close();
        worker.execute(() -> {
            try {
                OwnedProcesses.stopAll(context.getFilesDir().getCanonicalPath());
                Process value = process;
                if (value != null && value.isAlive()) value.destroyForcibly();
                process = null;
                if (run != null) RuntimeFiles.deleteTree(run);
                gateway = null;
                state(Phase.STOPPED, "引擎已停止，工作区和会话已保留");
            } catch (Exception error) { state(Phase.ERROR, "停止未完成：" + error.getMessage()); }
        });
    }
}
