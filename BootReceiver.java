package com.kaua.ritmo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BlocklistStore.isProtectionEnabled(context)) return;
        if (VpnService.prepare(context) != null) return;

        try {
            Intent service = new Intent(context, DnsBlockVpnService.class)
                    .setAction(DnsBlockVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {}
    }
}
