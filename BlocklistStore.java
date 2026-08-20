package com.kaua.ritmo;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BlocklistStore {
    private static final String PREFS = "ritmo_blocker";
    private static final String KEY_SITES = "blocked_sites";
    private static final String KEY_ACTIVE = "vpn_active";
    private static final String KEY_PROTECTION_ENABLED = "protection_enabled";

    private BlocklistStore() {}

    public static synchronized List<String> getSites(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(KEY_SITES, Collections.emptySet());
        List<String> out = new ArrayList<>(raw == null ? Collections.emptySet() : raw);
        Collections.sort(out);
        return out;
    }

    public static synchronized boolean addSite(Context context, String input) {
        String domain = normalizeDomain(input);
        if (domain == null) return false;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_SITES, Collections.emptySet()));
        set.add(domain);
        sp.edit().putStringSet(KEY_SITES, set).apply();
        return true;
    }

    public static synchronized boolean removeSite(Context context, String input) {
        String domain = normalizeDomain(input);
        if (domain == null) return false;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_SITES, Collections.emptySet()));
        boolean changed = set.remove(domain);
        sp.edit().putStringSet(KEY_SITES, set).apply();
        return changed;
    }

    public static synchronized void replaceSites(Context context, Collection<String> inputs) {
        Set<String> set = new HashSet<>();
        if (inputs != null) {
            for (String input : inputs) {
                String domain = normalizeDomain(input);
                if (domain != null) set.add(domain);
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_SITES, set)
                .apply();
    }

    public static boolean isBlocked(Context context, String host) {
        String domain = normalizeDomain(host);
        if (domain == null) return false;
        for (String blocked : getSites(context)) {
            if (domain.equals(blocked) || domain.endsWith("." + blocked)) return true;
        }
        return false;
    }

    public static boolean isVpnActive(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false);
    }

    public static void setVpnActive(Context context, boolean active) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, active)
                .apply();
    }

    public static boolean isProtectionEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PROTECTION_ENABLED, false);
    }

    public static void setProtectionEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PROTECTION_ENABLED, enabled)
                .apply();
    }

    public static String normalizeDomain(String input) {
        if (input == null) return null;
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (value.startsWith("*.")) value = value.substring(2);

        try {
            URI uri = value.contains("://") ? URI.create(value) : URI.create("https://" + value);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) value = host;
        } catch (Exception ignored) {
            int slash = value.indexOf('/');
            if (slash >= 0) value = value.substring(0, slash);
        }

        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) value = value.substring(0, colon);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty() || value.contains(" ")) return null;

        try {
            value = IDN.toASCII(value);
        } catch (Exception e) {
            return null;
        }

        if (!value.matches("^[a-z0-9.-]+$") || value.startsWith(".") || value.endsWith(".") || !value.contains(".")) {
            return null;
        }
        return value;
    }
}
