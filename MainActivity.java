package com.kaua.ritmo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 7001;
    private static final int FILE_REQUEST = 7002;
    private static final int OVERLAY_REQUEST = 7003;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AndroidBridge(this), "RitmoAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {}
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILE_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void startVpnService() {
        Intent intent = new Intent(this, DnsBlockVpnService.class).setAction(DnsBlockVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        refreshWebSoon();
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, DnsBlockVpnService.class).setAction(DnsBlockVpnService.ACTION_STOP);
        startService(intent);
        refreshWebSoon();
    }

    private void requestOverlayThenVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent overlay = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(overlay, OVERLAY_REQUEST);
                return;
            } catch (Exception ignored) {
            }
        }
        requestVpnPermissionNow();
    }

    private void requestVpnPermissionNow() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, VPN_REQUEST);
        else startVpnService();
    }

    private void refreshWebSoon() {
        webView.postDelayed(() -> webView.evaluateJavascript("window.refreshNativeBlocker && refreshNativeBlocker();", null), 450);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWebSoon();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(
                        this,
                        "Sem a permissão sobre outros apps, o bloqueio funciona, mas a tela motivacional não aparece.",
                        Toast.LENGTH_LONG
                ).show();
            }
            requestVpnPermissionNow();
            return;
        }

        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startVpnService();
            else refreshWebSoon();
            return;
        }

        if (requestCode == FILE_REQUEST) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    public final class AndroidBridge {
        private final Context context;
        AndroidBridge(Context context) { this.context = context; }

        @JavascriptInterface
        public boolean isNative() { return true; }

        @JavascriptInterface
        public boolean isVpnActive() { return BlocklistStore.isVpnActive(context); }

        @JavascriptInterface
        public String getBlockedSites() {
            return new JSONArray(BlocklistStore.getSites(context)).toString();
        }

        @JavascriptInterface
        public boolean addBlockedSite(String value) {
            boolean ok = BlocklistStore.addSite(context, value);
            refreshWebSoon();
            return ok;
        }

        @JavascriptInterface
        public boolean removeBlockedSite(String value) {
            boolean ok = BlocklistStore.removeSite(context, value);
            refreshWebSoon();
            return ok;
        }

        @JavascriptInterface
        public void requestVpnPermission() {
            runOnUiThread(MainActivity.this::requestOverlayThenVpn);
        }

        @JavascriptInterface
        public void stopVpn() {
            runOnUiThread(MainActivity.this::stopVpnService);
        }

        @JavascriptInterface
        public void openVpnSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }
    }
}
