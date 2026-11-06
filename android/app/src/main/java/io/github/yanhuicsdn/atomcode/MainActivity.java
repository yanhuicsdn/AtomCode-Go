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
    private WebView web;
    private String loadedOrigin;
    private ValueCallback<Uri[]> upload;
    private static final int CHOOSE_FILE = 10;
    private static final int IMPORT = 11;
    private static final int EXPORT = 12;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        controller = RuntimeController.get(this);
        getWindow().setStatusBarColor(Color.rgb(21, 59, 53));
        getWindow().setNavigationBarColor(Color.rgb(247, 248, 244));
        LinearLayout root = new LinearLayout(this);
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
        web = new WebView(this);
        configureWebView();
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
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

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isLocal(request.getUrl().toString())) return false;
                Uri uri = request.getUrl();
                if (request.isForMainFrame() && request.hasGesture()
                        && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException ignored) { toast("没有可打开此链接的浏览器"); }
                }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isLocal(request.getUrl().toString())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                Bundle certificate = SslCertificate.saveState(error.getCertificate());
                byte[] actual = certificate == null ? null : certificate.getByteArray("x509-certificate");
                byte[] expected = controller.certificate();
                // A precise certificate pin for our exact active origin; never
                // trust arbitrary self-signed localhost listeners.
                if (isLocal(error.getUrl()) && actual != null && expected != null && Arrays.equals(actual, expected)) handler.proceed();
                else handler.cancel();
            }

            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) runOnUiThread(() -> {
                    // The default Chromium error page echoes its failing URL,
                    // including a bootstrap token. Show only native status.
                    web.setVisibility(View.GONE);
                    chrome.setVisibility(View.VISIBLE);
                    setRuntimeFullscreen(false);
                    status.setText("本机界面连接中断，请停止后重新启动");
                });
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams parameters) {
                if (upload != null) upload.onReceiveValue(null);
                upload = callback;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(picker, CHOOSE_FILE); }
                catch (ActivityNotFoundException error) { upload.onReceiveValue(null); upload = null; toast("文件选择器不可用"); }
                return true;
            }
        });
        web.setDownloadListener((url, agent, disposition, type, length) -> toast("请使用工作区导出功能保存文件"));
    }

    private boolean isLocal(String url) {
        String origin = controller.origin();
        if (origin == null) return false;
        Uri parsed = Uri.parse(url);
        Uri expected = Uri.parse(origin);
        return "https".equals(parsed.getScheme()) && "127.0.0.1".equals(parsed.getHost())
                && parsed.getPort() == expected.getPort() && parsed.getUserInfo() == null;
    }

    @Override protected void onStart() { super.onStart(); controller.addListener(this); changed(); }
    @Override protected void onStop() { controller.removeListener(this); super.onStop(); }
    @Override public void changed() {
        status.setText(controller.message + (controller.phase == RuntimeController.Phase.INSTALLING ? " · " + controller.progress + "%" : ""));
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
        String origin = controller.origin();
        boolean running = origin != null;
        chrome.setVisibility(running ? View.GONE : View.VISIBLE);
        setRuntimeFullscreen(running);
        introduction.setVisibility(running ? View.GONE : View.VISIBLE);
        web.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running && !origin.equals(loadedOrigin)) {
            loadedOrigin = origin;
            CookieManager.getInstance().removeAllCookies(removed -> {
                if (!isDestroyed() && origin.equals(controller.origin())) {
                    web.clearSslPreferences();
                    web.loadUrl(controller.entryUrl());
                }
            });
        } else if (!running && loadedOrigin != null) {
            loadedOrigin = null;
            web.stopLoading();
            web.loadUrl("about:blank");
            web.clearHistory();
            CookieManager.getInstance().removeAllCookies(null);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CHOOSE_FILE && upload != null) {
            Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
            upload.onReceiveValue(uri != null && "content".equals(uri.getScheme()) ? new Uri[]{uri} : null);
            upload = null;
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
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (upload != null) upload.onReceiveValue(null);
        web.stopLoading();
        web.destroy();
        super.onDestroy();
    }
}
