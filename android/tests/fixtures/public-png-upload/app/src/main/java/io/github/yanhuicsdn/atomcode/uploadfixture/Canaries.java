package io.github.yanhuicsdn.atomcode.uploadfixture;

import android.net.Uri;
import android.util.Base64;

/** Two original, generated 1x1 RGBA PNGs; public fixed test bytes, never user data. */
final class Canaries {
    static final String FIXTURE = "io.github.yanhuicsdn.atomcode.uploadfixture";
    static final String OWNER = "io.github.yanhuicsdn.atomcode";
    static final String AUTHORITY = FIXTURE + ".png";
    static final String CONFIRMATION = "public-png-upload-fixture-v1";
    static final String[] NAMES = {"one.png", "two.png"};
    static final String[] BASE64 = {
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP4z8DwHwAFAAH/VscvDQAAAABJRU5ErkJggg==",
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNgYPj/HwADAgH/OSkZvgAAAABJRU5ErkJggg=="
    };
    static final Uri[] URIS = {Uri.parse("content://" + AUTHORITY + "/one.png"), Uri.parse("content://" + AUTHORITY + "/two.png")};
    static byte[] bytes(int index) { return Base64.decode(BASE64[index], Base64.DEFAULT); }
    static int index(Uri uri) {
        for (int i = 0; i < URIS.length; i++) if (URIS[i].equals(uri)) return i;
        throw new IllegalArgumentException("unknown-public-fixture-uri");
    }
    private Canaries() {}
}
