package com.kaua.ritmo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Armazenamento simples compartilhado entre o processo da interface e o
 * processo :vpn. Os marcadores de proteção/atividade ficam em arquivos
 * separados para evitar que um processo sobrescreva a lista do outro.
 */
public final class BlocklistStore {
    private static final String PREFS = "ritmo_blocker";
    private static final String KEY_SITES = "blocked_sites";
    private static final String KEY_ACTIVE = "vpn_active";
    private static final String KEY_PROTECTION_ENABLED = "protection_enabled";

    private static final String SITES_FILE = "ritmo_blocked_sites_v4.txt";
    private static final String PROTECTION_FILE = "ritmo_protection_enabled_v4.flag";
    private static final String ACTIVE_FILE = "ritmo_vpn_active_v4.flag";
    private static final String MIGRATION_FILE = "ritmo_blocker_v4_migrated.flag";

    private BlocklistStore() {}

    private static File file(Context context, String name) {
        return new File(context.getApplicationContext().getFilesDir(), name);
    }

    private static synchronized void ensureMigrated(Context context) {
        File migrated = file(context, MIGRATION_FILE);
        if (migrated.exists()) return;

        Set<String> sites = new HashSet<>();
        boolean protection = false;

        // Primeiro tenta migrar o formato usado pelas versões V4/V5 anteriores.
        for (String oldName : new String[]{"ritmo_blocker_state_v3.txt", "ritmo_blocker_state_v2.txt"}) {
            File old = file(context, oldName);
            if (!old.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(old), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim();
                    if (value.startsWith("protection=")) {
                        protection = "protection=1".equals(value);
                    } else if (!value.startsWith("active=")) {
                        String domain = normalizeDomain(value);
                        if (domain != null) sites.add(domain);
                    }
                }
                break;
            } catch (Exception ignored) {}
        }

        // Preferences antigas servem como fallback e também preservam a lista
        // caso o arquivo antigo não esteja disponível.
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sites.isEmpty()) {
            Set<String> oldSites = sp.getStringSet(KEY_SITES, Collections.emptySet());
            if (oldSites != null) {
                for (String raw : oldSites) {
                    String domain = normalizeDomain(raw);
                    if (domain != null) sites.add(domain);
                }
            }
        }
        if (!protection) protection = sp.getBoolean(KEY_PROTECTION_ENABLED, false);

        writeSites(context, sites);
        setFlag(file(context, PROTECTION_FILE), protection);
        setFlag(file(context, ACTIVE_FILE), false);
        try { migrated.createNewFile(); } catch (Exception ignored) {}
        mirrorPrefs(context, sites, protection, false);
    }

    private static Set<String> readSites(Context context) {
        ensureMigrated(context);
        Set<String> sites = new HashSet<>();
        File source = file(context, SITES_FILE);
        if (!source.exists()) return sites;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = normalizeDomain(line);
                if (domain != null) sites.add(domain);
            }
        } catch (Exception ignored) {}
        return sites;
    }

    private static void writeSites(Context context, Collection<String> values) {
        File target = file(context, SITES_FILE);
        File tmp = file(context, SITES_FILE + ".tmp");
        List<String> sorted = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String domain = normalizeDomain(value);
                if (domain != null && !sorted.contains(domain)) sorted.add(domain);
            }
        }
        Collections.sort(sorted);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp, false), StandardCharsets.UTF_8))) {
            for (String domain : sorted) {
                writer.write(domain);
                writer.newLine();
            }
            writer.flush();
        } catch (Exception ignored) {
            return;
        }

        if (target.exists() && !target.delete()) return;
        if (!tmp.renameTo(target)) {
            try (FileInputStream in = new FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                out.flush();
            } catch (Exception ignored) {}
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void setFlag(File marker, boolean enabled) {
        if (enabled) {
            if (!marker.exists()) {
                try { marker.createNewFile(); } catch (Exception ignored) {}
            }
        } else if (marker.exists()) {
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
        }
    }

    private static void mirrorPrefs(Context context, Collection<String> sites, boolean protection, boolean active) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_SITES, new HashSet<>(sites))
                .putBoolean(KEY_PROTECTION_ENABLED, protection)
                .putBoolean(KEY_ACTIVE, active)
                .commit();
    }

    public static synchronized List<String> getSites(Context context) {
        List<String> out = new ArrayList<>(readSites(context));
        Collections.sort(out);
        return out;
    }

    public static synchronized boolean addSite(Context context, String input) {
        String domain = normalizeDomain(input);
        if (domain == null) return false;
        Set<String> sites = readSites(context);
        sites.add(domain);
        writeSites(context, sites);
        mirrorPrefs(context, sites, isProtectionEnabled(context), isVpnActive(context));
        return true;
    }

    public static synchronized boolean removeSite(Context context, String input) {
        String domain = normalizeDomain(input);
        if (domain == null) return false;
        Set<String> sites = readSites(context);
        boolean changed = sites.remove(domain);
        if (changed) {
            writeSites(context, sites);
            mirrorPrefs(context, sites, isProtectionEnabled(context), isVpnActive(context));
        }
        return changed;
    }

    public static synchronized void replaceSites(Context context, Collection<String> inputs) {
        Set<String> sites = new HashSet<>();
        if (inputs != null) {
            for (String input : inputs) {
                String domain = normalizeDomain(input);
                if (domain != null) sites.add(domain);
            }
        }
        writeSites(context, sites);
        mirrorPrefs(context, sites, isProtectionEnabled(context), isVpnActive(context));
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
        ensureMigrated(context);
        return file(context, ACTIVE_FILE).exists();
    }

    public static synchronized void setVpnActive(Context context, boolean active) {
        ensureMigrated(context);
        setFlag(file(context, ACTIVE_FILE), active);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ACTIVE, active).commit();
    }

    public static boolean isProtectionEnabled(Context context) {
        ensureMigrated(context);
        return file(context, PROTECTION_FILE).exists();
    }

    public static synchronized void setProtectionEnabled(Context context, boolean enabled) {
        ensureMigrated(context);
        setFlag(file(context, PROTECTION_FILE), enabled);
        if (!enabled) setFlag(file(context, ACTIVE_FILE), false);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PROTECTION_ENABLED, enabled)
                .putBoolean(KEY_ACTIVE, enabled && isVpnActive(context))
                .commit();
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

        try { value = IDN.toASCII(value); } catch (Exception e) { return null; }

        if (!value.matches("^[a-z0-9.-]+$") || value.startsWith(".") || value.endsWith(".") || !value.contains(".")) {
            return null;
        }
        return value;
    }
}
