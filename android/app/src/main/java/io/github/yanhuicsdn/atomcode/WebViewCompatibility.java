package io.github.yanhuicsdn.atomcode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep this minimum in agreement with the Chrome target in webui/vite.config.ts. */
final class WebViewCompatibility {
    static final int MINIMUM_CHROMIUM_MAJOR = 99;
    private static final Pattern VERSION = Pattern.compile("^([0-9]+)(?:\\.[0-9]+)*$");

    private WebViewCompatibility() {}

    static boolean supports(String versionName) {
        if (versionName == null) return false;
        Matcher version = VERSION.matcher(versionName.trim());
        if (!version.matches()) return false;
        try { return Integer.parseInt(version.group(1)) >= MINIMUM_CHROMIUM_MAJOR; }
        catch (NumberFormatException unknown) { return false; }
    }
}
