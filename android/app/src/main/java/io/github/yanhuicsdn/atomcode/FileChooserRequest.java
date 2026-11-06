package io.github.yanhuicsdn.atomcode;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;

/** One Activity-owned, temporary document selection. Never opens or persists URIs. */
final class FileChooserRequest {
    static final int FIRST_CODE = 0x1000;
    static final int LAST_CODE = 0xffff;
    final WebView view;
    final String origin;
    final long generation;
    final int code;
    final Intent picker;
    private final boolean multiple;
    private ValueCallback<Uri[]> callback;

    FileChooserRequest(WebView view, String origin, long generation, int code, ValueCallback<Uri[]> callback,
                       WebChromeClient.FileChooserParams parameters) {
        int mode = parameters.getMode();
        if (mode != WebChromeClient.FileChooserParams.MODE_OPEN
                && mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            throw new IllegalArgumentException("Unsupported document selection mode");
        this.view = view;
        this.origin = origin;
        this.generation = generation;
        this.code = code;
        this.callback = callback;
        multiple = mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        // MIME values are picker hints, never authentication of provider content.
        // Use platform hints for extension conversion; do not invent MIME mappings.
        Intent platform = parameters.createIntent();
        LinkedHashSet<String> types = new LinkedHashSet<>();
        addTypes(types, parameters.getAcceptTypes());
        if (platform != null) {
            LinkedHashSet<String> precise = new LinkedHashSet<>();
            addTypes(precise, platform.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
            types.addAll(precise);
            String type = platform.getType();
            if (types.isEmpty() || (precise.isEmpty() && type != null && !"*/*".equals(type.trim())))
                addTypes(types, new String[]{type});
        }
        if (types.isEmpty()) types.add("*/*");
        picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(types.size() == 1 ? types.iterator().next() : "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (types.size() > 1) picker.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
    }

    private static void addTypes(LinkedHashSet<String> types, String[] values) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            for (String item : value.split(",")) {
                String type = item.trim().toLowerCase(Locale.ROOT);
                if (type.equals("*/*") || type.matches("[a-z0-9!#$&^_.+\\-]+/(?:[a-z0-9!#$&^_.+\\-]+|\\*)"))
                    types.add(type);
            }
        }
    }

    Uri[] selection(int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null) return null;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        try {
            Uri direct = data.getData();
            if (direct != null && !add(selected, direct)) return null;
            ClipData clip = data.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++)
                if (!add(selected, clip.getItemAt(i).getUri())) return null;
        } catch (RuntimeException malformed) { return null; }
        if (selected.isEmpty() || (!multiple && selected.size() != 1)) return null;
        return selected.toArray(new Uri[0]);
    }

    private static boolean add(LinkedHashSet<Uri> selected, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !uri.isHierarchical()) return false;
        try {
            URI parsed = new URI(uri.toString());
            String authority = parsed.getRawAuthority();
            if (authority == null || !authority.matches("(?:[0-9]+@)?[A-Za-z0-9_][A-Za-z0-9_.-]*")) return false;
            int user = authority.indexOf('@');
            if (user >= 0) Integer.parseInt(authority.substring(0, user));
        } catch (URISyntaxException | NumberFormatException malformed) { return false; }
        selected.add(uri);
        return true;
    }

    void complete(Uri[] values) {
        ValueCallback<Uri[]> retired = callback;
        callback = null;
        rejectOrDeliver(retired, values);
    }

    static void rejectOrDeliver(ValueCallback<Uri[]> callback, Uri[] values) {
        if (callback != null) try { callback.onReceiveValue(values); }
        catch (RuntimeException unavailableWebView) { /* State was retired before calling Chromium. */ }
    }
}
