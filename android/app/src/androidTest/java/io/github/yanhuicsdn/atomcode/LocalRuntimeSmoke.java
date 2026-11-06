package io.github.yanhuicsdn.atomcode;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeController;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeService;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Device-only smoke runner. No model key, remote engine or test mock involved. */
public class LocalRuntimeSmoke extends Instrumentation {
    protected RuntimeController controller;
    protected final StringBuilder evidence = new StringBuilder();
    private boolean realModel;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        realModel = arguments != null && "true".equals(arguments.getString("real_model"));
        start();
    }
    @Override public void onStart() {
        if (realModel) { new RealModelAcceptance(this).run(); return; }
        Bundle result = new Bundle();
        try {
            controller = RuntimeController.get(getTargetContext());
            Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(RuntimeController.Phase.RUNNING, 180000);
            pass("Activity launch automatically installs/starts bundled Linux/PRoot/daemon");
            String firstToken = Uri.parse(controller.entryUrl()).getQueryParameter("token");
            HttpsURLConnection bootstrap = connect(controller.entryUrl());
            require(bootstrap.getResponseCode() == 302, "cookie exchange must redirect");
            String cookie = bootstrap.getHeaderField("Set-Cookie");
            require(cookie != null && cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Strict"), "cookie flags");
            require("/".equals(bootstrap.getHeaderField("Location")), "credential-free redirect");
            bootstrap.disconnect();
            pass("Pinned TLS and daemon token-to-HttpOnly-cookie exchange");
            HttpsURLConnection wrongIdentity = connect(controller.origin() + "/health", new byte[]{0});
            boolean identityRejected = false;
            try { wrongIdentity.getResponseCode(); }
            catch (javax.net.ssl.SSLException expected) { identityRejected = true; }
            finally { wrongIdentity.disconnect(); }
            require(identityRejected, "a certificate outside the exact local pin must be rejected");
            pass("Incorrect local service certificate rejected");
            HttpsURLConnection unauthenticated = connect(controller.origin() + "/sessions");
            require(unauthenticated.getResponseCode() == 401, "unauthenticated API must be denied");
            unauthenticated.disconnect();
            HttpsURLConnection authenticated = connect(controller.origin() + "/sessions");
            authenticated.setRequestProperty("Cookie", cookie.split(";", 2)[0]);
            require(authenticated.getResponseCode() == 200, "cookie-authenticated sessions API");
            authenticated.disconnect();
            pass("Unauthenticated API rejection and authenticated API access");
            String cookiePair = cookie.split(";", 2)[0];
            String projectName = "android-smoke-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            Path workspace = getTargetContext().getFilesDir().toPath().resolve("home/workspace").resolve(projectName);
            Files.createDirectories(workspace);
            org.json.JSONObject session = api("/sessions", new org.json.JSONObject()
                    .put("working_dir", "/root/workspace/" + projectName).put("title", "Android device smoke"), cookiePair, 201);
            String sessionId = session.getString("id");
            String terminal = api("/workbench/terminals", new org.json.JSONObject().put("session_id", sessionId), cookiePair, 200).getString("id");
            api("/workbench/terminals/" + terminal, new org.json.JSONObject().put("session_id", sessionId)
                    .put("input", "printf 'from-phone\\n' > android-command.txt; git --version > android-git.txt; printf 'terminal-ok\\n'; exit 7\r"), cookiePair, 204);
            boolean exited = false;
            long terminalDeadline = SystemClock.elapsedRealtime() + 30000;
            while (SystemClock.elapsedRealtime() < terminalDeadline) {
                org.json.JSONObject output = api("/workbench/terminals/" + terminal + "?session_id=" + sessionId, null, cookiePair, 200);
                if (output.getBoolean("finished") && !output.isNull("exit_code")) {
                    require(output.getInt("exit_code") == 7, "real shell exit status");
                    exited = true;
                    break;
                }
                SystemClock.sleep(100);
            }
            require(exited, "real PTY shell must exit");
            require(new String(Files.readAllBytes(workspace.resolve("android-command.txt")), StandardCharsets.UTF_8).equals("from-phone\n"), "command changed the actual workspace");
            require(new String(Files.readAllBytes(workspace.resolve("android-git.txt")), StandardCharsets.UTF_8).startsWith("git version"), "bundled Git runs with its Linux dependencies");
            pass("Real Linux PTY command execution, Git and exit status");
            org.json.JSONObject text = api("/workbench/text?session_id=" + sessionId + "&path=android-command.txt", null, cookiePair, 200);
            require(text.getString("content").equals("from-phone\n"), "workspace file API read");
            api("/workbench/text", new org.json.JSONObject().put("session_id", sessionId).put("path", "android-command.txt")
                    .put("revision", text.getString("revision")).put("content", "edited on phone\n"), cookiePair, 200);
            require(new String(Files.readAllBytes(workspace.resolve("android-command.txt")), StandardCharsets.UTF_8).equals("edited on phone\n"), "file API writes real bytes");
            pass("Workspace file read and revision-checked edit");
            HttpsURLConnection foreign = connect(controller.origin() + "/shutdown");
            foreign.setRequestMethod("POST");
            foreign.setRequestProperty("Origin", "https://attacker.example");
            require(foreign.getResponseCode() == 403, "cross-origin control request must be denied");
            foreign.disconnect();
            pass("Foreign-origin request rejection");
            AtomicReference<String> document = new AtomicReference<>();
            boolean rendered = false;
            long deadline = SystemClock.elapsedRealtime() + 30000;
            do {
                CountDownLatch read = new CountDownLatch(1);
                runOnMainSync(() -> {
                    WebView web = findWebView(activity.getWindow().getDecorView());
                    if (web == null) { read.countDown(); return; }
                    web.evaluateJavascript("JSON.stringify({text:document.body.innerText.length,origin:location.origin,query:location.search,cookie:document.cookie})", value -> { document.set(value); read.countDown(); });
                });
                require(read.await(5, TimeUnit.SECONDS), "WebView inspection timed out");
                if (document.get() != null) {
                    try {
                        Object decoded = new org.json.JSONTokener(document.get()).nextValue();
                        if (decoded instanceof String) {
                            org.json.JSONObject page = new org.json.JSONObject((String)decoded);
                            rendered = page.getInt("text") > 20 && page.getString("origin").equals(controller.origin())
                                    && !page.getString("query").contains("token=") && !page.getString("cookie").contains(firstToken);
                        }
                    } catch (org.json.JSONException ignored) {}
                }
                if (rendered) break;
                SystemClock.sleep(250);
            } while (SystemClock.elapsedRealtime() < deadline);
            require(rendered, "WebUI must render real content without exposing the token");
            pass("Actual WebView loading and credential isolation");
            final int[] location = new int[2];
            final int[] webHeight = new int[1];
            runOnMainSync(() -> {
                WebView browser = findWebView(activity.getWindow().getDecorView());
                browser.getLocationOnScreen(location);
                webHeight[0] = browser.getHeight();
            });
            require(location[1] < 160 && webHeight[0] > getTargetContext().getResources().getDisplayMetrics().heightPixels * 0.8,
                    "running WebView must occupy the screen without native runtime controls");
            pass("Fullscreen workspace after automatic startup");
            runOnMainSync(() -> {
                WebView browser = findWebView(activity.getWindow().getDecorView());
                browser.evaluateJavascript("document.querySelector('.model-config-modal .modal-close')?.click()", null);
            });
            SystemClock.sleep(200);
            runOnMainSync(() -> {
                WebView browser = findWebView(activity.getWindow().getDecorView());
                browser.requestFocus();
                browser.evaluateJavascript("document.querySelector('textarea')?.focus()", null);
                getTargetContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .showSoftInput(browser, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
            boolean resizedForKeyboard = false;
            for (int attempt = 0; attempt < 50; attempt++) {
                SystemClock.sleep(100);
                final int[] currentHeight = new int[1];
                runOnMainSync(() -> currentHeight[0] = findWebView(activity.getWindow().getDecorView()).getHeight());
                if (currentHeight[0] < webHeight[0] - 100) { resizedForKeyboard = true; break; }
            }
            require(resizedForKeyboard, "fullscreen workspace must resize for the input method");
            runOnMainSync(() -> {
                WebView browser = findWebView(activity.getWindow().getDecorView());
                getTargetContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .hideSoftInputFromWindow(browser.getWindowToken(), 0);
            });
            pass("Fullscreen workspace resizes for Android keyboard");
            Path probe = workspace.resolve("persistence.txt");
            Files.write(probe, "persistent workspace\n".getBytes(StandardCharsets.UTF_8));
            controller.stop();
            await(RuntimeController.Phase.STOPPED, 15000);
            pass("Explicit process stop");
            getTargetContext().startForegroundService(new Intent(getTargetContext(), RuntimeService.class));
            await(RuntimeController.Phase.RUNNING, 120000);
            String nextToken = Uri.parse(controller.entryUrl()).getQueryParameter("token");
            require(!firstToken.equals(nextToken), "restart must rotate authentication");
            require(new String(Files.readAllBytes(probe), StandardCharsets.UTF_8).equals("persistent workspace\n"), "workspace survived restart");
            // The normal catalog intentionally hides empty conversations.
            // Resolve the created native session directly instead of treating
            // that UI visibility policy as a storage failure.
            HttpsURLConnection catalog = connect(controller.origin() + "/sessions/resolve/" + sessionId);
            catalog.setRequestProperty("Authorization", "Bearer " + nextToken);
            require(catalog.getResponseCode() == 200, "resumed session catalog");
            String sessions;
            try (java.io.InputStream input = catalog.getInputStream()) {
                sessions = io.github.yanhuicsdn.atomcode.runtime.RuntimeManifest.readText(input, 1024 * 1024);
            }
            require(sessions.contains(sessionId), "native session metadata survives restart");
            catalog.disconnect();
            HttpsURLConnection old = connect(controller.origin() + "/sessions");
            old.setRequestProperty("Authorization", "Bearer " + firstToken);
            require(old.getResponseCode() == 401, "previous-run token must be rejected");
            old.disconnect();
            pass("Restart, token rotation and workspace persistence");
            result.putString("stream", evidence.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Never print connection URLs, cookie values or tokens in evidence.
            StringBuilder causes = new StringBuilder();
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause())
                causes.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage()).append('\n');
            result.putString("stream", evidence + "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n" + causes
                    + "\nGateway: " + (controller == null ? "not started" : controller.gatewayFailure())
                    + "\nRuntime: " + (controller == null ? "not started" : controller.phase + ": " + controller.message) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    protected void await(RuntimeController.Phase target, long timeout) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (controller.phase == target) return;
            if (controller.phase == RuntimeController.Phase.ERROR) throw new IOException(controller.message);
            SystemClock.sleep(100);
        }
        throw new IOException("runtime state timeout: " + target + ", actual " + controller.phase);
    }

    protected HttpsURLConnection connect(String url) throws Exception {
        return connect(url, controller.certificate());
    }

    private HttpsURLConnection connect(String url, byte[] pinned) throws Exception {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("not a client CA"); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain.length == 0 || !Arrays.equals(chain[0].getEncoded(), pinned)) throw new CertificateException("local certificate mismatch");
            }
        }}, null);
        HttpsURLConnection connection = (HttpsURLConnection)new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        connection.setSSLSocketFactory(tls.getSocketFactory());
        connection.setHostnameVerifier((host, session) -> host.equals("127.0.0.1"));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        return connection;
    }

    protected org.json.JSONObject api(String path, org.json.JSONObject body, String cookie, int expected) throws Exception {
        HttpsURLConnection connection = connect(controller.origin() + path);
        try {
            connection.setRequestProperty("Cookie", cookie);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            require(status == expected, "API " + path + " returned " + status + ", expected " + expected);
            if (status == 204) return new org.json.JSONObject();
            try (java.io.InputStream input = connection.getInputStream()) {
                return new org.json.JSONObject(io.github.yanhuicsdn.atomcode.runtime.RuntimeManifest.readText(input, 1024 * 1024));
            }
        } finally { connection.disconnect(); }
    }

    private static WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView)root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) { WebView found = findWebView(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    protected static void require(boolean condition, String reason) throws IOException { if (!condition) throw new IOException(reason); }
    protected void pass(String value) { evidence.append("PASS: ").append(value).append('\n'); }
}
