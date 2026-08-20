package com.kaua.ritmo;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean supported = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!supported) return;

        if (isStrictAccessibilityEnabled(context)) {
            try {
                Intent guard = new Intent(context, ProtectionWatchdogService.class)
                        .setAction(ProtectionWatchdogService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(guard);
                else context.startService(guard);
            } catch (Exception ignored) {}
        }

        if (!BlocklistStore.isProtectionEnabled(context)) return;
        if (VpnService.prepare(context) != null) return;

        try {
            Intent service = new Intent(context, DnsBlockVpnService.class)
                    .setAction(DnsBlockVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {}
    }

    private boolean isStrictAccessibilityEnabled(Context context) {
        try {
            int enabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
            );
            if (enabled != 1) return false;

            String services = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (services == null || services.isEmpty()) return false;

            ComponentName expected = new ComponentName(context, StrictBlockAccessibilityService.class);
            for (String raw : services.split(":")) {
                ComponentName current = ComponentName.unflattenFromString(raw);
                if (current != null && expected.equals(current)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
