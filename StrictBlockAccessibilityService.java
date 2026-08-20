package com.kaua.ritmo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class StrictBlockAccessibilityService extends AccessibilityService {
    private static final Set<String> KNOWN_BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.google.android.apps.chrome",
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly",
            "com.microsoft.emmx",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.vivaldi.browser",
            "com.vivaldi.browser.snapshot",
            "com.kiwibrowser.browser",
            "org.chromium.chrome"
    ));

    private static final String[] COMMON_URL_IDS = {
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
            "com.chrome.canary:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.brave.browser_beta:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "org.chromium.chrome:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/location_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view",
            "com.opera.browser:id/url_field",
            "com.vivaldi.browser:id/url_bar"
    };

    private long lastBlockedAt = 0L;
    private String lastBlockedDomain = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                info.notificationTimeout = 80;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }

        startWatchdog();
    }

    private void startWatchdog() {
        try {
            Intent guard = new Intent(this, ProtectionWatchdogService.class)
                    .setAction(ProtectionWatchdogService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(guard);
            else startService(guard);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !BlocklistStore.isProtectionEnabled(this)) return;
        if (BlocklistStore.getSites(this).isEmpty()) return;

        CharSequence packageNameCs = event.getPackageName();
        if (packageNameCs == null) return;
        String packageName = packageNameCs.toString();
        if (!looksLikeBrowserPackage(packageName)) return;

        String blocked = null;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            blocked = findBlockedFromKnownUrlBars(root);
            if (blocked == null) blocked = findBlockedFromEditableAddressFields(root);
        }

        if (blocked == null && event.getSource() != null) {
            AccessibilityNodeInfo src = event.getSource();
            if (src.isEditable() || looksLikeUrlField(src.getViewIdResourceName())) {
                blocked = findBlockedInText(src.getText());
                if (blocked == null) blocked = findBlockedInText(src.getContentDescription());
            }
        }

        if (blocked == null) return;
        blockNow(blocked);
    }

    private boolean looksLikeBrowserPackage(String pkg) {
        if (KNOWN_BROWSERS.contains(pkg)) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("browser") || p.contains("chrome") || p.contains("firefox") || p.contains("brave") || p.contains("vivaldi") || p.contains("opera");
    }

    private String findBlockedFromKnownUrlBars(AccessibilityNodeInfo root) {
        for (String id : COMMON_URL_IDS) {
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null) continue;
                    String blocked = findBlockedInText(node.getText());
                    if (blocked == null) blocked = findBlockedInText(node.getContentDescription());
                    if (blocked != null) return blocked;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String findBlockedFromEditableAddressFields(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        while (!queue.isEmpty() && visited < 350) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            try {
                boolean likelyAddress = node.isEditable() || looksLikeUrlField(node.getViewIdResourceName());
                if (likelyAddress) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    boolean nearTop = bounds.top < Math.max(500, screenHeight / 3);
                    if (nearTop) {
                        String blocked = findBlockedInText(node.getText());
                        if (blocked == null) blocked = findBlockedInText(node.getContentDescription());
                        if (blocked != null) return blocked;
                    }
                }

                int children = node.getChildCount();
                for (int i = 0; i < children; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean looksLikeUrlField(String viewId) {
        if (viewId == null) return false;
        String id = viewId.toLowerCase(Locale.ROOT);
        return id.contains("url_bar") || id.contains("url_field") || id.contains("address") || id.contains("location_bar") || id.contains("toolbar_url");
    }

    private String findBlockedInText(CharSequence value) {
        if (value == null) return null;
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;

        for (String blocked : BlocklistStore.getSites(this)) {
            String b = blocked.toLowerCase(Locale.ROOT);
            if (containsDomain(text, b)) return blocked;
        }
        return null;
    }

    private boolean containsDomain(String text, String domain) {
        int from = 0;
        while (true) {
            int idx = text.indexOf(domain, from);
            if (idx < 0) return false;
            int before = idx - 1;
            int after = idx + domain.length();
            boolean leftOk = before < 0 || !isDomainChar(text.charAt(before));
            boolean rightOk = after >= text.length() || !isDomainChar(text.charAt(after));
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }

    private boolean isDomainChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    private void blockNow(String domain) {
        long now = System.currentTimeMillis();
        if (domain.equals(lastBlockedDomain) && now - lastBlockedAt < 1800) return;
        lastBlockedAt = now;
        lastBlockedDomain = domain;

        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception ignored) {}

        if (Settings.canDrawOverlays(this)) {
            try {
                Intent overlay = new Intent(this, BlockedOverlayService.class);
                overlay.putExtra(BlockedOverlayService.EXTRA_DOMAIN, domain);
                startService(overlay);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        startWatchdog();
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        startWatchdog();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        startWatchdog();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        startWatchdog();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        startWatchdog();
    }
}
