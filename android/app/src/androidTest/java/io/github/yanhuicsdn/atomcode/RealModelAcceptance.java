package io.github.yanhuicsdn.atomcode;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeController;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeService;
import io.github.yanhuicsdn.atomcode.runtime.RuntimeManifest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Uses only the model the user configured inside the app. No credential reads. */
final class RealModelAcceptance {
    private final LocalRuntimeSmoke host;
    private String cookie;
    private String guest;
    private String session;
    private Path project;
    private int allowed;
    private int denied;
    private int textChunks;
    private int toolResults;
    private final String command = "printf '%s\\n' 'device-command-ok' > command-proof.txt";

    RealModelAcceptance(LocalRuntimeSmoke host) { this.host = host; }

    void run() {
        Bundle result = new Bundle();
        try {
            host.controller = RuntimeController.get(host.getTargetContext());
            host.getTargetContext().startForegroundService(new Intent(host.getTargetContext(), RuntimeService.class));
            host.await(RuntimeController.Phase.RUNNING, 120000);
            exchange();
            JSONObject config = host.api("/config", null, cookie, 200);
            org.json.JSONArray models = config.optJSONArray("providers");
            if (models != null) for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.getJSONObject(i);
                if (model.optBoolean("is_default")) host.evidence.append("Default model credential metadata: requires_login=")
                        .append(model.optBoolean("requires_login")).append(", has_api_key=").append(model.optBoolean("has_api_key")).append('\n');
            }
            String name = "model-acceptance-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            project = host.getTargetContext().getFilesDir().toPath().resolve("home/workspace").resolve(name);
            Files.createDirectories(project);
            guest = "/root/workspace/" + name;
            String prompt = "这是 Android 真机端到端验收。只操作当前工作目录 " + guest + "，不得读取凭据、用户目录或其他项目。"
                    + "请实际调用工具完成：1. 用文件写入工具创建 model-proof.txt，内容严格为 model-executed 加一个换行。"
                    + "2. 用命令工具在当前目录执行以下原样命令：" + command
                    + "。3. 读取刚创建的两个文件，确认真实内容。不要安装软件或联网查询。最后只回复 ANDROID_ACCEPTANCE_OK。";
            chat(prompt, null);
            LocalRuntimeSmoke.require(textChunks > 0, "model must deliver streaming text");
            LocalRuntimeSmoke.require(toolResults >= 2, "model must actually execute tools");
            LocalRuntimeSmoke.require(read("model-proof.txt").equals("model-executed\n"), "model file-write tool changed real bytes");
            LocalRuntimeSmoke.require(read("command-proof.txt").equals("device-command-ok\n"), "model command tool executed on the phone");
            host.pass("Configured real model: streaming response and actual file/command tools");
            host.evidence.append("Observed text chunks: ").append(textChunks).append(", successful tool results: ")
                    .append(toolResults).append(", approved requests: ").append(allowed).append(", rejected requests: ").append(denied).append('\n');
            host.controller.stop();
            host.await(RuntimeController.Phase.STOPPED, 15000);
            host.getTargetContext().startForegroundService(new Intent(host.getTargetContext(), RuntimeService.class));
            host.await(RuntimeController.Phase.RUNNING, 120000);
            exchange();
            JSONObject recovered = host.api("/sessions/resolve/" + session, null, cookie, 200);
            LocalRuntimeSmoke.require(recovered.toString().contains(session), "real conversation resolves after restart");
            int before = textChunks;
            chat("继续同一会话，只读取当前目录的 model-proof.txt 和 command-proof.txt，核对文件真实内容。不要修改文件或操作其他目录。最后回复 RESUME_ACCEPTANCE_OK。", session);
            LocalRuntimeSmoke.require(textChunks > before, "resumed model conversation responded");
            host.pass("Real model conversation and workspace resume after runtime restart");
            result.putString("stream", host.evidence.toString());
            host.finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Never serialize raw provider responses, model/tool content,
            // connection URLs, cookies, config files or credentials.
            result.putString("stream", host.evidence + "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
            host.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(project.resolve(name)), StandardCharsets.UTF_8);
    }

    private void exchange() throws Exception {
        HttpsURLConnection connection = host.connect(host.controller.entryUrl());
        try {
            LocalRuntimeSmoke.require(connection.getResponseCode() == 302, "local auth exchange");
            cookie = connection.getHeaderField("Set-Cookie").split(";", 2)[0];
        } finally { connection.disconnect(); }
    }

    private void chat(String prompt, String resume) throws Exception {
        HttpsURLConnection connection = host.connect(host.controller.origin() + "/chat");
        String requestId = "android-acceptance-" + java.util.UUID.randomUUID();
        try {
            connection.setReadTimeout(120000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Cookie", cookie);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            JSONObject body = new JSONObject().put("message", prompt).put("working_dir", guest)
                    .put("approval_mode", "build").put("request_id", requestId);
            if (resume != null) body.put("session_id", resume);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            LocalRuntimeSmoke.require(status == 200, "model chat HTTP status " + status + "; inspect model configuration in the App");
            boolean terminal = false;
            long total = 0;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    total += line.length();
                    LocalRuntimeSmoke.require(total < 2 * 1024 * 1024, "model acceptance stream size bound");
                    if (!line.startsWith("data:")) continue;
                    JSONObject event = new JSONObject(line.substring(5).trim());
                    switch (event.getString("type")) {
                        case "session_assigned": session = event.getString("session_id"); break;
                        case "runtime_info":
                            host.evidence.append("Model selected by configured runtime (identifier retained in app settings).\n");
                            break;
                        case "text": textChunks++; break;
                        case "tool_result": if (event.optBoolean("success")) toolResults++; break;
                        case "permission_request":
                            boolean accept = approvedAction(event);
                            if (accept) allowed++; else denied++;
                            JSONObject decision = new JSONObject().put("session_id", event.getString("session_id"))
                                    .put("decision", accept ? "allow" : "deny").put("call_id", event.getString("call_id"))
                                    .put("tool_name", event.getString("tool_name"));
                            host.api("/chat/permission", decision, cookie, 200);
                            break;
                        case "user_input_request": throw new IOException("model requested additional user input; inspect App");
                        case "error":
                            String message = event.optString("message").toLowerCase(java.util.Locale.ROOT);
                            StringBuilder category = new StringBuilder();
                            for (String word : new String[]{"signer", "signature", "签名", "官方", "未登录", "认证", "401", "403", "429", "404", "connect", "connection", "连接", "certificate", "证书", "tls", "dns", "resolve", "超时", "timeout", "offline", "quota", "配额", "余额", "not configured", "未配置", "refused", "auth", "模型", "provider", "broken pipe", "network"})
                                if (message.contains(word)) category.append(word).append('|');
                            throw new IOException("configured provider returned an error; safe category keywords: " + category);
                        case "done":
                            session = event.getString("session_id");
                            LocalRuntimeSmoke.require("stopped".equals(event.optString("stop_reason", "stopped")), "model terminal was " + event.optString("stop_reason"));
                            terminal = true;
                            break;
                        case "policy_intervention": throw new IOException("runtime policy blocked the acceptance operation");
                        default: break;
                    }
                    if (terminal) break;
                }
            }
            LocalRuntimeSmoke.require(terminal, "model stream ended without a terminal event");
        } finally {
            connection.disconnect();
            // Ensure a failed test cannot leave an accepted model turn running.
            host.api("/chat/stop", new JSONObject().put("session_id", requestId), cookie, 200);
        }
    }

    private boolean approvedAction(JSONObject event) {
        try {
            JSONObject args = new JSONObject(event.getString("arguments"));
            String tool = event.getString("tool_name").toLowerCase(java.util.Locale.ROOT);
            if (tool.contains("bash") || tool.contains("shell")) return args.optString("command", args.optString("cmd")).equals(command);
            String path = args.optString("file_path", args.optString("path"));
            boolean known = path.equals("model-proof.txt") || path.equals(guest + "/model-proof.txt")
                    || path.equals("command-proof.txt") || path.equals(guest + "/command-proof.txt");
            if (!known) return false;
            if (tool.contains("read")) return true;
            return tool.contains("write") && args.optString("content").equals("model-executed\n");
        } catch (org.json.JSONException malformed) { return false; }
    }
}
