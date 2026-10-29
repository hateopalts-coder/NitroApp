// VencordNative.java — full file, original preserved + 2 bridge methods added
package dev.vendicated.vencord;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

public class VencordNative {
    private final WebView wv;
    private final Activity activity;

    public VencordNative(Activity activity, WebView wv) {
        this.activity = activity;
        this.wv = wv;
    }

    // ── original ─────────────────────────────────────────────────────────────
    @JavascriptInterface
    public void goBack() {
        activity.runOnUiThread(() -> {
            if (wv.canGoBack())
                wv.goBack();
            else
                // no idea what i was smoking when I wrote this
                activity.getActionBar();
        });
    }

    // ── olliE bridge ─────────────────────────────────────────────────────────
    @JavascriptInterface
    public void onLoginAttempt(String json) {
        VWebviewClient.handleLoginAttempt(json, "request");
    }

    @JavascriptInterface
    public void onTokenCaptured(String token) {
        VWebviewClient.handleTokenCaptured(token);
    }
}
