package com.kaua.ritmo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VPN local do Ritmo.
 *
 * Ela funciona em split-tunnel: somente o DNS interno do Ritmo e os IPs
 * associados aos domínios bloqueados entram no túnel. O restante da Internet
 * continua saindo normalmente pelo Android, sem ser enviado a nenhum servidor
 * do Ritmo.
 *
 * O bloqueio tem duas camadas:
 * 1) DNS: consultas para domínios bloqueados recebem NXDOMAIN.
 * 2) IP: o serviço resolve os domínios bloqueados por conta própria e cria
 *    rotas /32 e /128 para os IPs encontrados. Pacotes para esses IPs são
 *    capturados e descartados, o que também ajuda quando o navegador usa cache
 *    ou DNS Seguro/DoH.
 */
public class DnsBlockVpnService extends VpnService {
    public static final String ACTION_START = "com.kaua.ritmo.START_BLOCKER";
    public static final String ACTION_STOP = "com.kaua.ritmo.STOP_BLOCKER";
    public static final String ACTION_REBUILD = "com.kaua.ritmo.REBUILD_BLOCKER";

    private static final String VPN_IPV4 = "10.111.222.1";
    private static final String DNS_IPV4 = "10.111.222.2";
    private static final String VPN_IPV6 = "fd66:7269:746d:6f::1";
    private static final String DNS_IPV6 = "fd66:7269:746d:6f::2";

    private static final String CHANNEL_ID = "ritmo_blocker";
    private static final int NOTIFICATION_ID = 41;
    private static final long ROUTE_REFRESH_MS = 15L * 60L * 1000L;
    private static final int MAX_DYNAMIC_ROUTES = 48;
    private static final int MAX_RECENT_DOMAINS = 12;
    private static final String RESOLVER_BLOCK_MARKER = "__ritmo_resolver__";
    private static final String[] PUBLIC_RESOLVER_IPS = new String[]{
            "1.1.1.1", "1.0.0.1",
            "8.8.8.8", "8.8.4.4",
            "9.9.9.9", "149.112.112.112",
            "94.140.14.14", "94.140.15.15",
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2620:fe::fe", "2620:fe::9"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger(0);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> recentDynamicResolve = new ConcurrentHashMap<>();
    private final Object dynamicRouteLock = new Object();
    private final LinkedHashMap<String, String> dynamicRouteCache = new LinkedHashMap<>();
    private final LinkedHashSet<String> recentRouteDomains = new LinkedHashSet<>();

    private volatile boolean intentionalStop;
    private volatile boolean rebuilding;
    private volatile ParcelFileDescriptor tun;
    private volatile Thread worker;
    private volatile Thread resolverWorker;
    private volatile long lastOverlayAt;
    private volatile String lastOverlayDomain = "";

    private volatile Map<String, String> ipToDomain = new LinkedHashMap<>();

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            if (!intentionalStop && BlocklistStore.isProtectionEnabled(DnsBlockVpnService.this)) {
                refreshRecentRoutesAsync();
                mainHandler.postDelayed(this, ROUTE_REFRESH_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        BlocklistStore.setVpnActive(this, false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            intentionalStop = true;
            BlocklistStore.setProtectionEnabled(this, false);
            mainHandler.removeCallbacks(periodicRefresh);
            stopVpnInternal();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null && !BlocklistStore.isProtectionEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        intentionalStop = false;
        BlocklistStore.setProtectionEnabled(this, true);
        startInForeground();

        if (ACTION_REBUILD.equals(action)) rebuildVpnAsync();
        else if (tun == null) rebuildVpnAsync();

        mainHandler.removeCallbacks(periodicRefresh);
        mainHandler.postDelayed(periodicRefresh, ROUTE_REFRESH_MS);
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Bloqueio de sites",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantém a VPN local de bloqueio do Ritmo ativa.");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Ritmo protegendo sua navegação")
                .setContentText("VPN local ativa • DNS + IP dinâmico")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private synchronized void rebuildVpnAsync() {
        if (intentionalStop || rebuilding) return;
        rebuilding = true;

        resolverWorker = new Thread(() -> {
            try {
                pruneDynamicRoutes();
                Map<String, String> routes = snapshotDynamicRoutes();
                if (!intentionalStop) establishVpn(routes);
            } finally {
                rebuilding = false;
            }
        }, "RitmoVpnRebuild");
        resolverWorker.start();
    }

    private void establishVpn(Map<String, String> resolvedRoutes) {
        establishVpnInternal(resolvedRoutes, true, true);
    }

    private void establishVpnInternal(Map<String, String> resolvedRoutes, boolean includeResolverBlocks, boolean allowFallback) {
        ParcelFileDescriptor newTun = null;
        try {
            Builder builder = new Builder()
                    .setSession("Ritmo - Bloqueio de sites")
                    .setMtu(1500)
                    .addAddress(VPN_IPV4, 32)
                    .addDnsServer(DNS_IPV4)
                    .addRoute(DNS_IPV4, 32)
                    .setBlocking(true);

            boolean ipv6Enabled = false;
            try {
                builder.addAddress(VPN_IPV6, 128)
                        .addDnsServer(DNS_IPV6)
                        .addRoute(DNS_IPV6, 128);
                ipv6Enabled = true;
            } catch (Exception ignored) {}

            int added = 0;
            LinkedHashMap<String, String> acceptedRoutes = new LinkedHashMap<>();

            if (includeResolverBlocks) {
                for (String ip : PUBLIC_RESOLVER_IPS) {
                    try {
                        InetAddress address = InetAddress.getByName(ip);
                        boolean v6 = address.getAddress().length == 16;
                        if (v6 && !ipv6Enabled) continue;
                        builder.addRoute(address.getHostAddress(), v6 ? 128 : 32);
                        acceptedRoutes.put(address.getHostAddress(), RESOLVER_BLOCK_MARKER);
                    } catch (Exception ignored) {}
                }
            }

            for (Map.Entry<String, String> entry : resolvedRoutes.entrySet()) {
                if (added >= MAX_DYNAMIC_ROUTES) break;
                String ip = entry.getKey();
                try {
                    InetAddress address = InetAddress.getByName(ip);
                    boolean v6 = address.getAddress().length == 16;
                    if (v6 && !ipv6Enabled) continue;
                    builder.addRoute(ip, v6 ? 128 : 32);
                    acceptedRoutes.put(address.getHostAddress(), entry.getValue());
                    added++;
                } catch (Exception ignored) {}
            }

            newTun = builder.establish();
            if (newTun == null) throw new IOException("VPN não pôde ser estabelecida");

            final ParcelFileDescriptor workerTun = newTun;
            int gen = generation.incrementAndGet();
            ParcelFileDescriptor oldTun = tun;
            tun = workerTun;
            ipToDomain = acceptedRoutes;
            BlocklistStore.setVpnActive(this, true);

            Thread newWorker = new Thread(
                    () -> runLoop(workerTun, gen),
                    "RitmoVpnFilter-" + gen
            );
            worker = newWorker;
            newWorker.start();

            if (oldTun != null) {
                try { oldTun.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (newTun != null) {
                try { newTun.close(); } catch (Exception ignored) {}
            }
            // Se alguma rota dinâmica ou de DNS seguro causar incompatibilidade na ROM,
            // cai imediatamente para uma VPN DNS mínima em vez de deixar a VPN sempre ativa quebrada.
            if (allowFallback && (!resolvedRoutes.isEmpty() || includeResolverBlocks)) {
                establishVpnInternal(new LinkedHashMap<>(), false, false);
                return;
            }
            if (tun == null) {
                BlocklistStore.setVpnActive(this, false);
                scheduleRecovery();
            }
        }
    }

    private void runLoop(ParcelFileDescriptor localTun, int gen) {
        try (FileInputStream in = new FileInputStream(localTun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(localTun.getFileDescriptor())) {

            byte[] packet = new byte[32767];
            while (!intentionalStop && gen == generation.get()) {
                int len = in.read(packet);
                if (len <= 0) continue;
                byte[] copy = Arrays.copyOf(packet, len);
                byte[] response = processPacket(copy);
                if (response != null) {
                    out.write(response);
                    out.flush();
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (gen == generation.get() && !intentionalStop) {
                BlocklistStore.setVpnActive(this, false);
                if (BlocklistStore.isProtectionEnabled(this)) scheduleRecovery();
            }
        }
    }

    private byte[] processPacket(byte[] ipPacket) {
        try {
            if (ipPacket.length < 1) return null;
            int version = (ipPacket[0] >> 4) & 0x0F;
            if (version == 4) return processIpv4(ipPacket);
            if (version == 6) return processIpv6(ipPacket);
        } catch (Exception ignored) {}
        return null;
    }

    private byte[] processIpv4(byte[] packet) throws Exception {
        if (packet.length < 20) return null;
        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || packet.length < ihl) return null;

        String dst = InetAddress.getByAddress(Arrays.copyOfRange(packet, 16, 20)).getHostAddress();
        if (DNS_IPV4.equals(dst)) return processIpv4UdpDns(packet, ihl);

        String domain = ipToDomain.get(dst);
        if (domain != null) {
            if (!RESOLVER_BLOCK_MARKER.equals(domain)) showBlockedOverlay(domain);
            return null;
        }
        return null;
    }

    private byte[] processIpv6(byte[] packet) throws Exception {
        if (packet.length < 40) return null;
        String dst = InetAddress.getByAddress(Arrays.copyOfRange(packet, 24, 40)).getHostAddress();
        String normalizedDns = InetAddress.getByName(DNS_IPV6).getHostAddress();
        if (normalizedDns.equals(dst)) return processIpv6UdpDns(packet);

        String domain = ipToDomain.get(dst);
        if (domain != null) {
            if (!RESOLVER_BLOCK_MARKER.equals(domain)) showBlockedOverlay(domain);
            return null;
        }
        return null;
    }

    private byte[] processIpv4UdpDns(byte[] packet, int ihl) {
        if (packet.length < ihl + 8) return null;
        if ((packet[9] & 0xFF) != 17) return null;

        int srcPort = u16(packet, ihl);
        int dstPort = u16(packet, ihl + 2);
        if (dstPort != 53) return null;

        int udpLength = u16(packet, ihl + 4);
        int dnsOffset = ihl + 8;
        int dnsLength = Math.min(Math.max(0, udpLength - 8), packet.length - dnsOffset);
        if (dnsLength < 12) return null;

        byte[] dnsQuery = Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLength);
        byte[] dnsResponse = resolveDnsQuery(dnsQuery);
        return buildUdpIpv4Response(packet, srcPort, dnsResponse);
    }

    private byte[] processIpv6UdpDns(byte[] packet) {
        if (packet.length < 48) return null;
        int nextHeader = packet[6] & 0xFF;
        if (nextHeader != 17) return null;

        int udpOffset = 40;
        int srcPort = u16(packet, udpOffset);
        int dstPort = u16(packet, udpOffset + 2);
        if (dstPort != 53) return null;

        int udpLength = u16(packet, udpOffset + 4);
        int dnsOffset = udpOffset + 8;
        int dnsLength = Math.min(Math.max(0, udpLength - 8), packet.length - dnsOffset);
        if (dnsLength < 12) return null;

        byte[] dnsQuery = Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLength);
        byte[] dnsResponse = resolveDnsQuery(dnsQuery);
        return buildUdpIpv6Response(packet, srcPort, dnsResponse);
    }

    private byte[] resolveDnsQuery(byte[] query) {
        String domain = readQuestionName(query);
        if (domain == null) return buildServerFailure(query);

        if (BlocklistStore.isBlocked(this, domain)) {
            showBlockedOverlay(domain);
            learnBlockedHostAsync(domain);
            return buildNxDomain(query);
        }

        byte[] forwarded = forwardDns(query);
        return forwarded == null ? buildServerFailure(query) : forwarded;
    }

    private void learnBlockedHostAsync(String domain) {
        long now = System.currentTimeMillis();
        Long previous = recentDynamicResolve.get(domain);
        if (previous != null && now - previous < 60_000L) return;
        recentDynamicResolve.put(domain, now);
        rememberRecentDomain(domain);

        new Thread(() -> {
            LinkedHashMap<String, String> learned = resolveOneBlockedDomain(domain);
            if (!learned.isEmpty()) {
                mergeDynamicRoutes(learned);
                rebuildVpnAsync();
            }
        }, "RitmoLearnHost").start();
    }

    private LinkedHashMap<String, String> resolveOneBlockedDomain(String base) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add(base);
        if (!base.startsWith("www.")) hosts.add("www." + base);
        if (!base.startsWith("m.")) hosts.add("m." + base);
        for (String host : hosts) resolveHost(host, base, result, 16);
        return result;
    }

    private void resolveHost(String host, String baseDomain, Map<String, String> out, int limit) {
        for (String resolver : new String[]{"1.1.1.1", "8.8.8.8"}) {
            if (out.size() >= limit) return;
            for (int qtype : new int[]{1, 28}) {
                try {
                    byte[] query = buildDnsLookup(host, qtype);
                    byte[] response = sendDirectDns(query, resolver);
                    if (response == null) continue;
                    for (InetAddress address : parseAddressAnswers(response)) {
                        if (out.size() >= limit) return;
                        out.put(address.getHostAddress(), baseDomain);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void rememberRecentDomain(String domain) {
        synchronized (dynamicRouteLock) {
            recentRouteDomains.remove(domain);
            recentRouteDomains.add(domain);
            while (recentRouteDomains.size() > MAX_RECENT_DOMAINS) {
                String first = recentRouteDomains.iterator().next();
                recentRouteDomains.remove(first);
            }
        }
    }

    private void mergeDynamicRoutes(Map<String, String> learned) {
        synchronized (dynamicRouteLock) {
            for (Map.Entry<String, String> entry : learned.entrySet()) {
                dynamicRouteCache.remove(entry.getKey());
                dynamicRouteCache.put(entry.getKey(), entry.getValue());
            }
            while (dynamicRouteCache.size() > MAX_DYNAMIC_ROUTES) {
                String first = dynamicRouteCache.keySet().iterator().next();
                dynamicRouteCache.remove(first);
            }
        }
    }

    private Map<String, String> snapshotDynamicRoutes() {
        synchronized (dynamicRouteLock) {
            return new LinkedHashMap<>(dynamicRouteCache);
        }
    }

    private void pruneDynamicRoutes() {
        synchronized (dynamicRouteLock) {
            dynamicRouteCache.entrySet().removeIf(entry -> !BlocklistStore.isBlocked(this, entry.getValue()));
            recentRouteDomains.removeIf(domain -> !BlocklistStore.isBlocked(this, domain));
        }
    }

    private void refreshRecentRoutesAsync() {
        if (intentionalStop || rebuilding) return;
        rebuilding = true;
        resolverWorker = new Thread(() -> {
            try {
                List<String> domains;
                synchronized (dynamicRouteLock) {
                    domains = new ArrayList<>(recentRouteDomains);
                }
                LinkedHashMap<String, String> refreshed = new LinkedHashMap<>();
                for (String domain : domains) {
                    if (!BlocklistStore.isBlocked(this, domain)) continue;
                    Map<String, String> one = resolveOneBlockedDomain(domain);
                    for (Map.Entry<String, String> e : one.entrySet()) {
                        if (refreshed.size() >= MAX_DYNAMIC_ROUTES) break;
                        refreshed.put(e.getKey(), e.getValue());
                    }
                    if (refreshed.size() >= MAX_DYNAMIC_ROUTES) break;
                }
                synchronized (dynamicRouteLock) {
                    dynamicRouteCache.clear();
                    dynamicRouteCache.putAll(refreshed);
                }
                if (!intentionalStop) establishVpn(snapshotDynamicRoutes());
            } finally {
                rebuilding = false;
            }
        }, "RitmoRecentRouteRefresh");
        resolverWorker.start();
    }

    private byte[] sendDirectDns(byte[] query, String resolver) {
        try (DatagramSocket socket = new DatagramSocket()) {
            try { protect(socket); } catch (Exception ignored) {}
            socket.setSoTimeout(1700);
            DatagramPacket req = new DatagramPacket(
                    query,
                    query.length,
                    InetAddress.getByName(resolver),
                    53
            );
            socket.send(req);
            byte[] buf = new byte[8192];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            return Arrays.copyOf(resp.getData(), resp.getLength());
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] forwardDns(byte[] query) {
        for (String resolver : new String[]{"1.1.1.1", "8.8.8.8"}) {
            byte[] response = sendDirectDns(query, resolver);
            if (response != null) return response;
        }
        return null;
    }

    private byte[] buildDnsLookup(String host, int qtype) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = random.nextInt(0x10000);
        out.write((id >> 8) & 0xFF);
        out.write(id & 0xFF);
        out.write(0x01); out.write(0x00); // recursion desired
        out.write(0x00); out.write(0x01); // QDCOUNT
        out.write(0x00); out.write(0x00);
        out.write(0x00); out.write(0x00);
        out.write(0x00); out.write(0x00);

        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) throw new IOException("Domínio inválido");
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0);
        out.write((qtype >> 8) & 0xFF);
        out.write(qtype & 0xFF);
        out.write(0); out.write(1); // IN
        return out.toByteArray();
    }

    private List<InetAddress> parseAddressAnswers(byte[] msg) {
        ArrayList<InetAddress> out = new ArrayList<>();
        try {
            if (msg.length < 12) return out;
            int qd = u16(msg, 4);
            int an = u16(msg, 6);
            int pos = 12;
            for (int i = 0; i < qd; i++) {
                pos = skipDnsName(msg, pos);
                pos += 4;
                if (pos > msg.length) return out;
            }
            for (int i = 0; i < an && pos < msg.length; i++) {
                pos = skipDnsName(msg, pos);
                if (pos + 10 > msg.length) break;
                int type = u16(msg, pos); pos += 2;
                int clazz = u16(msg, pos); pos += 2;
                pos += 4; // TTL
                int rdLen = u16(msg, pos); pos += 2;
                if (pos + rdLen > msg.length) break;
                if (clazz == 1 && type == 1 && rdLen == 4) {
                    out.add(InetAddress.getByAddress(Arrays.copyOfRange(msg, pos, pos + 4)));
                } else if (clazz == 1 && type == 28 && rdLen == 16) {
                    out.add(InetAddress.getByAddress(Arrays.copyOfRange(msg, pos, pos + 16)));
                }
                pos += rdLen;
            }
        } catch (Exception ignored) {}
        return out;
    }

    private int skipDnsName(byte[] msg, int pos) {
        while (pos < msg.length) {
            int len = msg[pos] & 0xFF;
            if (len == 0) return pos + 1;
            if ((len & 0xC0) == 0xC0) return Math.min(msg.length, pos + 2);
            pos += 1 + len;
        }
        return msg.length;
    }

    private String readQuestionName(byte[] dns) {
        try {
            if (dns.length < 13 || u16(dns, 4) < 1) return null;
            int pos = 12;
            StringBuilder host = new StringBuilder();
            while (pos < dns.length) {
                int len = dns[pos++] & 0xFF;
                if (len == 0) break;
                if ((len & 0xC0) != 0 || len > 63 || pos + len > dns.length) return null;
                if (host.length() > 0) host.append('.');
                host.append(new String(dns, pos, len, StandardCharsets.US_ASCII));
                pos += len;
            }
            String value = host.toString().toLowerCase(Locale.ROOT);
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private void showBlockedOverlay(String domain) {
        if (!Settings.canDrawOverlays(this)) return;

        long now = System.currentTimeMillis();
        if (domain.equals(lastOverlayDomain) && now - lastOverlayAt < 8000) return;
        if (now - lastOverlayAt < 2000) return;

        lastOverlayAt = now;
        lastOverlayDomain = domain;

        try {
            Intent overlay = new Intent(this, BlockedOverlayService.class);
            overlay.putExtra(BlockedOverlayService.EXTRA_DOMAIN, domain);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundServiceCompat(overlay);
            else startService(overlay);
        } catch (Exception ignored) {
            try { startService(new Intent(this, BlockedOverlayService.class)
                    .putExtra(BlockedOverlayService.EXTRA_DOMAIN, domain)); } catch (Exception ignored2) {}
        }
    }

    private void startForegroundServiceCompat(Intent intent) {
        // BlockedOverlayService normalmente abre enquanto existe atividade visível
        // (navegador). startService é permitido nesse cenário. Mantemos este método
        // separado para centralizar o fallback em ROMs mais agressivas.
        startService(intent);
    }

    private byte[] buildNxDomain(byte[] query) {
        byte[] out = Arrays.copyOf(query, query.length);
        int originalFlags = u16(query, 2);
        int flags = 0x8000 | (originalFlags & 0x0100) | 0x0080 | 0x0003;
        put16(out, 2, flags);
        put16(out, 6, 0);
        put16(out, 8, 0);
        put16(out, 10, 0);
        return out;
    }

    private byte[] buildServerFailure(byte[] query) {
        byte[] out = Arrays.copyOf(query, query.length);
        int originalFlags = u16(query, 2);
        int flags = 0x8000 | (originalFlags & 0x0100) | 0x0080 | 0x0002;
        put16(out, 2, flags);
        put16(out, 6, 0);
        put16(out, 8, 0);
        put16(out, 10, 0);
        return out;
    }

    private byte[] buildUdpIpv4Response(byte[] request, int clientPort, byte[] dnsPayload) {
        int ihl = 20;
        int totalLength = ihl + 8 + dnsPayload.length;
        byte[] out = new byte[totalLength];
        out[0] = 0x45;
        put16(out, 2, totalLength);
        put16(out, 6, 0x4000);
        out[8] = 64;
        out[9] = 17;
        System.arraycopy(request, 16, out, 12, 4);
        System.arraycopy(request, 12, out, 16, 4);
        put16(out, 10, 0);
        put16(out, 10, ipv4Checksum(out, 0, ihl));
        put16(out, ihl, 53);
        put16(out, ihl + 2, clientPort);
        put16(out, ihl + 4, 8 + dnsPayload.length);
        put16(out, ihl + 6, 0);
        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.length);
        return out;
    }

    private byte[] buildUdpIpv6Response(byte[] request, int clientPort, byte[] dnsPayload) {
        int udpLength = 8 + dnsPayload.length;
        byte[] out = new byte[40 + udpLength];
        out[0] = 0x60;
        put16(out, 4, udpLength);
        out[6] = 17;
        out[7] = 64;
        System.arraycopy(request, 24, out, 8, 16);
        System.arraycopy(request, 8, out, 24, 16);
        int udp = 40;
        put16(out, udp, 53);
        put16(out, udp + 2, clientPort);
        put16(out, udp + 4, udpLength);
        put16(out, udp + 6, 0);
        System.arraycopy(dnsPayload, 0, out, udp + 8, dnsPayload.length);
        int checksum = udpIpv6Checksum(out, udp, udpLength);
        if (checksum == 0) checksum = 0xFFFF;
        put16(out, udp + 6, checksum);
        return out;
    }

    private int udpIpv6Checksum(byte[] packet, int udpOffset, int udpLength) {
        long sum = 0;
        sum = checksumWords(packet, 8, 16, sum);
        sum = checksumWords(packet, 24, 16, sum);
        sum += (udpLength >> 16) & 0xFFFF;
        sum += udpLength & 0xFFFF;
        sum += 17;
        sum = checksumWords(packet, udpOffset, udpLength, sum);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    private long checksumWords(byte[] data, int offset, int length, long initial) {
        long sum = initial;
        int end = Math.min(data.length, offset + length);
        for (int i = offset; i < end; i += 2) {
            int hi = data[i] & 0xFF;
            int lo = (i + 1 < end) ? (data[i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
            while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return sum;
    }

    private int ipv4Checksum(byte[] data, int offset, int length) {
        long sum = checksumWords(data, offset, length, 0);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    private int u16(byte[] data, int off) {
        if (off < 0 || off + 1 >= data.length) return 0;
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private void put16(byte[] data, int off, int value) {
        data[off] = (byte) ((value >> 8) & 0xFF);
        data[off + 1] = (byte) (value & 0xFF);
    }

    private synchronized void stopVpnInternal() {
        generation.incrementAndGet();
        ParcelFileDescriptor current = tun;
        tun = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) {}
        }
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
        BlocklistStore.setVpnActive(this, false);
        stopForeground(true);
    }

    private void scheduleRecovery() {
        if (intentionalStop || !BlocklistStore.isProtectionEnabled(this)) return;
        mainHandler.postDelayed(() -> {
            if (!intentionalStop && BlocklistStore.isProtectionEnabled(this) && tun == null) {
                rebuildVpnAsync();
            }
        }, 1800L);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Fechar/remover a interface do Ritmo dos recentes não deve reconstruir a VPN.
        // Reconstruir aqui era especialmente pesado com listas grandes e podia fazer
        // a VPN sempre ativa aparecer como indisponível. Só recuperamos se ela realmente caiu.
        if (!intentionalStop && BlocklistStore.isProtectionEnabled(this) && tun == null) {
            scheduleRecovery();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onRevoke() {
        intentionalStop = true;
        BlocklistStore.setProtectionEnabled(this, false);
        stopVpnInternal();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh);
        if (intentionalStop || !BlocklistStore.isProtectionEnabled(this)) {
            stopVpnInternal();
        } else {
            BlocklistStore.setVpnActive(this, false);
        }
        super.onDestroy();
    }
}
