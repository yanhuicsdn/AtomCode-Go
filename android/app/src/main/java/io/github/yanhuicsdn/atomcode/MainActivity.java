package io.github.yanhuicsdn.atomcode;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeController;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeManifest;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeService;
import io.github.yanhuicsdn.atomcode.runtime.WorkspaceArchive;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;

public final class MainActivity extends Activity implements RuntimeController.Listener {
    private RuntimeController controller;
    private TextView status;
    private LinearLayout chrome;
    private boolean fullscreen;
    private TextView introduction;
    private Button start;
    private Button stop;
    private Button importFiles;
    private Button exportFiles;
    private ProgressBar progress;
    private LinearLayout workspaceContainer;
    private volatile WebView web;
    private String webOrigin;
    private long webGeneration = -1;
    private String webUnavailable;
    private String loadedOrigin;
    private long loadedGeneration = -1;
    private String requestedOrigin;
    private long requestedGeneration = -1;
    private boolean workspaceConnectionFailed;
    private String failedOrigin;
    private long failedGeneration = -1;
    private long webLoadRequest;
    private FileChooserRequest upload;
    private int nextUploadCode = FileChooserRequest.FIRST_CODE;
    private boolean started;
    private boolean listening;
    private boolean destroying;
    private boolean chooserStateSaved;
    private static final String NEXT_UPLOAD_CODE = "next-file-chooser-code";
    private static final int IMPORT = 11;
    private static final int EXPORT = 12;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        controller = RuntimeController.get(this);
        if (saved != null) nextUploadCode = Math.max(FileChooserRequest.FIRST_CODE,
                saved.getInt(NEXT_UPLOAD_CODE, FileChooserRequest.FIRST_CODE));
        getWindow().setStatusBarColor(Color.rgb(21, 59, 53));
        getWindow().setNavigationBarColor(Color.rgb(247, 248, 244));
        LinearLayout root = new LinearLayout(this);
        workspaceContainer = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 248, 244));
        root.setFitsSystemWindows(true);
        LinearLayout header = new LinearLayout(this);
        chrome = header;
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(8));
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTextColor(Color.rgb(21, 59, 53));
        header.addView(title);
        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(6));
        header.addView(status);
        LinearLayout actions = new LinearLayout(this);
        start = button("启动本机环境", actions);
        stop = button("停止", actions);
        header.addView(actions);
        LinearLayout files = new LinearLayout(this);
        importFiles = button("导入项目 ZIP", files);
        exportFiles = button("导出工作区", files);
        header.addView(files);
        importFiles.setOnClickListener(view -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
            try { startActivityForResult(picker, IMPORT); }
            catch (ActivityNotFoundException error) { toast("文件选择器不可用"); }
        });
        exportFiles.setOnClickListener(view -> {
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "atomcode-go-workspace.zip");
            try { startActivityForResult(picker, EXPORT); }
            catch (ActivityNotFoundException error) { toast("文件选择器不可用"); }
        });
        start.setOnClickListener(view -> startForegroundService(new Intent(this, RuntimeService.class)));
        stop.setOnClickListener(view -> startService(new Intent(this, RuntimeService.class).setAction(RuntimeService.STOP)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        header.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        root.addView(header);
        introduction = new TextView(this);
        introduction.setPadding(dp(20), dp(24), dp(20), dp(24));
        introduction.setTextSize(16);
        String size;
        try { size = Long.toString(RuntimeManifest.load(this).expandedBytes / (1024 * 1024)); }
        catch (Exception error) { size = "未知"; }
        introduction.setText("在手机上运行你的编程工作区\n\n首次启动会安装内置 Linux 和 AtomCode，解压后约 " + size
                + " MB，请预留额外空间。安装完成后可离线启动，调用模型需要网络和你自己的模型配置。\n\n"
                + "工作区和会话保存在应用私有目录；卸载应用会删除它们。\n\n"
                + "这是 ARM64 侧载版本，首次安装时系统可能提示此应用面向较旧的 Android 版本。");
        root.addView(introduction);
        createWebView(root);
        setContentView(root);
        changed();
        root.post(() -> {
            if (!isDestroyed() && controller.shouldAutoStart())
                startForegroundService(new Intent(this, RuntimeService.class));
        });
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private Button button(String label, LinearLayout parent) {
        Button value = new Button(this);
        value.setText(label);
        value.setMinHeight(dp(48));
        parent.addView(value);
        return value;
    }

    private void createWebView(LinearLayout root) {
        String origin = controller.origin();
        long generation = controller.connectionGeneration();
        webOrigin = origin;
        webGeneration = generation;
        webUnavailable = null;
        try {
            android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
            if (provider == null || !WebViewCompatibility.supports(provider.versionName)) {
                webUnavailable = "请安装或更新 Android System WebView（Chromium 99 或更新版本），然后重新打开 AtomCode-Go。"
                        + "\nUpdate Android System WebView to Chromium 99 or newer, then reopen AtomCode-Go.";
                return;
            }
            WebView created = new WebView(this);
            web = created;
            configureWebView(created, origin, generation);
            root.addView(created, new LinearLayout.LayoutParams(-1, 0, 1));
        } catch (RuntimeException | LinkageError unavailable) {
            // Provider installation/initialization can fail even when package
            // metadata exists. Keep the native recovery controls usable.
            retireWebView();
            webOrigin = origin;
            webGeneration = generation;
            webUnavailable = "Android System WebView 无法启动。请安装并启用兼容版本（Chromium 99 或更新版本），然后重新打开应用。"
                    + "\nAndroid System WebView could not start. Install/enable a compatible provider (Chromium 99+), then reopen the app.";
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView owner, String origin, long generation) {
        WebSettings settings = owner.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(owner, false);
        owner.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != owner || !currentConnection(owner, origin, generation)) return true;
                if (isLocal(request.getUrl().toString(), origin)) return false;
                Uri uri = request.getUrl();
                if (request.isForMainFrame() && request.hasGesture()
                        && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException ignored) { toast("没有可打开此链接的浏览器"); }
                }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Chromium calls this on a worker thread. Only compare the
                // volatile identity and immutable connection binding here.
                if (view == owner && currentConnection(owner, origin, generation)
                        && isLocal(request.getUrl().toString(), origin)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (view != owner || !currentConnection(owner, origin, generation)) { handler.cancel(); return; }
                Bundle certificate = SslCertificate.saveState(error.getCertificate());
                byte[] actual = certificate == null ? null : certificate.getByteArray("x509-certificate");
                byte[] expected = controller.certificate();
                // A precise certificate pin for our exact active origin; never
                // trust arbitrary self-signed localhost listeners.
                if (isLocal(error.getUrl(), origin) && actual != null && expected != null && Arrays.equals(actual, expected)) handler.proceed();
                else handler.cancel();
            }

            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) runOnUiThread(() -> {
                    if (view != owner || !currentConnection(owner, origin, generation)) return;
                    // The default Chromium error page echoes its failing URL,
                    // including a bootstrap token. Show only native status.
                    webLoadRequest++;
                    workspaceConnectionFailed = true;
                    failedOrigin = origin;
                    failedGeneration = generation;
                    owner.setVisibility(View.GONE);
                    cancelUpload();
                    chrome.setVisibility(View.VISIBLE);
                    setRuntimeFullscreen(false);
                    status.setText("本机界面连接中断，请停止后重新启动");
                });
            }
        });
        owner.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams parameters) {
                if (view != owner || !currentConnection(owner, origin, generation)) {
                    FileChooserRequest.rejectOrDeliver(callback, null);
                    return true;
                }
                return chooseFile(view, callback, parameters);
            }
        });
        owner.setDownloadListener((url, agent, disposition, type, length) -> {
            if (currentConnection(owner, origin, generation)) toast("请使用工作区导出功能保存文件");
        });
    }

    private boolean isLocal(String url) {
        return isLocal(url, controller.origin());
    }

    private static boolean isLocal(String url, String origin) {
        if (origin == null || url == null) return false;
        Uri parsed = Uri.parse(url);
        Uri expected = Uri.parse(origin);
        return "https".equals(parsed.getScheme()) && "127.0.0.1".equals(parsed.getHost())
                && parsed.getPort() == expected.getPort() && parsed.getUserInfo() == null;
    }

    private boolean currentConnection(WebView owner, String origin, long generation) {
        return owner != null && owner == web && origin != null && origin.equals(controller.origin())
                && generation == controller.connectionGeneration();
    }

    private void retireWebView() {
        WebView retired = web;
        web = null; // Retire callback authority before cleanup or callback reentry.
        webOrigin = null;
        webGeneration = -1;
        webLoadRequest++;
        loadedOrigin = null;
        loadedGeneration = -1;
        requestedOrigin = null;
        requestedGeneration = -1;
        workspaceConnectionFailed = false;
        failedOrigin = null;
        failedGeneration = -1;
        if (retired != null && workspaceContainer != null) workspaceContainer.removeView(retired);
        cancelUpload();
        if (retired != null) {
            try { retired.stopLoading(); } catch (RuntimeException | LinkageError ignored) {}
            try { retired.destroy(); } catch (RuntimeException | LinkageError ignored) {}
        }
    }

    private void refreshWebViewConnection() {
        String origin = controller.origin();
        long generation = controller.connectionGeneration();
        if (origin != null && (!origin.equals(webOrigin) || generation != webGeneration)) {
            retireWebView();
            createWebView(workspaceContainer);
        } else if (origin == null && webOrigin != null) {
            retireWebView();
            CookieManager.getInstance().removeAllCookies(null);
        }
    }

    private boolean trustedChooser(WebView view, String origin, long generation) {
        return !destroying && !isFinishing() && !workspaceConnectionFailed
                && controller.phase == RuntimeController.Phase.RUNNING && view != null
                && view == web && view.getVisibility() == View.VISIBLE
                && origin != null && origin.equals(controller.origin()) && origin.equals(loadedOrigin)
                && generation == controller.connectionGeneration() && generation == loadedGeneration && isLocal(view.getUrl());
    }

    private boolean chooseFile(WebView view, ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams parameters) {
        // A request accepted after the saved counter snapshot could reuse its
        // code after recreation. Existing pending results remain valid here.
        if (!started || chooserStateSaved) {
            FileChooserRequest.rejectOrDeliver(callback, null);
            return true;
        }
        String origin = controller.origin();
        long generation = controller.connectionGeneration();
        FileChooserRequest next;
        try {
            if (!trustedChooser(view, origin, generation) || nextUploadCode > FileChooserRequest.LAST_CODE)
                throw new IllegalArgumentException("Unavailable document selection owner");
            next = new FileChooserRequest(view, origin, generation, nextUploadCode++, callback, parameters);
        } catch (RuntimeException unavailable) {
            cancelUpload();
            FileChooserRequest.rejectOrDeliver(callback, null);
            return true;
        }
        FileChooserRequest previous = upload;
        upload = next;
        syncListener();
        if (previous != null) previous.complete(null);
        // A canceled callback can reenter and replace this request. Never
        // launch it afterward or let an older picker result complete its successor.
        if (upload != next) return true;
        if (!trustedChooser(next.view, next.origin, next.generation)) { finishUpload(next, null); return true; }
        try { startActivityForResult(next.picker, next.code); }
        catch (RuntimeException unavailable) {
            finishUpload(next, null);
            toast("文件选择器不可用");
        }
        return true;
    }

    private void finishUpload(FileChooserRequest expected, Uri[] values) {
        if (upload != expected) return;
        upload = null;
        syncListener();
        expected.complete(values);
    }

    private void cancelUpload() {
        FileChooserRequest pending = upload;
        if (pending != null) finishUpload(pending, null);
    }

    private void syncListener() {
        boolean wanted = !destroying && (started || upload != null);
        if (wanted && !listening) { controller.addListener(this); listening = true; }
        else if (!wanted && listening) { controller.removeListener(this); listening = false; }
    }

    @Override protected void onStart() { super.onStart(); started = true; chooserStateSaved = false; syncListener(); changed(); }
    @Override protected void onStop() { started = false; syncListener(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        chooserStateSaved = true;
        state.putInt(NEXT_UPLOAD_CODE, nextUploadCode);
        super.onSaveInstanceState(state);
    }
    @Override public void changed() {
        if (destroying) return;
        refreshWebViewConnection();
        if (upload != null && !trustedChooser(upload.view, upload.origin, upload.generation)) cancelUpload();
        String origin = controller.origin();
        long generation = controller.connectionGeneration();
        boolean failedPage = workspaceConnectionFailed && origin != null && origin.equals(failedOrigin)
                && generation == failedGeneration;
        String message = failedPage ? "本机界面连接中断，请停止后重新启动"
                : controller.message + (controller.phase == RuntimeController.Phase.INSTALLING ? " · " + controller.progress + "%" : "");
        status.setText(webUnavailable == null ? message : message + "\n\n" + webUnavailable);
        progress.setVisibility(controller.busy() ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(controller.phase != RuntimeController.Phase.INSTALLING);
        progress.setProgress(controller.progress);
        start.setEnabled(!controller.busy() && controller.phase != RuntimeController.Phase.RUNNING);
        stop.setEnabled(controller.phase == RuntimeController.Phase.RUNNING || controller.phase == RuntimeController.Phase.STARTING || controller.phase == RuntimeController.Phase.INSTALLING);
        boolean transferAllowed = !controller.busy() && controller.phase != RuntimeController.Phase.RUNNING;
        importFiles.setEnabled(transferAllowed);
        exportFiles.setEnabled(transferAllowed);
        importFiles.setVisibility(controller.phase == RuntimeController.Phase.RUNNING ? View.GONE : View.VISIBLE);
        exportFiles.setVisibility(controller.phase == RuntimeController.Phase.RUNNING ? View.GONE : View.VISIBLE);
        boolean running = origin != null && web != null && !failedPage;
        chrome.setVisibility(running ? View.GONE : View.VISIBLE);
        setRuntimeFullscreen(running);
        introduction.setVisibility(running ? View.GONE : View.VISIBLE);
        if (web != null) web.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running && (!origin.equals(requestedOrigin) || generation != requestedGeneration)) {
            requestedOrigin = origin;
            requestedGeneration = generation;
            long request = ++webLoadRequest;
            WebView owner = web;
            CookieManager.getInstance().removeAllCookies(removed -> loadWorkspace(owner, origin, generation, request));
        } else if (web != null && origin == null && requestedOrigin != null) {
            retireWebView();
            CookieManager.getInstance().removeAllCookies(null);
        }
    }

    private void loadWorkspace(WebView owner, String origin, long generation, long request) {
        if (!destroying && !isDestroyed() && currentConnection(owner, origin, generation) && request == webLoadRequest
                && generation == requestedGeneration
                && origin.equals(requestedOrigin)) {
            owner.clearSslPreferences();
            workspaceConnectionFailed = false;
            failedOrigin = null;
            failedGeneration = -1;
            loadedOrigin = origin;
            loadedGeneration = generation;
            owner.loadUrl(controller.entryUrl());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request >= FileChooserRequest.FIRST_CODE && request <= FileChooserRequest.LAST_CODE) {
            FileChooserRequest pending = upload;
            if (pending != null && pending.code == request)
                finishUpload(pending, trustedChooser(pending.view, pending.origin, pending.generation) ? pending.selection(result, data) : null);
            return;
        }
        if ((request == IMPORT || request == EXPORT) && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null || !"content".equals(uri.getScheme())) return;
            // Do not grant a background writer access to a live workspace.
            if (controller.busy() || controller.phase == RuntimeController.Phase.RUNNING) { toast("请先停止引擎，再导入或导出工作区"); return; }
            android.content.ContentResolver resolver = getApplicationContext().getContentResolver();
            java.nio.file.Path cache = getCacheDir().toPath();
            controller.transfer(workspace -> {
                if (request == IMPORT) {
                    try (java.io.InputStream input = resolver.openInputStream(uri)) {
                        if (input == null) throw new java.io.IOException("无法读取选择的文件");
                        java.nio.file.Path project = WorkspaceArchive.importProject(input, workspace);
                        return "项目已导入：/root/workspace/" + project.getFileName();
                    }
                }
                java.nio.file.Path archive = java.nio.file.Files.createTempFile(cache, "workspace-", ".zip");
                try {
                    WorkspaceArchive.exportWorkspace(workspace, java.nio.file.Files.newOutputStream(archive));
                    try (java.io.InputStream input = java.nio.file.Files.newInputStream(archive);
                         java.io.OutputStream output = resolver.openOutputStream(uri, "w")) {
                        if (output == null) throw new java.io.IOException("无法写入导出文件");
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    }
                    return "工作区已导出，请妥善保管 ZIP 文件";
                } finally { java.nio.file.Files.deleteIfExists(archive); }
            });
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void setRuntimeFullscreen(boolean enabled) {
        fullscreen = enabled;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController insets = getWindow().getInsetsController();
            if (insets != null) {
                if (enabled) {
                    insets.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    insets.hide(android.view.WindowInsets.Type.systemBars());
                } else insets.show(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(enabled
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreen) setRuntimeFullscreen(true);
    }
    @Override public void onBackPressed() {
        if (web != null && web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        destroying = true;
        retireWebView();
        controller.removeListener(this);
        listening = false;
        super.onDestroy();
    }
}
