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

public final class BlocklistStore {
    private static final String PREFS = "ritmo_blocker";
    private static final String KEY_SITES = "blocked_sites";
    private static final String KEY_ACTIVE = "vpn_active";
    private static final String KEY_PROTECTION_ENABLED = "protection_enabled";
    private static final String STATE_FILE = "ritmo_blocker_state_v2.txt";

    private BlocklistStore() {}

    private static File stateFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), STATE_FILE);
    }

    private static final class State {
        boolean protectionEnabled;
        final Set<String> sites = new HashSet<>();
    }

    private static State readState(Context context) {
        File file = stateFile(context);
        if (!file.exists()) return migrateFromPreferences(context);

        State state = new State();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    state.protectionEnabled = "protection=1".equals(line.trim());
                    continue;
                }
                String domain = normalizeDomain(line);
                if (domain != null) state.sites.add(domain);
            }
            return state;
        } catch (Exception ignored) {
            return migrateFromPreferences(context);
        }
    }

    private static State migrateFromPreferences(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        State state = new State();
        state.protectionEnabled = sp.getBoolean(KEY_PROTECTION_ENABLED, false);
        Set<String> raw = sp.getStringSet(KEY_SITES, Collections.emptySet());
        if (raw != null) {
            for (String value : raw) {
                String domain = normalizeDomain(value);
                if (domain != null) state.sites.add(domain);
            }
        }
        writeStateFile(context, state);
        return state;
    }

    private static void writeStateFile(Context context, State state) {
        File target = stateFile(context);
        File tmp = new File(target.getParentFile(), STATE_FILE + ".tmp");
        List<String> sorted = new ArrayList<>(state.sites);
        Collections.sort(sorted);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp, false), StandardCharsets.UTF_8))) {
            writer.write(state.protectionEnabled ? "protection=1" : "protection=0");
            writer.newLine();
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

    private static void mirrorPrefs(Context context, State state) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_SITES, new HashSet<>(state.sites))
                .putBoolean(KEY_PROTECTION_ENABLED, state.protectionEnabled)
                .commit();
    }

    public static synchronized List<String> getSites(Context context) {
        List<String> out = new ArrayList<>(readState(context).sites);
        Collections.sort(out);
        return out;
    }

    public static synchronized boolean addSite(Context context, String input) {
        String domain = normalizeDomain(input);
        if (domain == null) return false;
        State state = readState(context);
        boolean changed = state.sites.add(domain);
        if (changed) {
            writeStateFile(context, state);
            mirrorPrefs(context, state);
        }
        return true;
    }

    public static synchronized boolean removeSite(Context context, String input) {
        String domain = normalizeDomain(input);
        if (domain == null) return false;
        State state = readState(context);
        boolean changed = state.sites.remove(domain);
        if (changed) {
            writeStateFile(context, state);
            mirrorPrefs(context, state);
        }
        return changed;
    }

    public static synchronized void replaceSites(Context context, Collection<String> inputs) {
        State state = readState(context);
        state.sites.clear();
        if (inputs != null) {
            for (String input : inputs) {
                String domain = normalizeDomain(input);
                if (domain != null) state.sites.add(domain);
            }
        }
        writeStateFile(context, state);
        mirrorPrefs(context, state);
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
                .edit().putBoolean(KEY_ACTIVE, active).apply();
    }

    public static synchronized boolean isProtectionEnabled(Context context) {
        return readState(context).protectionEnabled;
    }

    public static synchronized void setProtectionEnabled(Context context, boolean enabled) {
        State state = readState(context);
        state.protectionEnabled = enabled;
        writeStateFile(context, state);
        mirrorPrefs(context, state);
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
