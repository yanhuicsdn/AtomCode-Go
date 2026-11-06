package io.github.yanhuicsdn.atomcode.runtime;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.net.ssl.KeyManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.security.auth.x500.X500Principal;

/** TLS on loopback -> HTTP on a private filesystem socket. No token injection.
 * WebView pins this run's certificate before making the daemon cookie exchange.
 * A sibling app that later occupies the port cannot authenticate as this host.
 */
public final class LocalGateway implements Closeable {
    private final SSLServerSocket server;
    private final Path unixSocket;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Closeable> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Semaphore capacity = new Semaphore(32);
    private volatile boolean closed;
    public volatile String lastFailure;
    public volatile String lastStage = "not connected";
    public final byte[] certificate;
    public final String origin;

    public LocalGateway(Path unixSocket) throws Exception {
        this.unixSocket = unixSocket;
        String alias = "atomcode-local-tls-ec-v2";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    // Conscrypt hashes the TLS transcript itself, then calls
                    // NONEwithECDSA for non-exportable AndroidKeyStore keys.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=AtomCode Local"))
                    .setCertificateSerialNumber(new BigInteger(128, new SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(new Date(0))
                    .setCertificateNotAfter(new Date(4102444800000L)).build());
            generator.generateKeyPair();
        }
        certificate = ((X509Certificate)store.getCertificate(alias)).getEncoded();
        java.security.PrivateKey privateKey = (java.security.PrivateKey)store.getKey(alias, null);
        java.security.cert.Certificate[] storedChain = store.getCertificateChain(alias);
        X509Certificate[] chain = new X509Certificate[storedChain.length];
        for (int i = 0; i < chain.length; i++) chain[i] = (X509Certificate)storedChain[i];
        // AndroidKeyStore keys are non-exportable. Select the exact key entry
        // explicitly instead of a provider-dependent alias discovery pass.
        KeyManager manager = new X509ExtendedKeyManager() {
            private boolean supports(String type) { return "EC".equals(type); }
            @Override public String[] getClientAliases(String type, java.security.Principal[] issuers) { return null; }
            @Override public String chooseClientAlias(String[] types, java.security.Principal[] issuers, Socket socket) { return null; }
            @Override public String[] getServerAliases(String type, java.security.Principal[] issuers) { return supports(type) ? new String[]{alias} : null; }
            @Override public String chooseServerAlias(String type, java.security.Principal[] issuers, Socket socket) { return supports(type) ? alias : null; }
            @Override public String chooseEngineServerAlias(String type, java.security.Principal[] issuers, SSLEngine engine) { return supports(type) ? alias : null; }
            @Override public X509Certificate[] getCertificateChain(String value) { return alias.equals(value) ? chain.clone() : null; }
            @Override public java.security.PrivateKey getPrivateKey(String value) { return alias.equals(value) ? privateKey : null; }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(new KeyManager[]{manager}, null, new SecureRandom());
        server = (SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        server.setEnabledProtocols(new String[]{"TLSv1.2"});
        origin = "https://127.0.0.1:" + server.getLocalPort();
    }

    public int port() { return server.getLocalPort(); }

    private boolean submit(Runnable task) {
        try { workers.execute(task); return true; }
        catch (java.util.concurrent.RejectedExecutionException stopped) { return false; }
    }

    public void start() {
        submit(() -> {
            while (!closed) {
                try {
                    SSLSocket socket = (SSLSocket)server.accept();
                    if (!capacity.tryAcquire()) { socket.close(); continue; }
                    connections.add(socket);
                    if (!submit(() -> {
                        try { forward(socket); }
                        catch (IOException error) {
                            // Only protocol/socket failures, never request headers,
                            // URLs, cookies or daemon response bodies.
                            lastFailure = lastStage + ": " + error.getClass().getSimpleName() + ": " + error.getMessage();
                        }
                        finally {
                            connections.remove(socket);
                            try { socket.close(); } catch (IOException ignored) {}
                            capacity.release();
                        }
                    })) {
                        connections.remove(socket);
                        capacity.release();
                        socket.close();
                        return;
                    }
                } catch (IOException stopped) { if (closed) return; }
            }
        });
    }

    private void forward(SSLSocket browser) throws IOException {
        lastStage = "handshake; clientMode=" + browser.getUseClientMode();
        browser.setSoTimeout(15000);
        browser.startHandshake();
        lastStage = "HTTP request";
        InputStream incoming = browser.getInputStream();
        OutputStream outgoing = browser.getOutputStream();
        String request = headers(incoming);
        String[] lines = request.split("\r\n");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[1].startsWith("/") || first[1].startsWith("//")
                || !first[2].equals("HTTP/1.1")) { reject(outgoing); return; }
        String expectedHost = "127.0.0.1:" + port();
        StringBuilder forwarded = new StringBuilder(lines[0]).append("\r\n");
        int hosts = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0 || Character.isWhitespace(lines[i].charAt(0))) { reject(outgoing); return; }
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (name.equals("host")) {
                hosts++;
                if (!value.equals(expectedHost)) { reject(outgoing); return; }
            }
            if (name.equals("origin") && !value.equals(origin)) { reject(outgoing); return; }
            if (name.equals("sec-fetch-site") && !value.equals("same-origin") && !value.equals("none")) {
                reject(outgoing); return;
            }
            if (name.equals("connection") || name.equals("keep-alive") || name.startsWith("proxy-")
                    || name.equals("forwarded") || name.startsWith("x-forwarded-")) continue;
            forwarded.append(lines[i]).append("\r\n");
        }
        if (hosts != 1) { reject(outgoing); return; }
        // One HTTP request per TLS connection makes the origin/header checks
        // apply to every request, including after SSE or keep-alive reuse.
        forwarded.append("Connection: close\r\n\r\n");
        LocalSocket guest = new LocalSocket();
        connections.add(guest);
        try {
            guest.connect(new LocalSocketAddress(unixSocket.toString(), LocalSocketAddress.Namespace.FILESYSTEM));
            OutputStream toGuest = guest.getOutputStream();
            toGuest.write(forwarded.toString().getBytes(StandardCharsets.ISO_8859_1));
            toGuest.flush();
            browser.setSoTimeout(0);
            if (!submit(() -> {
                try { copy(incoming, toGuest); guest.shutdownOutput(); }
                catch (IOException ignored) {}
            })) throw new IOException("本机网关已关闭");
            String response = headers(guest.getInputStream());
            for (String line : response.split("\r\n")) {
                if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11)) line += "; Secure";
                outgoing.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            }
            outgoing.write(("Referrer-Policy: no-referrer\r\nCache-Control: no-store\r\nConnection: close\r\n"
                    + "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n"
                    + "Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n"
                    + "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; worker-src 'self' blob:; "
                    + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            outgoing.flush();
            copy(guest.getInputStream(), outgoing);
        } finally {
            connections.remove(guest);
            guest.close();
        }
    }

    private static String headers(InputStream input) throws IOException {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        int state = 0;
        while (value.size() < 65536) {
            int b = input.read();
            if (b < 0) throw new IOException("连接在 HTTP 标头完成前关闭");
            if (b == 0) throw new IOException("HTTP 标头包含无效字符");
            value.write(b);
            state = switch (state) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : (b == '\r' ? 1 : 0);
                case 2 -> b == '\r' ? 3 : 0;
                default -> b == '\n' ? 4 : 0;
            };
            if (state == 4) return value.toString(StandardCharsets.ISO_8859_1.name()).substring(0, value.size() - 4);
        }
        throw new IOException("HTTP 标头过大");
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768];
        int read;
        while ((read = input.read(buffer)) != -1) { output.write(buffer, 0, read); output.flush(); }
    }

    private static void reject(OutputStream output) throws IOException {
        output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        for (Closeable connection : connections) try { connection.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
