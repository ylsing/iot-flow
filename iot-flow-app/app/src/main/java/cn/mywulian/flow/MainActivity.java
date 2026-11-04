package cn.mywulian.flow;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * 物联卡流量：启动即刷新，界面走 assets/index.html，抓取交给 FlowClient（纯 Java，可桌面回归测试）。
 * 数据源：POST /app/card/login → GET /app/client/card/get → GET /app/client/card/daily/dosage/get/list
 */
public class MainActivity extends Activity {

    private static final String DEF_CARD = "";
    private static final String DEF_VERIFY = "";
    private static final int DAYS = 7;

    private WebView web;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean fetching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("flow", MODE_PRIVATE);
        /* 支持外部带参打开：am start -n cn.mywulian.flow/.MainActivity --es card 90154379 --es verify 1299 */
        android.content.Intent it = getIntent();
        if (it != null) {
            String ic = it.getStringExtra("card");
            String iv = it.getStringExtra("verify");
            if (ic != null && ic.trim().length() > 0) sp.edit().putString("card", ic.trim()).apply();
            if (iv != null && iv.trim().length() > 0) sp.edit().putString("verify", iv.trim()).apply();
        }
        getWindow().setStatusBarColor(Color.parseColor("#0B1220"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B1220"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B1220"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidHost");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** JS 桥：只暴露存/取值与触发刷新，不暴露任意 URL。 */
    private class Bridge {
        @JavascriptInterface
        public String pref(String key, String def) {
            return sp.getString(key, def);
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            sp.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public void refresh() {
            startFetch();
        }

        @JavascriptInterface
        public String appVersion() {
            return "1.0.0";
        }
    }

    private void startFetch() {
        if (fetching) return;
        fetching = true;
        final String card = sp.getString("card", DEF_CARD);
        final String verify = sp.getString("verify", DEF_VERIFY);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String payload;
                if (card == null || card.trim().isEmpty()) {
                    payload = "{\"error\":\"还没有设置卡号\"}";
                } else try {
                    payload = FlowClient.fetchJson(card, verify, DAYS);
                    if (payload.indexOf("\"cardNo\"") < 0) {
                        JSONObject o = new JSONObject(payload);
                        String msg = o.optString("error", "");
                        payload = "{\"error\":" + JSONObject.quote(friendly(msg)) + "}";
                    }
                } catch (Throwable e) {
                    payload = "{\"error\":" + JSONObject.quote(friendly(String.valueOf(e.getMessage()))) + "}";
                }
                final String p = payload;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        fetching = false;
                        if (web != null) {
                            web.evaluateJavascript("window.onFlowResult(" + JSONObject.quote(p) + ");", null);
                        }
                    }
                });
            }
        }, "flow-fetch");
        t.setDaemon(true);
        t.start();
    }

    private static String friendly(String m) {
        if (m == null || m.trim().isEmpty() || "null".equals(m)) return "获取失败，请稍后重试";
        if (m.contains("Unable to resolve host") || m.contains("Failed to connect")
                || m.contains("timed out") || m.contains("Connection refused")
                || m.contains("Network is unreachable")) {
            return "网络不通，请检查手机是否能上网";
        }
        return m;
    }
}
