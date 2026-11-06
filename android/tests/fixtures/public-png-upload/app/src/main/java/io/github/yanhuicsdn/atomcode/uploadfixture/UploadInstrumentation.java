package io.github.yanhuicsdn.atomcode.uploadfixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Real Chromium FileReader, synthetic picker result. No DocumentsUI/provider UI. */
public final class UploadInstrumentation extends Instrumentation {
    private static final long LIMIT_MS = 180000;
    private String confirmation;
    private boolean revokedOnly;
    private final String id = "atomcode-public-upload-" + UUID.randomUUID().toString().replace("-", "");
    private final StringBuilder evidence = new StringBuilder();
    private Activity activity;
    private WebView web;
    private long deadline;
    private static final class CheckFailure extends RuntimeException {}

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        confirmation = arguments == null ? null : arguments.getString("fixture_confirmation");
        revokedOnly = arguments != null && "true".equals(arguments.getString("check_revoked"));
        start();
    }
    private void check(boolean value) { if (!value) throw new CheckFailure(); }
    private void budget() { check(SystemClock.elapsedRealtime() < deadline); }
    private void pass(String label) { evidence.append("PASS: ").append(label).append('\n'); }
    private interface MainAction { void run() throws Exception; }
    private void main(MainAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() instanceof Exception) throw (Exception)failure.get();
        if (failure.get() != null) throw new CheckFailure();
    }
    private static WebView find(View view) {
        if (view instanceof WebView) return (WebView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            WebView found = find(((ViewGroup)view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private JSONObject javascript(String expression) throws Exception {
        budget();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        main(() -> {
            check(web != null && web == find(activity.getWindow().getDecorView()));
            web.evaluateJavascript(expression, result -> { value.set(result); latch.countDown(); });
        });
        check(latch.await(Math.max(1, Math.min(5000, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS));
        Object decoded = new JSONTokener(value.get()).nextValue();
        check(decoded instanceof String);
        return new JSONObject((String)decoded);
    }
    private void ready() throws Exception {
        AtomicBoolean available = new AtomicBoolean();
        long stable = 0;
        WebView previous = null;
        while (true) {
            budget();
            main(() -> {
                Field field = activity.getClass().getDeclaredField("controller");
                field.setAccessible(true);
                Object controller = field.get(activity);
                Object phase = controller.getClass().getField("phase").get(controller);
                web = find(activity.getWindow().getDecorView());
                available.set("RUNNING".equals(phase.toString()) && web != null
                        && web.getVisibility() == View.VISIBLE && web.getProgress() == 100 && activity.hasWindowFocus());
            });
            if (available.get() && web == previous) {
                if (stable == 0) stable = SystemClock.elapsedRealtime();
                if (SystemClock.elapsedRealtime() - stable >= 750) return;
            } else stable = 0;
            previous = web;
            SystemClock.sleep(100);
        }
    }
    private void action(String action) throws Exception {
        main(() -> getTargetContext().startActivity(new Intent(action)
                .setComponent(new ComponentName(Canaries.FIXTURE, Canaries.FIXTURE + ".GrantActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }
    private void permissions(int ownerUid, boolean granted) throws Exception {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 10000);
        while (true) {
            budget();
            boolean matched = true;
            for (Uri uri : Canaries.URIS) {
                int read = getTargetContext().checkUriPermission(uri, Process.myPid(), ownerUid, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                int write = getTargetContext().checkUriPermission(uri, Process.myPid(), ownerUid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                check(write == PackageManager.PERMISSION_DENIED);
                matched &= read == (granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
            }
            if (matched) return;
            check(SystemClock.elapsedRealtime() < until);
            SystemClock.sleep(100);
        }
    }
    private void proveReadDenied() throws Exception {
        for (Uri uri : Canaries.URIS) {
            boolean denied = false;
            try (ParcelFileDescriptor ignored = getTargetContext().getContentResolver().openFileDescriptor(uri, "r")) { }
            catch (SecurityException expected) { denied = true; }
            check(denied);
        }
    }
    private String installInput() throws Exception {
        // Read only our test input, its geometry and its resulting File objects.
        // Never inspect location, cookies, storage, application DOM or configuration.
        return "JSON.stringify((()=>{if(!document.body)return {state:'no-body'};"
                + "let e=document.getElementById(" + JSONObject.quote(id) + ");if(e)e.remove();"
                + "e=document.createElement('input');e.id=" + JSONObject.quote(id) + ";e.type='file';e.multiple=true;e.accept='image/*';"
                + "e.style.cssText='position:fixed;left:16px;top:48px;width:240px;height:72px;z-index:2147483647;background:white;color:black;opacity:1';"
                + "e.dataset.publicUpload=JSON.stringify({state:'waiting'});"
                + "e.onchange=async()=>{const fs=Array.from(e.files||[]);"
                + "if(fs.length!==2||fs.some((f,i)=>f.name!==['one.png','two.png'][i]||f.size!==70||f.type!=='image/png'))"
                + "{e.dataset.publicUpload=JSON.stringify({state:'unexpected-selection-metadata'});return;}"
                + "try{const rows=await Promise.all(fs.map(f=>new Promise((resolve,reject)=>{const r=new FileReader();"
                + "r.onerror=()=>reject();r.onload=()=>{const bytes=new Uint8Array(r.result);"
                + "resolve({name:f.name,size:f.size,type:f.type,base64:btoa(String.fromCharCode(...bytes))});};r.readAsArrayBuffer(f);})));"
                + "e.dataset.publicUpload=JSON.stringify({state:'done',files:rows});}"
                + "catch(_){e.dataset.publicUpload=JSON.stringify({state:'reader-error'});}};document.body.appendChild(e);return {state:'installed'};})())";
    }
    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + LIMIT_MS;
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("mode", revokedOnly ? "revoked" : "file-reader");
        report.put("scope", "real cross-UID public PNG FileReader with intercepted document-picker result; not actual DocumentsUI selection or model upload");
        report.put("modelRequestsSent", false);
        report.put("mainAppConfigOrTokenRead", false);
        report.put("limitMilliseconds", LIMIT_MS);
        String stage = "fixture-guard";
        boolean success = false;
        boolean grantMayExist = false;
        int ownerUid = -1;
        ActivityMonitor monitor = null;
        try {
            check(Canaries.CONFIRMATION.equals(confirmation) && "ranchu".equals(Build.HARDWARE));
            check(Canaries.OWNER.equals(getTargetContext().getPackageName()));
            PackageManager pm = getTargetContext().getPackageManager();
            ownerUid = pm.getApplicationInfo(Canaries.OWNER, 0).uid;
            int fixtureUid = pm.getApplicationInfo(Canaries.FIXTURE, 0).uid;
            ProviderInfo provider = pm.resolveContentProvider(Canaries.AUTHORITY, 0);
            check(Process.myUid() == ownerUid && ownerUid != fixtureUid
                    && pm.checkSignatures(Canaries.OWNER, Canaries.FIXTURE) == PackageManager.SIGNATURE_MATCH
                    && provider != null && provider.applicationInfo.uid == fixtureUid && !provider.exported && provider.grantUriPermissions);
            report.put("ownerUid", ownerUid);
            report.put("providerUid", fixtureUid);
            report.put("distinctUids", true);
            if (revokedOnly) {
                stage = "verify-revoked-permissions";
                permissions(ownerUid, false);
                proveReadDenied();
                pass("Both fixed public PNG URIs deny target-UID access after explicit revocation");
                success = true;
            } else {
                stage = "real-main-activity-startup";
                activity = startActivitySync(new Intent().setComponent(new ComponentName(Canaries.OWNER, Canaries.OWNER + ".MainActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                ready();
                AtomicBoolean contentAccess = new AtomicBoolean();
                main(() -> contentAccess.set(web.getSettings().getAllowContentAccess()));
                report.put("productionAllowContentAccess", contentAccess.get());
                check(!contentAccess.get()); // Observe production policy; never change it for this test.
                stage = "fixture-uid-grant-in-current-target-process";
                grantMayExist = true;
                action(GrantActivity.GRANT);
                permissions(ownerUid, true);
                ready();
                pass("Instrumentation target and explicitly granted read-only PNG provider have distinct UIDs");
                AtomicInteger pickerCalls = new AtomicInteger();
                AtomicBoolean pickerValid = new AtomicBoolean();
                monitor = new ActivityMonitor() {
                    @Override public ActivityResult onStartActivity(Intent intent) {
                        if (!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) return null;
                        int count = pickerCalls.incrementAndGet();
                        pickerValid.set(count == 1 && intent.hasCategory(Intent.CATEGORY_OPENABLE)
                                && "image/*".equals(intent.getType()) && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                                && intent.getData() == null && (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                                && (intent.getFlags() & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) == 0);
                        if (!pickerValid.get()) return new ActivityResult(Activity.RESULT_CANCELED, null);
                        ClipData clip = ClipData.newRawUri("fixed public PNGs", Canaries.URIS[0]);
                        clip.addItem(new ClipData.Item(Canaries.URIS[1]));
                        Intent selected = new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        selected.setClipData(clip);
                        return new ActivityResult(Activity.RESULT_OK, selected);
                    }
                };
                addMonitor(monitor);
                stage = "install-test-input-and-real-tap";
                check("installed".equals(javascript(installInput()).getString("state")));
                SystemClock.sleep(200);
                JSONObject bounds = javascript("JSON.stringify((()=>{const e=document.getElementById(" + JSONObject.quote(id)
                        + ");if(!e)return {missing:true};const r=e.getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height,viewportWidth:innerWidth};})())");
                check(!bounds.optBoolean("missing") && bounds.getDouble("width") > 10 && bounds.getDouble("height") > 10);
                Point target = new Point();
                main(() -> {
                    int[] at = new int[2]; web.getLocationOnScreen(at);
                    double ratio = web.getWidth() / bounds.getDouble("viewportWidth");
                    target.set(at[0] + (int)((bounds.getDouble("left") + bounds.getDouble("width") / 2) * ratio),
                            at[1] + (int)((bounds.getDouble("top") + bounds.getDouble("height") / 2) * ratio));
                    check(target.x >= at[0] && target.x < at[0] + web.getWidth()
                            && target.y >= at[1] && target.y < at[1] + web.getHeight() && activity.hasWindowFocus());
                });
                long down = SystemClock.uptimeMillis();
                MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, target.x, target.y, 0);
                MotionEvent release = MotionEvent.obtain(down, down + 100, MotionEvent.ACTION_UP, target.x, target.y, 0);
                try { sendPointerSync(press); SystemClock.sleep(100); sendPointerSync(release); }
                finally { press.recycle(); release.recycle(); }
                stage = "actual-file-reader-bytes";
                long readDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 30000);
                JSONObject result;
                do {
                    budget();
                    result = javascript("JSON.stringify((()=>{const e=document.getElementById(" + JSONObject.quote(id)
                            + ");return e?JSON.parse(e.dataset.publicUpload):{state:'input-gone'};})())");
                    if (!"waiting".equals(result.getString("state"))) break;
                    SystemClock.sleep(100);
                } while (SystemClock.elapsedRealtime() < readDeadline);
                check(pickerCalls.get() == 1 && pickerValid.get());
                pass("Real DOM tap requests multiple image files through the production OPEN_DOCUMENT adapter");
                check("done".equals(result.getString("state")));
                JSONArray files = result.getJSONArray("files");
                check(files.length() == 2);
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    String base64 = file.getString("base64");
                    check(Canaries.NAMES[i].equals(file.getString("name")) && file.getInt("size") == 70
                            && "image/png".equals(file.getString("type")) && Canaries.BASE64[i].equals(base64)
                            && Arrays.equals(Base64.decode(base64, Base64.DEFAULT), Canaries.bytes(i)));
                }
                report.put("actualFileReaderFiles", 2);
                report.put("bytesPerFile", 70);
                report.put("fixedBase64AndBytesMatched", true);
                pass("Real FileReader returns both fixed PNG byte sequences through granted cross-UID content URIs");
                success = true;
            }
        } catch (Throwable failure) {
            report.put("failureStage", stage);
            report.put("failureClass", failure.getClass().getSimpleName());
            evidence.append("FAIL: upload-fixture/").append(stage).append('/').append(failure.getClass().getSimpleName()).append('\n');
        } finally {
            if (monitor != null) removeMonitor(monitor);
            if (grantMayExist) {
                try {
                    action(GrantActivity.REVOKE);
                    permissions(ownerUid, false);
                    proveReadDenied();
                    report.put("sameProcessRevocationVerified", true);
                    if (success) pass("Explicit fixture-UID revocation denies subsequent reads in the same target process");
                } catch (Throwable failure) {
                    success = false;
                    report.put("revocationFailureClass", failure.getClass().getSimpleName());
                    evidence.append("FAIL: upload-fixture/revoke/").append(failure.getClass().getSimpleName()).append('\n');
                }
            }
            if (activity != null) {
                try {
                    if (web != null)
                        javascript("JSON.stringify((()=>{const e=document.getElementById(" + JSONObject.quote(id) + ");if(e)e.remove();return {removed:true};})())");
                    report.put("testDomCleanupCompleted", true);
                    if (success) pass("Test input is removed without accessing main-app files or credentials");
                } catch (Throwable failure) {
                    success = false;
                    report.put("cleanupFailureClass", failure.getClass().getSimpleName());
                    evidence.append("FAIL: upload-fixture/dom-cleanup/").append(failure.getClass().getSimpleName()).append('\n');
                } finally {
                    try { main(() -> activity.finish()); }
                    catch (Throwable failure) { success = false; report.put("activityFinishFailureClass", failure.getClass().getSimpleName()); }
                }
            }
        }
        report.put("status", success ? "passed" : "not-passed");
        evidence.append("UPLOAD_FIXTURE_RESULT: ").append(new JSONObject(report)).append('\n');
        Bundle result = new Bundle(); result.putString("stream", evidence.toString());
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
