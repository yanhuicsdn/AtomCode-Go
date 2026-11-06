package io.github.yanhuicsdn.atomcode;

import android.app.Activity;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeController;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeFiles;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeManifest;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Holds the real owner runtime while a separate UID probes its public boundary. */
final class SecurityLeaseAcceptance {
    static final String ENDPOINT = "security-acceptance-endpoint.json";
    static final String STOP = "security-acceptance-stop";
    private final LocalRuntimeSmoke host;
    private final String leaseId;
    SecurityLeaseAcceptance(LocalRuntimeSmoke host, String leaseId) {
        this.host = host;
        this.leaseId = leaseId == null ? UUID.randomUUID().toString() : leaseId;
    }

    void run() {
        Bundle result = new Bundle();
        String stage = "start owner";
        boolean success = false;
        Path endpoint = host.getTargetContext().getCacheDir().toPath().resolve(ENDPOINT);
        Path stop = host.getTargetContext().getCacheDir().toPath().resolve(STOP);
        try {
            Files.deleteIfExists(endpoint);
            Files.deleteIfExists(stop);
            host.controller = RuntimeController.get(host.getTargetContext());
            host.getTargetContext().startForegroundService(new Intent(host.getTargetContext(), RuntimeService.class));
            host.await(RuntimeController.Phase.RUNNING, 180000);
            java.lang.reflect.Field run = RuntimeController.class.getDeclaredField("run");
            run.setAccessible(true);
            Path uds = ((Path)run.get(host.controller)).resolve("s.sock");
            stage = "owner validation before probe";
            validateOwner(uds);
            host.pass("Owner authenticates successfully and reaches its live private socket before external probe");
            // This descriptor deliberately contains no credential or bootstrap URL.
            JSONObject descriptor = new JSONObject().put("lease_id", leaseId)
                    .put("owner_uid", android.os.Process.myUid())
                    .put("port", Uri.parse(host.controller.origin()).getPort())
                    .put("uds_path", uds.toString())
                    .put("expected_certificate_sha256", RuntimeFiles.sha256(new ByteArrayInputStream(host.controller.certificate())));
            Files.write(endpoint, descriptor.toString().getBytes(StandardCharsets.UTF_8));
            stage = "wait for separate UID probe";
            long deadline = SystemClock.elapsedRealtime() + 180000;
            while (!Files.exists(stop) && SystemClock.elapsedRealtime() < deadline) {
                LocalRuntimeSmoke.require(host.controller.phase == RuntimeController.Phase.RUNNING, "owner must stay running during probe");
                SystemClock.sleep(100);
            }
            LocalRuntimeSmoke.require(Files.exists(stop), "probe completion signal must arrive");
            stage = "owner validation after probe";
            validateOwner(uds);
            host.pass("Owner remains authenticated and healthy after external probes");
            success = true;
        } catch (Throwable failure) {
            host.evidence.append("FAIL: ").append(stage).append("/").append(failure.getClass().getSimpleName()).append('\n');
        } finally {
            try {
                if (host.controller != null) {
                    host.controller.stop();
                    host.await(RuntimeController.Phase.STOPPED, 20000);
                }
                Files.deleteIfExists(endpoint);
                Files.deleteIfExists(stop);
            } catch (Throwable failure) {
                success = false;
                host.evidence.append("FAIL: owner lease cleanup/").append(failure.getClass().getSimpleName()).append('\n');
            }
        }
        result.putString("stream", host.evidence.toString());
        host.finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void validateOwner(Path uds) throws Exception {
        String token = Uri.parse(host.controller.entryUrl()).getQueryParameter("token");
        HttpsURLConnection connection = host.connect(host.controller.origin() + "/sessions");
        try {
            connection.setRequestProperty("Authorization", "Bearer " + token);
            LocalRuntimeSmoke.require(connection.getResponseCode() == 200, "owner API must authenticate");
        } finally { connection.disconnect(); }
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(uds.toString(), LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response = RuntimeManifest.readText(socket.getInputStream(), 65536);
            LocalRuntimeSmoke.require(response.startsWith("HTTP/1.1 200 "), "owner socket must be alive");
        }
        boolean markerParentFound = false;
        String markerArgument = "ATOMCODE_ANDROID_RUNTIME=" + host.getTargetContext().getFilesDir().getCanonicalPath();
        String expectedParent = host.getTargetContext().getApplicationInfo().nativeLibraryDir + "/libproot.so";
        java.io.File[] processes = new java.io.File("/proc").listFiles();
        LocalRuntimeSmoke.require(processes != null, "owner process inspection must be available");
        for (java.io.File directory : processes) {
            if (!directory.getName().matches("[0-9]+")) continue;
            try {
                if (android.system.Os.stat(directory.toString()).st_uid != android.os.Process.myUid()) continue;
                String command = new String(Files.readAllBytes(directory.toPath().resolve("cmdline")), StandardCharsets.UTF_8);
                if (!command.split("\0", 2)[0].equals(expectedParent) || !command.contains(markerArgument)) continue;
                markerParentFound = true;
                LocalRuntimeSmoke.require(!command.contains("ATOMCODE_DAEMON_TOKEN="), "daemon credential must not be present in long-lived process arguments");
            } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException unavailableEntry) {
                // This read-only assertion does not infer process death or kill
                // anything. A positive inspection of the actual parent below
                // is still mandatory; unrelated inaccessible entries add no proof.
            } catch (android.system.ErrnoException notInspectable) {
                if (notInspectable.errno != android.system.OsConstants.ENOENT
                        && notInspectable.errno != android.system.OsConstants.ESRCH
                        && notInspectable.errno != android.system.OsConstants.EACCES) throw notInspectable;
            }
        }
        LocalRuntimeSmoke.require(markerParentFound, "the live marked runtime parent must have been inspected");
    }
}
