package io.github.yanhuicsdn.atomcode.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Process;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Test-only attacker process: no shared UID, credentials, exported activity or
 * external traffic. Endpoints and certificate hashes are public test metadata.
 */
public final class ForeignUidProbe extends Instrumentation {
    private static final int TIMEOUT_MS = 4000;
    private final StringBuilder evidence = new StringBuilder();
    private Bundle arguments;
    private int port;
    private String udsPath;
    private String expectedCertificate;
    private InetAddress loopback;
    private SSLContext attackerTls;

    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : new Bundle(supplied);
        start();
    }

    @Override public void onStart() {
        String stage = "arguments-and-uid";
        Bundle result = new Bundle();
        boolean success = false;
        try {
            port = Integer.parseInt(arguments.getString("port", ""));
            int ownerUid = Integer.parseInt(arguments.getString("owner_uid", ""));
            udsPath = arguments.getString("uds_path", "");
            expectedCertificate = arguments.getString("expected_certificate_sha256", "").toLowerCase(Locale.ROOT);
            require(port > 0 && port <= 65535 && ownerUid >= 10000);
            require(expectedCertificate.matches("[a-f0-9]{64}"));
            // The host supplies this one live socket; no directory traversal,
            // endpoint scanning or unrelated private-file access is permitted.
            require(udsPath.matches("/data/(?:user/0|data)/io\\.github\\.yanhuicsdn\\.atomcode/files/run/[a-f0-9]{8}/s\\.sock"));
            require(Process.myUid() >= 10000 && Process.myUid() != ownerUid);
            result.putInt("probe_uid", Process.myUid());
            result.putInt("owner_uid", ownerUid);
            loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            attackerTls = attackerContext();
            pass("Probe process has a distinct application UID");

            stage = "private-unix-socket";
            boolean denied = false;
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(udsPath, LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException failure) {
                denied = isPermissionDenied(failure);
            }
            require(denied);
            pass("Foreign UID is denied access to the live private Unix socket with EACCES");

            stage = "unauthenticated-api";
            require(requestStatus("127.0.0.1:" + port, null) == 401);
            pass("Foreign UID cannot access sessions without authentication");

            stage = "foreign-origin";
            require(requestStatus("127.0.0.1:" + port, "https://foreign.invalid") == 403);
            pass("Foreign Origin is rejected before unauthenticated API access");

            stage = "forged-host";
            require(requestStatus("forged.invalid", null) == 403);
            pass("Forged Host is rejected by the local TLS gateway");

            stage = "occupied-loopback-port";
            boolean occupied = false;
            try (ServerSocket listener = new ServerSocket()) {
                listener.setReuseAddress(false);
                listener.bind(new InetSocketAddress(loopback, port));
            } catch (BindException expected) {
                occupied = true;
            }
            require(occupied);
            pass("Foreign UID cannot bind the gateway's currently occupied loopback port");

            stage = "default-platform-tls-trust";
            require(defaultTrustRejectsCertificate());
            pass("Default platform TLS trust rejects the app's untrusted self-signed certificate");
            success = true;
        } catch (Throwable failure) {
            // No messages or nested stacks: platform errors may contain a
            // private socket path, URL, response header or certificate data.
            evidence.append("FAIL: ").append(stage).append('/').append(failure.getClass().getSimpleName()).append('\n');
        }
        result.putString("stream", evidence.toString());
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static boolean isPermissionDenied(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ErrnoException && ((ErrnoException)cause).errno == OsConstants.EACCES) return true;
            // LocalSocket's native implementation can throw IOException
            // without an ErrnoException cause. Do not accept ENOENT/refused.
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("permission denied") || lower.contains("eacces")) return true;
            }
        }
        return false;
    }

    private SSLContext attackerContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        // This models a hostile client that ignores platform server trust.
        // It is confined to this independent test APK. The observed peer is
        // still matched to the coordinator's public hash before any request.
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a client authority");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        }}, null);
        return context;
    }

    private Socket transport() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(loopback, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            return socket;
        } catch (IOException failure) {
            try { socket.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }

    private int requestStatus(String host, String origin) throws Exception {
        try (Socket raw = transport();
             SSLSocket tls = (SSLSocket)attackerTls.getSocketFactory().createSocket(raw, "127.0.0.1", port, true)) {
            tls.setSoTimeout(TIMEOUT_MS);
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls.startHandshake();
            byte[] certificate = tls.getSession().getPeerCertificates()[0].getEncoded();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            require(expectedCertificate.equals(hex.toString()));
            String request = "GET /sessions HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n"
                    + (origin == null ? "" : "Origin: " + origin + "\r\n") + "\r\n";
            tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            // The status line is sufficient; never retain response headers or bodies.
            String status = statusLine(tls.getInputStream());
            require(status.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"));
            return Integer.parseInt(status.substring(9, 12));
        }
    }

    private static String statusLine(InputStream source) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int count = 0; count < 1024; count++) {
            int value = source.read();
            if (value == -1) throw new IOException("incomplete status line");
            if (value == '\n') return line.toString(StandardCharsets.US_ASCII.name()).replace("\r", "");
            if (value == 0) throw new IOException("invalid status line");
            line.write(value);
        }
        throw new IOException("excessive status line");
    }

    private boolean defaultTrustRejectsCertificate() throws Exception {
        SSLContext platform = SSLContext.getInstance("TLS");
        platform.init(null, null, null);
        try (Socket raw = transport();
             SSLSocket tls = (SSLSocket)platform.getSocketFactory().createSocket(raw, "127.0.0.1", port, true)) {
            tls.setSoTimeout(TIMEOUT_MS);
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls.startHandshake();
            return false;
        } catch (SSLHandshakeException rejected) {
            for (Throwable cause = rejected; cause != null; cause = cause.getCause())
                if (cause instanceof CertificateException || cause instanceof CertPathValidatorException) return true;
            return false;
        }
    }

    private static void require(boolean condition) throws ProbeFailure { if (!condition) throw new ProbeFailure(); }
    private void pass(String message) { evidence.append("PASS: ").append(message).append('\n'); }
    private static final class ProbeFailure extends Exception {}
}
