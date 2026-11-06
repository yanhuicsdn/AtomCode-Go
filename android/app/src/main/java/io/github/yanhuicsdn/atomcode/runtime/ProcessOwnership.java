package io.github.yanhuicsdn.atomcode.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded process-identity decisions; the Android adapter owns the actual /proc IO. */
final class ProcessOwnership {
    enum Stage { LIST, UID, STAT, ENVIRON, CMDLINE, SIGNAL, IDENTITY }
    record Stat(String started, char state) {
        boolean exited() { return state == 'Z' || state == 'X' || state == 'x'; }
    }
    record Identity(int pid, String started, boolean proot) {}
    private record Snapshot(int uid, Stat stat) {}

    static final class Failure extends IOException {
        final Stage stage;
        final String exceptionClass;
        final int errno;
        final boolean confirmedGone;
        Failure(Stage stage, Exception error, int errno, boolean confirmedGone) {
            super("process inspection unavailable");
            this.stage = stage;
            String name = error.getClass().getSimpleName();
            this.exceptionClass = name.matches("[A-Za-z0-9_$]+") ? name : "UnknownException";
            this.errno = errno;
            this.confirmedGone = confirmedGone;
        }
    }

    interface Access {
        int uid(int pid) throws Failure;
        Stat stat(int pid) throws Failure;
        byte[] environ(int pid) throws Failure;
        boolean proot(int pid) throws Failure;
        void signal(int pid, int signal) throws Failure;
        void pause(long milliseconds) throws InterruptedException;
    }

    private static final int ATTEMPTS = 4;
    private final int uid;
    private final Access access;
    private final Consumer<String> diagnostic;
    // This cache is deliberately local to one stopAll invocation. It contains
    // only identities proved with a marker and matching snapshots, not env data.
    private final Map<Integer, Identity> known = new HashMap<>();

    ProcessOwnership(int uid, Access access, Consumer<String> diagnostic) {
        this.uid = uid;
        this.access = access;
        this.diagnostic = diagnostic;
    }

    static Stat parseStat(String text) throws IOException {
        int end = text.lastIndexOf(") ");
        if (end < 0) throw new IOException("进程身份格式无效");
        String[] fields = text.substring(end + 2).trim().split("\\s+");
        if (fields.length < 20 || fields[0].length() != 1 || !fields[19].matches("[0-9]+"))
            throw new IOException("进程身份格式无效");
        return new Stat(fields[19], fields[0].charAt(0));
    }

    static String observation(Stage stage, String exceptionClass, int errno,
                              boolean gone, boolean zombie, boolean identityChanged) {
        return "stage=" + stage.name().toLowerCase(Locale.ROOT) + ";exception=" + exceptionClass
                + ";errno=" + errno + ";gone=" + gone + ";zombie=" + zombie
                + ";identityChanged=" + identityChanged;
    }

    void report(Failure failure) {
        diagnostic.accept(observation(failure.stage, failure.exceptionClass, failure.errno,
                failure.confirmedGone, false, false));
    }

    private void changed() {
        diagnostic.accept(observation(Stage.IDENTITY, "none", 0, false, false, true));
    }

    private Snapshot snapshot(int pid) throws Failure {
        int before = access.uid(pid);
        if (before != uid) return new Snapshot(before, null);
        Stat stat = access.stat(pid);
        int after = access.uid(pid);
        return after == uid ? new Snapshot(uid, stat) : new Snapshot(after, null);
    }

    private boolean liveOwnedUid(Snapshot snapshot) {
        if (snapshot.uid != uid) return false;
        if (snapshot.stat.exited()) {
            diagnostic.accept(observation(Stage.STAT, "none", 0, false, true, false));
            return false;
        }
        return true;
    }

    private static boolean same(Snapshot first, Snapshot second) {
        return first.uid == second.uid && first.stat != null && second.stat != null
                && first.stat.started.equals(second.stat.started);
    }

    private static boolean hasMarker(byte[] environment, String marker) {
        byte[] expected = ("ATOMCODE_ANDROID_RUNTIME=" + marker).getBytes(StandardCharsets.UTF_8);
        int start = 0;
        for (int end = 0; end <= environment.length; end++) {
            if (end < environment.length && environment[end] != 0) continue;
            if (end - start == expected.length) {
                boolean equal = true;
                for (int offset = 0; offset < expected.length; offset++)
                    if (environment[start + offset] != expected[offset]) { equal = false; break; }
                if (equal) return true;
            }
            start = end + 1;
        }
        return false;
    }

    private void retryPause(int attempt) throws InterruptedException {
        if (attempt + 1 < ATTEMPTS) access.pause(25L * (attempt + 1));
    }

    private boolean resolvedExitOrReplacement(int pid, Identity expected) {
        try {
            Snapshot fresh = snapshot(pid);
            if (!liveOwnedUid(fresh)) {
                if (fresh.uid != uid && expected != null) changed();
                known.remove(pid);
                return true;
            }
            if (expected != null && !fresh.stat.started.equals(expected.started)) {
                known.remove(pid);
                changed();
                return true;
            }
        } catch (Failure check) {
            report(check);
            if (check.confirmedGone) { known.remove(pid); return true; }
        }
        return false;
    }

    private Identity inspect(int pid, String marker) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                Snapshot before = snapshot(pid);
                if (!liveOwnedUid(before)) {
                    if (before.uid != uid && known.containsKey(pid)) changed();
                    known.remove(pid); return null;
                }
                Identity cached = known.get(pid);
                if (cached != null && cached.started.equals(before.stat.started)) return cached;
                if (cached != null) { known.remove(pid); changed(); }
                boolean marked = hasMarker(access.environ(pid), marker);
                Snapshot after = snapshot(pid);
                if (!liveOwnedUid(after)) { known.remove(pid); return null; }
                if (!same(before, after)) { changed(); retryPause(attempt); continue; }
                if (!marked) return null;
                boolean proot = access.proot(pid);
                Snapshot confirmed = snapshot(pid);
                if (!liveOwnedUid(confirmed)) { known.remove(pid); return null; }
                if (!same(before, confirmed)) { changed(); retryPause(attempt); continue; }
                Identity identity = new Identity(pid, before.stat.started, proot);
                known.put(pid, identity);
                return identity;
            } catch (Failure failure) {
                report(failure);
                if (failure.confirmedGone) { known.remove(pid); return null; }
                if (resolvedExitOrReplacement(pid, null)) return null;
            }
            retryPause(attempt);
        }
        throw new IOException("不能确认旧引擎是否已退出，请停止应用后重试");
    }

    List<Identity> scan(List<Integer> listed, String marker) throws IOException, InterruptedException {
        // An already proved process cannot disappear just because one /proc
        // directory enumeration missed it. Revalidate its identity directly.
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>(listed);
        candidates.addAll(known.keySet());
        List<Identity> owned = new ArrayList<>();
        for (int pid : candidates) {
            Identity identity = inspect(pid, marker);
            if (identity != null) owned.add(identity);
        }
        return owned;
    }

    void signal(Identity identity, int signal) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                Snapshot current = snapshot(identity.pid);
                if (!liveOwnedUid(current)) {
                    if (current.uid != uid) changed();
                    known.remove(identity.pid); return;
                }
                if (!current.stat.started.equals(identity.started)) {
                    known.remove(identity.pid); changed(); return;
                }
                access.signal(identity.pid, signal);
                return;
            } catch (Failure failure) {
                report(failure);
                if (failure.confirmedGone) { known.remove(identity.pid); return; }
                if (resolvedExitOrReplacement(identity.pid, identity)) return;
            }
            retryPause(attempt);
        }
        throw new IOException("停止旧引擎失败");
    }
}
