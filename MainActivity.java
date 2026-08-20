package com.kaua.ritmo;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
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

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 7001;
    private static final int FILE_REQUEST = 7002;
    private static final int OVERLAY_REQUEST = 7003;
    private static final String CLOUD_PREFS = "ritmo_cloud";
    private static final String KEY_SYNC_ENABLED = "sync_enabled";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private FirebaseAuth auth;
    private FirebaseFirestore firestore;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            FirebaseApp.initializeApp(this);
            auth = FirebaseAuth.getInstance();
            firestore = FirebaseFirestore.getInstance();
        } catch (Exception e) {
            auth = null;
            firestore = null;
        }

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

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                notifyAuthState();
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

    private SharedPreferences cloudPrefs() {
        return getSharedPreferences(CLOUD_PREFS, Context.MODE_PRIVATE);
    }

    private boolean isSyncEnabledInternal() {
        return cloudPrefs().getBoolean(KEY_SYNC_ENABLED, true);
    }

    private void setSyncEnabledInternal(boolean enabled) {
        cloudPrefs().edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply();
    }

    private void callJs(String code) {
        if (!pageReady || webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(code, null));
    }

    private String q(String value) {
        return JSONObject.quote(value == null ? "" : value);
    }

    private void notifyAuthState() {
        JSONObject state = new JSONObject();
        try {
            FirebaseUser user = auth == null ? null : auth.getCurrentUser();
            state.put("firebaseReady", auth != null && firestore != null);
            state.put("signedIn", user != null);
            state.put("syncEnabled", isSyncEnabledInternal());
            if (user != null) {
                state.put("uid", user.getUid());
                state.put("email", user.getEmail() == null ? "" : user.getEmail());
            }
        } catch (Exception ignored) {}
        callJs("window.onRitmoAuthState && window.onRitmoAuthState(" + q(state.toString()) + ");");
    }

    private void authResult(String action, boolean ok, String message) {
        callJs("window.onRitmoAuthResult && window.onRitmoAuthResult(" + q(action) + "," + ok + "," + q(message) + ");");
    }

    private void cloudSaved(boolean ok, String message) {
        callJs("window.onRitmoCloudSaved && window.onRitmoCloudSaved(" + ok + "," + q(message) + ");");
    }

    private void cloudLoaded(boolean ok, boolean exists, String dataJson, List<String> sites, String message) {
        String sitesJson = new JSONArray(sites == null ? new ArrayList<>() : sites).toString();
        callJs("window.onRitmoCloudLoaded && window.onRitmoCloudLoaded(" + ok + "," + exists + "," + q(dataJson) + "," + q(sitesJson) + "," + q(message) + ");");
    }

    private void startVpnService() {
        BlocklistStore.setProtectionEnabled(this, true);
        Intent intent = new Intent(this, DnsBlockVpnService.class).setAction(DnsBlockVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        refreshWebSoon();
    }

    private void stopVpnService() {
        BlocklistStore.setProtectionEnabled(this, false);
        Intent intent = new Intent(this, DnsBlockVpnService.class).setAction(DnsBlockVpnService.ACTION_STOP);
        startService(intent);
        refreshWebSoon();
    }

    private void ensureBlockerRunningIfNeeded() {
        if (!BlocklistStore.isProtectionEnabled(this)) return;
        if (DnsBlockVpnService.isRunningNow()) return;
        if (VpnService.prepare(this) != null) return;
        try {
            startVpnService();
        } catch (Exception ignored) {}
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
            } catch (Exception ignored) {}
        }
        requestVpnPermissionNow();
    }

    private void requestVpnPermissionNow() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, VPN_REQUEST);
        else startVpnService();
    }

    private boolean isStrictAccessibilityEnabledInternal() {
        try {
            int enabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
            );
            if (enabled != 1) return false;

            String services = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (services == null || services.isEmpty()) return false;

            String expected = new ComponentName(
                    this,
                    StrictBlockAccessibilityService.class
            ).flattenToString();

            for (String service : services.split(":")) {
                if (expected.equalsIgnoreCase(service)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void openAccessibilitySettingsInternal() {
        try {
            Toast.makeText(
                    this,
                    "Ative o serviço Ritmo - Modo rígido. Ele verifica a barra de endereço do navegador para reforçar o bloqueio.",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void refreshWebSoon() {
        if (webView == null) return;
        webView.postDelayed(() -> webView.evaluateJavascript("window.refreshNativeBlocker && refreshNativeBlocker();", null), 450);
    }

    private void saveCloudDataInternal(String dataJson, boolean force) {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (firestore == null || user == null) {
            cloudSaved(false, "Entre na sua conta para sincronizar.");
            return;
        }
        if (!force && !isSyncEnabledInternal()) return;

        Map<String, Object> values = new HashMap<>();
        values.put("appData", dataJson == null ? "" : dataJson);
        values.put("blockedSites", BlocklistStore.getSites(this));
        values.put("schemaVersion", 2);
        values.put("email", user.getEmail() == null ? "" : user.getEmail());
        values.put("updatedAt", FieldValue.serverTimestamp());

        firestore.collection("users").document(user.getUid())
                .set(values, SetOptions.merge())
                .addOnSuccessListener(unused -> cloudSaved(true, "Dados sincronizados."))
                .addOnFailureListener(e -> cloudSaved(false, "Sem conexão. As alterações continuam salvas neste aparelho."));
    }

    private void saveBlockedSitesInternal(boolean force) {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (firestore == null || user == null) return;
        if (!force && !isSyncEnabledInternal()) return;

        Map<String, Object> values = new HashMap<>();
        values.put("blockedSites", BlocklistStore.getSites(this));
        values.put("schemaVersion", 2);
        values.put("email", user.getEmail() == null ? "" : user.getEmail());
        values.put("updatedAt", FieldValue.serverTimestamp());

        firestore.collection("users").document(user.getUid())
                .set(values, SetOptions.merge())
                .addOnSuccessListener(unused -> cloudSaved(true, "Sites bloqueados sincronizados."))
                .addOnFailureListener(e -> cloudSaved(false, "Lista salva no aparelho e aguardando conexão."));
    }

    private void loadCloudDataInternal() {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (firestore == null || user == null) {
            cloudLoaded(false, false, "", BlocklistStore.getSites(this), "Entre na sua conta para carregar os dados.");
            return;
        }

        firestore.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        cloudLoaded(true, false, "", BlocklistStore.getSites(this), "Conta sem dados na nuvem.");
                        return;
                    }
                    String appData = snapshot.getString("appData");
                    List<String> sites = new ArrayList<>();
                    Object rawSites = snapshot.get("blockedSites");
                    if (rawSites instanceof List<?>) {
                        for (Object item : (List<?>) rawSites) {
                            if (item != null) sites.add(String.valueOf(item));
                        }
                    }
                    BlocklistStore.replaceSites(this, sites);
                    refreshWebSoon();
                    cloudLoaded(true, true, appData == null ? "" : appData, BlocklistStore.getSites(this), "Dados carregados.");
                })
                .addOnFailureListener(e -> cloudLoaded(false, false, "", BlocklistStore.getSites(this), "Não foi possível acessar a nuvem agora. Seus dados locais continuam disponíveis."));
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureBlockerRunningIfNeeded();
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
        public boolean isFirebaseReady() { return auth != null && firestore != null; }

        @JavascriptInterface
        public String getCurrentUserJson() {
            JSONObject obj = new JSONObject();
            try {
                FirebaseUser user = auth == null ? null : auth.getCurrentUser();
                obj.put("firebaseReady", auth != null && firestore != null);
                obj.put("signedIn", user != null);
                obj.put("syncEnabled", isSyncEnabledInternal());
                if (user != null) {
                    obj.put("uid", user.getUid());
                    obj.put("email", user.getEmail() == null ? "" : user.getEmail());
                }
            } catch (Exception ignored) {}
            return obj.toString();
        }

        @JavascriptInterface
        public void signIn(String email, String password) {
            runOnUiThread(() -> {
                if (auth == null) {
                    authResult("signin", false, "Firebase não foi iniciado.");
                    return;
                }
                String cleanEmail = email == null ? "" : email.trim();
                auth.signInWithEmailAndPassword(cleanEmail, password == null ? "" : password)
                        .addOnSuccessListener(result -> {
                            authResult("signin", true, "Login realizado.");
                            notifyAuthState();
                        })
                        .addOnFailureListener(e -> authResult("signin", false, "Não foi possível entrar. Confira o e-mail, a senha e a conexão."));
            });
        }

        @JavascriptInterface
        public void createAccount(String email, String password) {
            runOnUiThread(() -> {
                if (auth == null) {
                    authResult("create", false, "Firebase não foi iniciado.");
                    return;
                }
                String cleanEmail = email == null ? "" : email.trim();
                auth.createUserWithEmailAndPassword(cleanEmail, password == null ? "" : password)
                        .addOnSuccessListener(result -> {
                            authResult("create", true, "Conta criada.");
                            notifyAuthState();
                        })
                        .addOnFailureListener(e -> authResult("create", false, "Não foi possível criar a conta. Use um e-mail válido e uma senha com pelo menos 6 caracteres."));
            });
        }

        @JavascriptInterface
        public void sendPasswordReset(String email) {
            runOnUiThread(() -> {
                if (auth == null) {
                    authResult("reset", false, "Firebase não foi iniciado.");
                    return;
                }
                String cleanEmail = email == null ? "" : email.trim();
                auth.sendPasswordResetEmail(cleanEmail)
                        .addOnSuccessListener(unused -> authResult("reset", true, "E-mail de recuperação enviado."))
                        .addOnFailureListener(e -> authResult("reset", false, "Não foi possível enviar a recuperação. Confira o e-mail e a conexão."));
            });
        }

        @JavascriptInterface
        public void signOut() {
            runOnUiThread(() -> {
                if (auth != null) auth.signOut();
                notifyAuthState();
            });
        }

        @JavascriptInterface
        public void loadCloudData() {
            loadCloudDataInternal();
        }

        @JavascriptInterface
        public void saveCloudData(String dataJson) {
            saveCloudDataInternal(dataJson, false);
        }

        @JavascriptInterface
        public void syncNow(String dataJson) {
            setSyncEnabledInternal(true);
            saveCloudDataInternal(dataJson, true);
            notifyAuthState();
        }

        @JavascriptInterface
        public boolean isCloudSyncEnabled() {
            return isSyncEnabledInternal();
        }

        @JavascriptInterface
        public void setCloudSyncEnabled(boolean enabled) {
            setSyncEnabledInternal(enabled);
            notifyAuthState();
        }

        @JavascriptInterface
        public boolean isVpnActive() {
            return DnsBlockVpnService.isRunningNow() && BlocklistStore.isVpnActive(context);
        }

        @JavascriptInterface
        public boolean isProtectionEnabled() {
            return BlocklistStore.isProtectionEnabled(context);
        }

        @JavascriptInterface
        public String getBlockedSites() {
            return new JSONArray(BlocklistStore.getSites(context)).toString();
        }

        @JavascriptInterface
        public boolean addBlockedSite(String value) {
            boolean ok = BlocklistStore.addSite(context, value);
            if (ok) saveBlockedSitesInternal(false);
            refreshWebSoon();
            return ok;
        }

        @JavascriptInterface
        public boolean removeBlockedSite(String value) {
            boolean ok = BlocklistStore.removeSite(context, value);
            if (ok) saveBlockedSitesInternal(false);
            refreshWebSoon();
            return ok;
        }

        @JavascriptInterface
        public void replaceBlockedSites(String json, boolean syncCloud) {
            try {
                JSONArray array = new JSONArray(json == null ? "[]" : json);
                List<String> sites = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    if (!array.isNull(i)) sites.add(array.optString(i, ""));
                }
                BlocklistStore.replaceSites(context, sites);
                refreshWebSoon();
                if (syncCloud) saveBlockedSitesInternal(false);
            } catch (Exception ignored) {}
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
        public boolean isStrictModeEnabled() {
            return isStrictAccessibilityEnabledInternal();
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(MainActivity.this::openAccessibilitySettingsInternal);
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
