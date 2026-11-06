package io.github.yanhuicsdn.atomcode;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeController;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic Android chooser-adapter checks; no provider, file read or model request. */
final class FileChooserAcceptance {
    private static final Uri ONE = Uri.parse("content://atomcode.acceptance/one");
    private static final Uri TWO = Uri.parse("content://atomcode.acceptance/two");
    private final LocalRuntimeSmoke host;
    private final List<Intent> pickers = new ArrayList<>();
    private MainActivity activity;
    private WebView web;

    FileChooserAcceptance(LocalRuntimeSmoke host) { this.host = host; }

    void run() {
        String stage = "startup";
        boolean success = false;
        Instrumentation.ActivityMonitor observer = null;
        Instrumentation.ActivityMonitor blocker = null;
        Recreation recreation = null;
        Application application = (Application)host.getTargetContext().getApplicationContext();
        try {
            host.controller = RuntimeController.get(host.getTargetContext());
            activity = (MainActivity)host.startActivitySync(new Intent(host.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            host.await(RuntimeController.Phase.RUNNING, 180000);
            ready();
            observer = new Instrumentation.ActivityMonitor() {
                @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    if (Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) pickers.add(new Intent(intent));
                    return null;
                }
            };
            host.addMonitor(observer);
            IntentFilter filter = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
            filter.addCategory(Intent.CATEGORY_OPENABLE);
            filter.addDataType("*/*");
            // A blocking monitor with a null result leaves completion under the
            // test's control. Neither DocumentsUI nor a content provider opens.
            blocker = host.addMonitor(filter, null, true);

            stage = "intent-and-multiple";
            onMain(() -> {
                Capture capture = show(params(true, "image/png", "image/jpeg"));
                Intent picker = pickers.get(pickers.size() - 1);
                check(Intent.ACTION_OPEN_DOCUMENT.equals(picker.getAction())
                        && picker.hasCategory(Intent.CATEGORY_OPENABLE)
                        && "*/*".equals(picker.getType())
                        && Arrays.equals(picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES), new String[]{"image/png", "image/jpeg"})
                        && picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
                check(picker.getFlags() == Intent.FLAG_GRANT_READ_URI_PERMISSION && picker.getData() == null);
                Intent result = new Intent().setData(ONE);
                result.setClipData(ClipData.newRawUri("synthetic", ONE));
                result.getClipData().addItem(new ClipData.Item(TWO));
                deliver(pending().code, Activity.RESULT_OK, result);
                capture.expect(ONE, TWO);
                Capture extension = show(new Params(false, new String[]{".patch"}, "text/x-patch"));
                check("text/x-patch".equals(pickers.get(pickers.size() - 1).getType()));
                deliver(pending().code, Activity.RESULT_CANCELED, null);
                extension.expectNull();
                Capture mixed = show(new Params(false, new String[]{"image/png", ".patch"}, "text/x-patch"));
                check(Arrays.equals(pickers.get(pickers.size() - 1).getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
                        new String[]{"image/png", "text/x-patch"}));
                deliver(pending().code, Activity.RESULT_CANCELED, null);
                mixed.expectNull();
            });
            host.pass("Chooser honors open/multiple MIME hints and deduplicates synthetic content selections");

            stage = "single-invalid-cancel";
            onMain(() -> {
                Capture single = show(params(false, "image/*"));
                Intent picker = pickers.get(pickers.size() - 1);
                check("image/*".equals(picker.getType()) && !picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true));
                deliver(pending().code, Activity.RESULT_OK, new Intent().setData(ONE));
                single.expect(ONE);
                for (String invalid : new String[]{"file:///synthetic", "content:opaque", "content:///missing-authority",
                        "content://atomcode.acceptance/bad%ZZ", "content://name@authority/one", "content://2147483648@authority/one"}) {
                    Capture rejected = show(params(true, "*/*"));
                    Intent mixed = new Intent().setData(ONE);
                    mixed.setClipData(ClipData.newRawUri("synthetic", Uri.parse(invalid)));
                    deliver(pending().code, Activity.RESULT_OK, mixed);
                    rejected.expectNull();
                }
                Capture crossUser = show(params(false, "*/*"));
                Uri uri = Uri.parse("content://10@atomcode.acceptance/one");
                deliver(pending().code, Activity.RESULT_OK, new Intent().setData(uri));
                crossUser.expect(uri);
                Capture tooMany = show(params(false, "*/*"));
                ClipData clip = ClipData.newRawUri("synthetic", ONE);
                clip.addItem(new ClipData.Item(TWO));
                Intent multi = new Intent();
                multi.setClipData(clip);
                deliver(pending().code, Activity.RESULT_OK, multi);
                tooMany.expectNull();
                Capture noUri = show(params(true, "*/*"));
                Intent textOnly = new Intent().setData(ONE);
                textOnly.setClipData(ClipData.newPlainText("synthetic", "not a URI"));
                deliver(pending().code, Activity.RESULT_OK, textOnly);
                noUri.expectNull();
                Capture canceled = show(params(false, "*/*"));
                int code = pending().code;
                deliver(code, Activity.RESULT_CANCELED, new Intent().setData(ONE));
                deliver(code, Activity.RESULT_OK, new Intent().setData(ONE));
                canceled.expectNull();
                int launches = pickers.size();
                Capture unsupported = new Capture();
                check(web.getWebChromeClient().onShowFileChooser(web, unsupported, new Params(false, new String[]{"*/*"}, "*/*") {
                    @Override public int getMode() { return MODE_SAVE; }
                }));
                unsupported.expectNull();
                WebView foreign = new WebView(activity);
                try {
                    Capture wrongView = new Capture();
                    check(web.getWebChromeClient().onShowFileChooser(foreign, wrongView, params(false, "*/*")));
                    wrongView.expectNull();
                } finally { foreign.destroy(); }
                check(pickers.size() == launches);
            });
            host.pass("Single selection, cancellation, malformed/non-content results and untrusted chooser owners fail closed");

            stage = "replacement-stale-result-reentry";
            onMain(() -> {
                Capture first = show(params(false, "*/*"));
                int oldCode = pending().code;
                Capture second = show(params(false, "*/*"));
                int secondCode = pending().code;
                check(secondCode > oldCode && secondCode <= 0xffff);
                first.expectNull();
                deliver(oldCode, Activity.RESULT_OK, new Intent().setData(ONE));
                check(second.calls == 0 && pending().code == secondCode);
                deliver(secondCode, Activity.RESULT_OK, new Intent().setData(TWO));
                second.expect(TWO);
                Capture reentrant = show(params(false, "*/*"));
                AtomicReference<Capture> nested = new AtomicReference<>();
                reentrant.onNull = () -> nested.set(show(params(false, "*/*")));
                int launches = pickers.size();
                Capture displaced = show(params(false, "*/*"));
                reentrant.expectNull();
                displaced.expectNull();
                check(nested.get() != null && nested.get().calls == 0 && pickers.size() == launches + 1);
                deliver(pending().code, Activity.RESULT_OK, new Intent().setData(ONE));
                nested.get().expect(ONE);
                int next = (int)field("nextUploadCode").get(activity);
                field("nextUploadCode").setInt(activity, FileChooserRequest.LAST_CODE + 1);
                launches = pickers.size();
                Capture exhausted = show(params(false, "*/*"));
                exhausted.expectNull();
                check(pending() == null && pickers.size() == launches);
                field("nextUploadCode").setInt(activity, next); // No code was consumed by the refused request.
            });
            host.pass("Replacement/reentry retires each callback once and stale request codes cannot complete a successor");

            stage = "activity-recreation";
            AtomicReference<Capture> destroyed = new AtomicReference<>();
            int[] destroyedCode = new int[1];
            onMain(() -> {
                destroyed.set(show(params(false, "*/*")));
                destroyedCode[0] = pending().code;
                Bundle saved = new Bundle();
                activity.onSaveInstanceState(saved);
                int next = (int)field("nextUploadCode").get(activity);
                int launches = pickers.size();
                Capture afterSave = show(params(false, "*/*"));
                afterSave.expectNull();
                check(destroyed.get().calls == 0 && pending().code == destroyedCode[0]
                        && pickers.size() == launches && (int)field("nextUploadCode").get(activity) == next);
            });
            MainActivity previous = activity;
            recreation = new Recreation(previous);
            application.registerActivityLifecycleCallbacks(recreation);
            onMain(previous::recreate);
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (recreation.resumed == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            check(recreation.resumed != null);
            activity = recreation.resumed;
            ready();
            onMain(() -> {
                destroyed.get().expectNull();
                check(previous.isDestroyed() && !(boolean)field("listening").get(previous));
                Capture fresh = show(params(false, "*/*"));
                int code = pending().code;
                check(code > destroyedCode[0]);
                deliver(destroyedCode[0], Activity.RESULT_OK, new Intent().setData(ONE));
                check(fresh.calls == 0);
                deliver(code, Activity.RESULT_OK, new Intent().setData(TWO));
                fresh.expect(TWO);
            });
            host.pass("Activity destruction cancels once and saved request codes isolate late results after recreation");

            stage = "main-frame-error-reentry";
            AtomicReference<Capture> errored = new AtomicReference<>();
            AtomicReference<Capture> retried = new AtomicReference<>();
            AtomicReference<WebView> retiredView = new AtomicReference<>();
            AtomicReference<WebViewClient> retiredClient = new AtomicReference<>();
            AtomicReference<WebChromeClient> retiredChrome = new AtomicReference<>();
            onMain(() -> {
                retiredView.set(web);
                retiredClient.set(web.getWebViewClient());
                retiredChrome.set(web.getWebChromeClient());
                errored.set(show(params(false, "*/*")));
                errored.get().onNull = () -> retried.set(show(params(false, "*/*")));
            });
            onMain(() -> {
                int launches = pickers.size();
                String origin = host.controller.origin();
                long oldLoad = (long)field("webLoadRequest").get(activity);
                web.getWebViewClient().onReceivedError(web, request(origin, false), null);
                check(errored.get().calls == 0 && pending() != null && (boolean)field("listening").get(activity));
                web.getWebViewClient().onReceivedError(web, request(origin, true), null);
                errored.get().expectNull();
                check(retried.get() != null);
                retried.get().expectNull();
                check(pending() == null && pickers.size() == launches && (boolean)field("listening").get(activity));
                Method load = MainActivity.class.getDeclaredMethod("loadWorkspace", WebView.class, String.class, long.class, long.class);
                load.setAccessible(true);
                load.invoke(activity, web, origin, host.controller.connectionGeneration(), oldLoad);
                check((boolean)field("workspaceConnectionFailed").get(activity));
                activity.changed();
                Capture stillFailed = show(params(false, "*/*"));
                stillFailed.expectNull();
                check(web.getVisibility() == View.GONE && pickers.size() == launches);
            });
            host.pass("Main-frame failure blocks callback reentry and same-origin redisplay; subframe errors retain selection");
            background(false);
            long previousGeneration = host.controller.connectionGeneration();
            host.controller.stop();
            host.await(RuntimeController.Phase.STOPPED, 20000);
            foreground();
            host.getTargetContext().startForegroundService(new Intent(host.getTargetContext(), RuntimeService.class));
            host.await(RuntimeController.Phase.RUNNING, 180000);
            ready();
            onMain(() -> check(!(boolean)field("workspaceConnectionFailed").get(activity)));

            stage = "retired-view-generation-isolation";
            onMain(() -> {
                long current = host.controller.connectionGeneration();
                check(current > previousGeneration && web != retiredView.get()
                        && (long)field("loadedGeneration").get(activity) == current);
                String origin = host.controller.origin();
                Capture currentSelection = show(params(false, "*/*"));
                int currentCode = pending().code;
                int launches = pickers.size();
                // Invoke the actual clients belonging to the retired view.
                // Even a URL shaped like the current connection must not
                // relabel this old callback or disturb its successor.
                retiredClient.get().onReceivedError(retiredView.get(), request(origin, true), null);
                Capture retiredSelection = new Capture();
                check(retiredChrome.get().onShowFileChooser(retiredView.get(), retiredSelection, params(false, "*/*")));
                retiredSelection.expectNull();
                check(currentSelection.calls == 0 && pending().code == currentCode && pickers.size() == launches
                        && web.getVisibility() == View.VISIBLE && !(boolean)field("workspaceConnectionFailed").get(activity));
                check(retiredClient.get().shouldOverrideUrlLoading(retiredView.get(), request(origin, true)));
                activity.changed();
                activity.changed();
                check(web.getVisibility() == View.VISIBLE && currentSelection.calls == 0);
                Method load = MainActivity.class.getDeclaredMethod("loadWorkspace", WebView.class, String.class, long.class, long.class);
                load.setAccessible(true);
                long attempt = (long)field("webLoadRequest").get(activity);
                load.invoke(activity, retiredView.get(), origin, previousGeneration, attempt);
                load.invoke(activity, web, origin, previousGeneration, attempt);
                check(!(boolean)field("workspaceConnectionFailed").get(activity)
                        && (long)field("loadedGeneration").get(activity) == current);
                deliver(currentCode, Activity.RESULT_OK, new Intent().setData(TWO));
                currentSelection.expect(TWO);
                // This adapter fixture models equal origin text with a stale
                // generation; the actual stop/start above supplies that value.
                int code = (int)field("nextUploadCode").get(activity);
                field("nextUploadCode").setInt(activity, code + 1);
                Capture stale = new Capture();
                field("upload").set(activity, new FileChooserRequest(web, origin, previousGeneration, code, stale, params(false, "*/*")));
                deliver(code, Activity.RESULT_OK, new Intent().setData(ONE));
                stale.expectNull();
                check(pending() == null);
            });
            check(retiredClient.get().shouldInterceptRequest(retiredView.get(), request(host.controller.origin(), true)).getStatusCode() == 403);
            ready();
            host.pass("Real stop/start remount rejects retired WebView/client events and stale selection/cookie completions");

            stage = "background-origin-change";
            AtomicReference<Capture> stopped = new AtomicReference<>();
            onMain(() -> stopped.set(show(params(false, "*/*"))));
            background(true);
            host.controller.stop();
            host.await(RuntimeController.Phase.STOPPED, 20000);
            deadline = SystemClock.elapsedRealtime() + 5000;
            while (stopped.get().calls == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            onMain(() -> {
                stopped.get().expectNull();
                check(pending() == null && !(boolean)field("listening").get(activity));
            });
            host.pass("Pending background selection is canceled when the active runtime origin disappears and releases its listener");
            success = true;
        } catch (Throwable failure) {
            host.evidence.append("FAIL: file-chooser/").append(stage).append('/').append(failure.getClass().getSimpleName()).append('\n');
        } finally {
            if (observer != null) host.removeMonitor(observer);
            if (blocker != null) host.removeMonitor(blocker);
            if (recreation != null) application.unregisterActivityLifecycleCallbacks(recreation);
            try {
                if (activity != null) onMain(() -> activity.finish());
                if (host.controller != null && host.controller.phase != RuntimeController.Phase.STOPPED) {
                    host.controller.stop();
                    host.await(RuntimeController.Phase.STOPPED, 20000);
                }
            } catch (Throwable failure) {
                success = false;
                host.evidence.append("FAIL: file-chooser/cleanup/").append(failure.getClass().getSimpleName()).append('\n');
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", host.evidence.toString());
        host.finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private Capture show(Params parameters) {
        Capture capture = new Capture();
        check(web.getWebChromeClient().onShowFileChooser(web, capture, parameters));
        return capture;
    }

    private void background(boolean listenerRetained) throws Exception {
        check(host.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        boolean[] background = new boolean[1];
        do {
            onMain(() -> background[0] = !(boolean)field("started").get(activity));
            if (!background[0]) SystemClock.sleep(50);
        } while (!background[0] && SystemClock.elapsedRealtime() < deadline);
        check(background[0]);
        onMain(() -> check((boolean)field("listening").get(activity) == listenerRetained));
    }

    private void foreground() throws Exception {
        host.getTargetContext().startActivity(new Intent(host.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        boolean[] foreground = new boolean[1];
        do {
            onMain(() -> foreground[0] = (boolean)field("started").get(activity));
            if (!foreground[0]) SystemClock.sleep(50);
        } while (!foreground[0] && SystemClock.elapsedRealtime() < deadline);
        check(foreground[0]);
    }

    private static WebResourceRequest request(String origin, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(origin + "/synthetic-chooser-error"); }
            @Override public boolean isForMainFrame() { return mainFrame; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }

    private FileChooserRequest pending() throws Exception { return (FileChooserRequest)field("upload").get(activity); }
    private void deliver(int code, int result, Intent data) { activity.onActivityResult(code, result, data); }
    private static Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void ready() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        boolean[] ready = new boolean[1];
        do {
            onMain(() -> {
                web = find(activity.getWindow().getDecorView());
                ready[0] = web != null && web.getVisibility() == View.VISIBLE && web.getUrl() != null && host.controller.origin() != null
                        && web.getUrl().startsWith(host.controller.origin() + "/") && web.getProgress() == 100;
            });
            if (!ready[0]) SystemClock.sleep(50);
        } while (!ready[0] && SystemClock.elapsedRealtime() < deadline);
        check(ready[0]);
    }

    private static WebView find(View root) {
        if (root instanceof WebView) return (WebView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            WebView found = find(((ViewGroup)root).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private interface MainAction { void run() throws Exception; }
    private void onMain(MainAction action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        host.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Exception) throw (Exception)error.get();
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static void check(boolean condition) { if (!condition) throw new AdapterFailure(); }
    private static final class AdapterFailure extends RuntimeException {}

    private static final class Capture implements ValueCallback<Uri[]> {
        volatile int calls;
        Uri[] value;
        Runnable onNull;
        @Override public void onReceiveValue(Uri[] values) {
            value = values;
            calls++;
            if (values == null && onNull != null) onNull.run();
        }
        void expect(Uri... values) { check(calls == 1 && Arrays.equals(value, values)); }
        void expectNull() { check(calls == 1 && value == null); }
    }

    private static Params params(boolean multiple, String... types) { return new Params(multiple, types, "*/*"); }
    private static class Params extends WebChromeClient.FileChooserParams {
        private final boolean multiple;
        private final String[] types;
        private final String platformType;
        Params(boolean multiple, String[] types, String platformType) { this.multiple = multiple; this.types = types; this.platformType = platformType; }
        @Override public int getMode() { return multiple ? MODE_OPEN_MULTIPLE : MODE_OPEN; }
        @Override public String[] getAcceptTypes() { return types; }
        @Override public boolean isCaptureEnabled() { return false; }
        @Override public CharSequence getTitle() { return null; }
        @Override public String getFilenameHint() { return null; }
        @Override public Intent createIntent() { return new Intent(Intent.ACTION_GET_CONTENT).setType(platformType); }
    }

    private static final class Recreation implements Application.ActivityLifecycleCallbacks {
        final MainActivity previous;
        volatile MainActivity resumed;
        Recreation(MainActivity previous) { this.previous = previous; }
        @Override public void onActivityResumed(Activity activity) {
            if (activity instanceof MainActivity && activity != previous) resumed = (MainActivity)activity;
        }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }
}
