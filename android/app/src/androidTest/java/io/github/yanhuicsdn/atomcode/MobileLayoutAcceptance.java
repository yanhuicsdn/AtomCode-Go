package io.github.yanhuicsdn.atomcode;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Focused device UI checks. Never submits a message or reads account content. */
final class MobileLayoutAcceptance {
    private final LocalRuntimeSmoke host;
    private final Map<Activity, Boolean> previousKeepScreenOn = new LinkedHashMap<>();
    private volatile Activity current;
    private String origin;
    private String entry;
    private String stage = "initialization";
    private String observation;
    private int originalOrientation;
    private boolean capturedOrientation;

    MobileLayoutAcceptance(LocalRuntimeSmoke host) { this.host = host; }

    void run() {
        Bundle result = new Bundle();
        String failure = null;
        Path canary = null;
        Application application = (Application)host.getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle saved) {
                if (!(activity instanceof MainActivity)) return;
                current = activity;
                boolean alreadyKeptOn = (activity.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
                previousKeepScreenOn.put(activity, alreadyKeptOn);
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        };
        try {
            LocalRuntimeSmoke.require(!host.getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(),
                    "layout checks need an unlocked device");
            host.runOnMainSync(() -> application.registerActivityLifecycleCallbacks(callbacks));
            host.controller = RuntimeController.get(host.getTargetContext());
            current = host.startActivitySync(new Intent(host.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            originalOrientation = current.getRequestedOrientation();
            capturedOrientation = true;
            host.await(RuntimeController.Phase.RUNNING, 180000);
            origin = host.controller.origin();
            entry = host.controller.entryUrl();
            LocalRuntimeSmoke.require(origin != null && entry != null, "active runtime identity required");
            Path workspace = host.getTargetContext().getFilesDir().toPath().resolve("home/workspace");
            Files.createDirectories(workspace);
            canary = Files.createTempFile(workspace, "layout-acceptance-", ".canary");
            byte[] expected = "layout-generation-canary\n".getBytes(StandardCharsets.UTF_8);
            Files.write(canary, expected);

            stage = "initial-page";
            pageReady(current);
            if (current.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT)
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
            host.pass("Foreground WebView renders with a visible composer before rotation");

            stage = "landscape-recreation";
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE);
            assertSameRuntime();
            host.pass("Landscape Activity recreation preserves runtime generation and fullscreen composer");

            stage = "landscape-long-text";
            longTextScroll(current);
            host.pass("Landscape composer lays out and scrolls temporary long text without submission");

            stage = "portrait-recreation";
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
            assertSameRuntime();
            host.pass("Portrait Activity recreation preserves runtime generation and usable composer");

            stage = "workspace-and-generation";
            LocalRuntimeSmoke.require(java.util.Arrays.equals(expected, Files.readAllBytes(canary)), "workspace canary retained");
            assertSameRuntime();
            host.pass("Workspace canary survives both rotations with unchanged runtime identity");
        } catch (Throwable error) {
            // No exception messages: they can contain URLs, document content or credentials.
            failure = stage + "/" + error.getClass().getSimpleName()
                    + (observation == null ? "" : " [" + observation + "]");
        } finally {
            try {
                host.runOnMainSync(() -> {
                    application.unregisterActivityLifecycleCallbacks(callbacks);
                    for (Map.Entry<Activity, Boolean> item : previousKeepScreenOn.entrySet())
                        if (!item.getKey().isDestroyed() && !item.getValue())
                            item.getKey().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (capturedOrientation && current != null && !current.isDestroyed())
                        current.setRequestedOrientation(originalOrientation);
                });
            } catch (Throwable error) { failure = appendFailure(failure, "orientation-cleanup", error); }
            try { if (canary != null) Files.deleteIfExists(canary); }
            catch (Throwable error) { failure = appendFailure(failure, "canary-cleanup", error); }
        }
        result.putString("stream", host.evidence + (failure == null ? "" : "FAIL: " + failure + "\n"));
        host.finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static String appendFailure(String previous, String phase, Throwable error) {
        return (previous == null ? "" : previous + "; ") + phase + "/" + error.getClass().getSimpleName();
    }

    private void assertSameRuntime() throws Exception {
        String previousStage = stage;
        stage = previousStage + "/runtime-identity";
        boolean running = host.controller.phase == RuntimeController.Phase.RUNNING;
        boolean sameOrigin = origin.equals(host.controller.origin());
        boolean sameEntry = entry.equals(host.controller.entryUrl());
        observation = "running=" + running + ", sameOrigin=" + sameOrigin + ", sameEntry=" + sameEntry;
        LocalRuntimeSmoke.require(running && sameOrigin && sameEntry,
                "rotation must not restart or replace the runtime");
        stage = previousStage;
        observation = null;
    }

    private void rotate(int request, int expectedConfiguration) throws Exception {
        String previousStage = stage;
        stage = previousStage + "/monitor";
        observation = "replacement=false";
        Activity before = current;
        Instrumentation.ActivityMonitor monitor = host.addMonitor(MainActivity.class.getName(), null, false);
        try {
            host.runOnMainSync(() -> before.setRequestedOrientation(request));
            Activity replacement = null;
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (SystemClock.elapsedRealtime() < deadline) {
                Activity candidate = host.waitForMonitorWithTimeout(monitor, 1000);
                if (candidate != null && candidate != before && !candidate.isDestroyed()) {
                    replacement = candidate;
                    break;
                }
            }
            LocalRuntimeSmoke.require(replacement != null, "orientation must recreate MainActivity");
            current = replacement;
            stage = previousStage + "/configuration";
            int actualConfiguration = current.getResources().getConfiguration().orientation;
            observation = "expected=" + expectedConfiguration + ", actual=" + actualConfiguration;
            LocalRuntimeSmoke.require(actualConfiguration == expectedConfiguration,
                    "requested orientation must be applied");
            stage = previousStage;
            observation = null;
            pageReady(current);
        } finally { host.removeMonitor(monitor); }
    }

    private void pageReady(Activity activity) throws Exception {
        String previousStage = stage;
        stage = previousStage + "/page-render";
        observation = null;
        long deadline = SystemClock.elapsedRealtime() + 30000;
        boolean ready = false;
        do {
            JSONObject page = evaluate(activity, "(()=>{document.querySelector('.model-config-modal .modal-close')?.click();"
                    + "const e=document.querySelector('textarea');const p={dom:document.readyState==='complete',"
                    + "sameOrigin:location.origin===" + JSONObject.quote(origin)
                    + ",tokenFree:!location.search.includes('token='),composer:!!e};if(!e)return p;"
                    + "e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();"
                    + "p.top=Math.round(r.top);p.bottom=Math.round(r.bottom);p.viewport=Math.round(innerHeight);"
                    + "p.ready=p.dom&&p.sameOrigin&&p.tokenFree&&r.width>20&&r.height>20&&r.top>=-2&&r.bottom<=innerHeight+2;return p})()");
            observation = "dom=" + page.optBoolean("dom") + ", composer=" + page.optBoolean("composer")
                    + ", sameOrigin=" + page.optBoolean("sameOrigin") + ", tokenFree=" + page.optBoolean("tokenFree")
                    + ", top=" + page.optInt("top", -1) + ", bottom=" + page.optInt("bottom", -1)
                    + ", viewport=" + page.optInt("viewport", -1);
            if (page.optBoolean("ready")) { ready = true; break; }
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        LocalRuntimeSmoke.require(ready, "rendered composer must be visible in the viewport");
        stage = previousStage + "/fullscreen";
        observation = null;
        final boolean[] fullscreen = new boolean[1];
        host.runOnMainSync(() -> {
            WebView web = findWebView(activity.getWindow().getDecorView());
            if (web == null || !(web.getParent() instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup)web.getParent();
            fullscreen[0] = web.getVisibility() == View.VISIBLE && root.getChildAt(0).getVisibility() == View.GONE
                    && web.getWidth() > 0 && web.getHeight() >= root.getHeight() * 0.8;
        });
        LocalRuntimeSmoke.require(fullscreen[0], "native runtime controls must stay hidden while WebView is ready");
        stage = previousStage;
        observation = null;
    }

    private void longTextScroll(Activity activity) throws Exception {
        StringBuilder draft = new StringBuilder();
        for (int line = 0; line < 200; line++)
            draft.append("Layout acceptance temporary draft, never sent. ").append(line)
                    .append(" ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789\n");
        // Exercise the actual textarea's layout/scrolling. Original user text
        // stays inside WebView and is restored synchronously, without firing
        // input handlers or modifying application draft persistence.
        JSONObject scroll = evaluate(activity, "(()=>{const e=document.querySelector('textarea');"
                + "if(!e)return {ok:false};const value=e.value,top=e.scrollTop,start=e.selectionStart,end=e.selectionEnd;"
                + "try{e.value=" + JSONObject.quote(draft.toString()) + ";e.scrollTop=e.scrollHeight;"
                + "return {ok:e.value.length===" + draft.length()
                + "&&e.scrollHeight>e.clientHeight+100&&e.scrollTop>0};}"
                + "finally{e.value=value;e.setSelectionRange(start,end);e.scrollTop=top;}})()");
        LocalRuntimeSmoke.require(scroll.optBoolean("ok"), "long temporary textarea must remain scrollable");
    }

    private JSONObject evaluate(Activity activity, String script) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        host.runOnMainSync(() -> {
            WebView web = findWebView(activity.getWindow().getDecorView());
            if (web == null) { completed.countDown(); return; }
            web.evaluateJavascript(script, result -> { value.set(result); completed.countDown(); });
        });
        LocalRuntimeSmoke.require(completed.await(5, TimeUnit.SECONDS) && value.get() != null,
                "WebView inspection must complete");
        return new JSONObject(value.get());
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView result = findWebView(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
