package io.github.yanhuicsdn.atomcode.runtime;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RuntimeManifest {
    public final JSONObject json;
    public final String version;
    public final long bytes;
    public final long expandedBytes;
    public final int files;
    public final String sha256;

    private RuntimeManifest(JSONObject value) throws JSONException, IOException {
        json = value;
        if (value.getInt("schema") != 1 || !value.getString("abi").equals("arm64-v8a"))
            throw new IOException("运行环境格式或架构不受支持");
        version = value.getString("runtimeVersion");
        if (!version.matches("atomcode-[a-f0-9]{16}")) throw new IOException("运行环境版本无效");
        if (!value.getString("rootfs").equals("rootfs.zip")) throw new IOException("运行环境文件名无效");
        bytes = value.getLong("rootfsBytes");
        expandedBytes = value.getLong("uncompressedBytes");
        files = value.getInt("files");
        sha256 = value.getString("rootfsSha256");
        if (bytes <= 0 || expandedBytes <= 0 || expandedBytes > 8L * 1024 * 1024 * 1024
                || files <= 0 || files > 200000 || !sha256.matches("[a-f0-9]{64}"))
            throw new IOException("运行环境大小或校验值无效");
    }

    public static RuntimeManifest load(Context context) throws IOException {
        try (InputStream source = context.getAssets().open("runtime/manifest.json")) {
            return new RuntimeManifest(new JSONObject(readText(source, 1024 * 1024)));
        } catch (JSONException error) {
            throw new IOException("无法读取运行环境清单", error);
        }
    }

    public static String readText(InputStream source, int limit) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = source.read(buffer)) != -1) {
            if (read > limit - output.size()) throw new IOException("元数据超过大小上限");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
