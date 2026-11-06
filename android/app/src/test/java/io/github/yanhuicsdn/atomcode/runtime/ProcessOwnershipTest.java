package io.github.yanhuicsdn.atomcode.runtime;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ProcessOwnershipTest {
    private static final int UID = 10001;
    private static final String MARKER = "test-runtime";
    private static final class State {
        int uid = UID;
        String started = "101";
        char state = 'R';
        byte[] env = ("SECRET=never-log-this\0ATOMCODE_ANDROID_RUNTIME=" + MARKER + "\0").getBytes(StandardCharsets.UTF_8);
    }
    private interface Action { void run() throws ProcessOwnership.Failure; }
    private static final class Fake implements ProcessOwnership.Access {
        final Map<Integer, State> states = new HashMap<>();
        final List<Integer> delivered = new ArrayList<>();
        Action beforeEnvironment, beforeStat, beforeSignal;
        byte[] environmentResult;
        int environmentReads, signalAttempts, pauses;
        Fake() { states.put(42, new State()); }
        State state(int pid) throws ProcessOwnership.Failure {
            if (!states.containsKey(pid)) throw failure(ProcessOwnership.Stage.UID, 2, true);
            return states.get(pid);
        }
        @Override public int uid(int pid) throws ProcessOwnership.Failure { return state(pid).uid; }
        @Override public ProcessOwnership.Stat stat(int pid) throws ProcessOwnership.Failure {
            if (beforeStat != null) beforeStat.run();
            State state = state(pid); return new ProcessOwnership.Stat(state.started, state.state);
        }
        @Override public byte[] environ(int pid) throws ProcessOwnership.Failure {
            environmentReads++;
            if (beforeEnvironment != null) beforeEnvironment.run();
            return environmentResult == null ? state(pid).env : environmentResult;
        }
        @Override public boolean proot(int pid) { return true; }
        @Override public void signal(int pid, int signal) throws ProcessOwnership.Failure {
            signalAttempts++;
            if (beforeSignal != null) beforeSignal.run();
            delivered.add(pid); state(pid).state = 'Z';
        }
        @Override public void pause(long milliseconds) { pauses++; }
    }
    private static ProcessOwnership.Failure failure(ProcessOwnership.Stage stage, int errno, boolean gone) {
        return new ProcessOwnership.Failure(stage, new IOException("SECRET /private/path must not reach diagnostics"), errno, gone);
    }
    private static ProcessOwnership verifier(Fake source, List<String> diagnostics) {
        return new ProcessOwnership(UID, source, diagnostics::add);
    }

    @Test public void exitingProcessCanBecomeZombieWhileEnvironmentReadFails() throws Exception {
        Fake source = new Fake();
        source.beforeEnvironment = () -> {
            source.states.get(42).state = 'Z';
            throw failure(ProcessOwnership.Stage.ENVIRON, 5, false);
        };
        List<String> diagnostics = new ArrayList<>();
        assertTrue(verifier(source, diagnostics).scan(List.of(42), MARKER).isEmpty());
        assertTrue(source.states.containsKey(42)); // The directory still exists.
        assertTrue(source.delivered.isEmpty());
        assertTrue(diagnostics.get(diagnostics.size() - 1).contains("zombie=true"));
    }

    @Test public void transientLiveReadIsRetriedAndConfirmedIdentityDoesNotDependOnLaterEnvironmentReads() throws Exception {
        Fake source = new Fake();
        source.beforeEnvironment = () -> {
            if (source.environmentReads == 1) throw failure(ProcessOwnership.Stage.ENVIRON, 5, false);
        };
        ProcessOwnership verifier = verifier(source, new ArrayList<>());
        ProcessOwnership.Identity identity = verifier.scan(List.of(42), MARKER).get(0);
        assertEquals(2, source.environmentReads);
        source.beforeEnvironment = () -> { throw failure(ProcessOwnership.Stage.ENVIRON, 13, false); };
        assertEquals(identity, verifier.scan(List.of(), MARKER).get(0)); // Enumeration omission is not exit.
        assertEquals(2, source.environmentReads);
        verifier.signal(identity, 9);
        assertEquals(List.of(42), source.delivered);
        assertTrue(verifier.scan(List.of(42), MARKER).isEmpty());
        // A different stop invocation must prove ownership again, not reuse the cache.
        source.states.get(42).state = 'R';
        assertThrows(IOException.class, () -> verifier(source, new ArrayList<>()).scan(List.of(42), MARKER));
    }

    @Test public void markerReadAcrossPidReuseCannotClassifyOrSignalTheReplacement() throws Exception {
        Fake source = new Fake();
        byte[] oldMarker = source.states.get(42).env;
        source.beforeEnvironment = () -> {
            if (source.environmentReads == 1) {
                source.states.get(42).started = "202";
                source.states.get(42).env = "OTHER=not-owned\0".getBytes(StandardCharsets.UTF_8);
                source.environmentResult = oldMarker;
            } else source.environmentResult = null;
        };
        assertTrue(verifier(source, new ArrayList<>()).scan(List.of(42), MARKER).isEmpty());
        assertTrue(source.delivered.isEmpty());
    }

    @Test public void signalRechecksBothUidAndStartTimeInsteadOfUsingAnOldPid() throws Exception {
        for (boolean uidChanged : new boolean[]{false, true}) {
            Fake source = new Fake();
            ProcessOwnership verifier = verifier(source, new ArrayList<>());
            ProcessOwnership.Identity old = verifier.scan(List.of(42), MARKER).get(0);
            if (uidChanged) source.states.get(42).uid = UID + 1;
            else source.states.get(42).started = "202";
            verifier.signal(old, 9);
            assertTrue(source.delivered.isEmpty());
            if (!uidChanged) {
                ProcessOwnership.Identity replacement = verifier.scan(List.of(42), MARKER).get(0);
                assertNotEquals(old.started(), replacement.started());
                verifier.signal(replacement, 9);
                assertEquals(List.of(42), source.delivered);
            }
        }
    }

    @Test public void persistentUnreadableLiveProcessFailsClosedWithBoundedSafeDiagnostics() {
        Fake source = new Fake();
        source.beforeEnvironment = () -> { throw failure(ProcessOwnership.Stage.ENVIRON, 13, false); };
        List<String> diagnostics = new ArrayList<>();
        assertThrows(IOException.class, () -> verifier(source, diagnostics).scan(List.of(42), MARKER));
        assertEquals(4, source.environmentReads);
        assertEquals(3, source.pauses);
        assertTrue(source.delivered.isEmpty());
        String report = String.join("\n", diagnostics);
        assertTrue(report.contains("stage=environ;exception=IOException;errno=13;gone=false"));
        assertFalse(report.contains("SECRET")); assertFalse(report.contains("private")); assertFalse(report.contains(MARKER));
    }

    @Test public void missingStatMemberAloneDoesNotProveThatTheProcessDirectoryIsGone() {
        Fake source = new Fake();
        source.beforeStat = () -> { throw failure(ProcessOwnership.Stage.STAT, 2, false); };
        assertThrows(IOException.class, () -> verifier(source, new ArrayList<>()).scan(List.of(42), MARKER));
        assertEquals(3, source.pauses);
        assertTrue(source.delivered.isEmpty());
    }

    @Test public void typedGoneIdentityAndZombieStatesDoNotRequireSignals() throws Exception {
        Fake absent = new Fake(); absent.states.clear();
        assertTrue(verifier(absent, new ArrayList<>()).scan(List.of(42), MARKER).isEmpty());
        for (char state : new char[]{'Z', 'X', 'x'}) {
            Fake source = new Fake(); source.states.get(42).state = state;
            assertTrue(verifier(source, new ArrayList<>()).scan(List.of(42), MARKER).isEmpty());
            assertEquals(0, source.environmentReads); assertTrue(source.delivered.isEmpty());
        }
    }

    @Test public void persistentSignalFailureOnTheSameLiveIdentityFailsClosed() throws Exception {
        Fake source = new Fake();
        ProcessOwnership verifier = verifier(source, new ArrayList<>());
        ProcessOwnership.Identity identity = verifier.scan(List.of(42), MARKER).get(0);
        source.beforeSignal = () -> { throw failure(ProcessOwnership.Stage.SIGNAL, 1, false); };
        assertThrows(IOException.class, () -> verifier.signal(identity, 9));
        assertEquals(4, source.signalAttempts); assertTrue(source.delivered.isEmpty());
    }

    @Test public void statParsingHandlesParenthesesInCommAndRejectsIncompleteIdentity() throws Exception {
        String stat = "42 (cmd ) with spaces) S " + "0 ".repeat(18) + "12345 0 0\n";
        ProcessOwnership.Stat identity = ProcessOwnership.parseStat(stat);
        assertEquals("12345", identity.started()); assertEquals('S', identity.state());
        assertThrows(IOException.class, () -> ProcessOwnership.parseStat("42 (unfinished"));
        assertThrows(IOException.class, () -> ProcessOwnership.parseStat("42 (cmd) R 1 2"));
    }
}
