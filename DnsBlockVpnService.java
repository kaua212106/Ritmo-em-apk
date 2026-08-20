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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

public class DnsBlockVpnService extends VpnService {
    public static final String ACTION_START = "com.kaua.ritmo.START_BLOCKER";
    public static final String ACTION_STOP = "com.kaua.ritmo.STOP_BLOCKER";

    private static final String VPN_IPV4 = "10.111.222.1";
    private static final String DNS_IPV4 = "10.111.222.2";
    private static final String VPN_IPV6 = "fd66:7269:746d:6f::1";
    private static final String DNS_IPV6 = "fd66:7269:746d:6f::2";
    private static final String CHANNEL_ID = "ritmo_blocker";
    private static final int NOTIFICATION_ID = 41;

    private static volatile boolean processRunning = false;

    private volatile boolean running;
    private volatile boolean intentionalStop;
    private volatile boolean restarting;
    private ParcelFileDescriptor tun;
    private Thread worker;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastOverlayAt;
    private volatile String lastOverlayDomain = "";

    public static boolean isRunningNow() {
        return processRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        processRunning = false;
        BlocklistStore.setVpnActive(this, false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            intentionalStop = true;
            BlocklistStore.setProtectionEnabled(this, false);
            stopVpnInternal();
            stopSelf();
            return START_NOT_STICKY;
        }

        // START_STICKY can recreate the service with a null Intent.
        if (intent == null && !BlocklistStore.isProtectionEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        intentionalStop = false;
        BlocklistStore.setProtectionEnabled(this, true);
        startInForeground();
        if (!running) startVpn();
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
            channel.setDescription("Mantém o filtro local do Ritmo ativo.");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Ritmo protegendo sua navegação")
                .setContentText("Proteção ativa • DNS IPv4 e IPv6")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private synchronized void startVpn() {
        if (running || intentionalStop) return;

        try {
            Builder builder = new Builder()
                    .setSession("Ritmo - Bloqueio de sites")
                    .setMtu(1500)
                    .addAddress(VPN_IPV4, 32)
                    .addDnsServer(DNS_IPV4)
                    .addRoute(DNS_IPV4, 32);

            // IPv6 is an extra protection layer. If a specific device rejects the
            // synthetic IPv6 interface, IPv4 protection still starts normally.
            try {
                builder.addAddress(VPN_IPV6, 128)
                        .addDnsServer(DNS_IPV6)
                        .addRoute(DNS_IPV6, 128);
            } catch (Exception ignored) {}

            tun = builder.establish();
            if (tun == null) {
                markStopped();
                stopSelf();
                return;
            }

            running = true;
            processRunning = true;
            restarting = false;
            BlocklistStore.setVpnActive(this, true);
            worker = new Thread(this::runLoop, "RitmoDnsFilter");
            worker.start();
        } catch (Exception e) {
            markStopped();
            scheduleRecovery();
        }
    }

    private void runLoop() {
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {

            byte[] packet = new byte[32767];
            while (running && !Thread.currentThread().isInterrupted()) {
                int len = in.read(packet);
                if (len <= 0) continue;
                byte[] response = processPacket(Arrays.copyOf(packet, len));
                if (response != null) {
                    out.write(response);
                    out.flush();
                }
            }
        } catch (IOException ignored) {
        } finally {
            boolean shouldRecover = !intentionalStop && BlocklistStore.isProtectionEnabled(this);
            markStopped();
            if (shouldRecover) scheduleRecovery();
        }
    }

    private byte[] processPacket(byte[] ipPacket) {
        try {
            if (ipPacket.length < 1) return null;
            int version = (ipPacket[0] >> 4) & 0x0F;
            if (version == 4) return processIpv4UdpDns(ipPacket);
            if (version == 6) return processIpv6UdpDns(ipPacket);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] processIpv4UdpDns(byte[] ipPacket) {
        if (ipPacket.length < 28) return null;
        int ihl = (ipPacket[0] & 0x0F) * 4;
        if (ihl < 20 || ipPacket.length < ihl + 8) return null;
        if ((ipPacket[9] & 0xFF) != 17) return null; // UDP

        int srcPort = u16(ipPacket, ihl);
        int dstPort = u16(ipPacket, ihl + 2);
        if (dstPort != 53) return null;

        int udpLength = u16(ipPacket, ihl + 4);
        int dnsOffset = ihl + 8;
        int dnsLength = Math.min(Math.max(0, udpLength - 8), ipPacket.length - dnsOffset);
        if (dnsLength < 12) return null;

        byte[] dnsQuery = Arrays.copyOfRange(ipPacket, dnsOffset, dnsOffset + dnsLength);
        byte[] dnsResponse = resolveDnsQuery(dnsQuery);
        return buildUdpIpv4Response(ipPacket, srcPort, dnsResponse);
    }

    private byte[] processIpv6UdpDns(byte[] ipPacket) {
        // IPv6 base header is 40 bytes. Android DNS packets to our synthetic resolver
        // normally arrive without extension headers; unsupported extension headers are ignored.
        if (ipPacket.length < 48) return null;
        int nextHeader = ipPacket[6] & 0xFF;
        if (nextHeader != 17) return null; // UDP

        int udpOffset = 40;
        int srcPort = u16(ipPacket, udpOffset);
        int dstPort = u16(ipPacket, udpOffset + 2);
        if (dstPort != 53) return null;

        int udpLength = u16(ipPacket, udpOffset + 4);
        int dnsOffset = udpOffset + 8;
        int dnsLength = Math.min(Math.max(0, udpLength - 8), ipPacket.length - dnsOffset);
        if (dnsLength < 12) return null;

        byte[] dnsQuery = Arrays.copyOfRange(ipPacket, dnsOffset, dnsOffset + dnsLength);
        byte[] dnsResponse = resolveDnsQuery(dnsQuery);
        return buildUdpIpv6Response(ipPacket, srcPort, dnsResponse);
    }

    private byte[] resolveDnsQuery(byte[] dnsQuery) {
        String domain = readQuestionName(dnsQuery);
        if (domain == null) return buildServerFailure(dnsQuery);

        if (BlocklistStore.isBlocked(this, domain)) {
            showBlockedOverlay(domain);
            return buildNxDomain(dnsQuery);
        }

        byte[] forwarded = forwardDns(dnsQuery);
        return forwarded == null ? buildServerFailure(dnsQuery) : forwarded;
    }

    private void showBlockedOverlay(String domain) {
        if (!Settings.canDrawOverlays(this)) return;

        long now = System.currentTimeMillis();
        if (domain.equals(lastOverlayDomain) && now - lastOverlayAt < 8000) return;
        if (now - lastOverlayAt < 2500) return;

        lastOverlayAt = now;
        lastOverlayDomain = domain;

        try {
            Intent overlay = new Intent(this, BlockedOverlayService.class);
            overlay.putExtra(BlockedOverlayService.EXTRA_DOMAIN, domain);
            startService(overlay);
        } catch (Exception ignored) {
        }
    }

    private byte[] forwardDns(byte[] query) {
        String[] resolvers = {"1.1.1.1", "8.8.8.8"};
        for (String resolver : resolvers) {
            try (DatagramSocket socket = new DatagramSocket()) {
                if (!protect(socket)) continue;
                socket.setSoTimeout(1800);
                byte[] rx = new byte[8192];
                DatagramPacket request = new DatagramPacket(
                        query, query.length, InetAddress.getByName(resolver), 53
                );
                socket.send(request);
                DatagramPacket response = new DatagramPacket(rx, rx.length);
                socket.receive(response);
                return Arrays.copyOf(response.getData(), response.getLength());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String readQuestionName(byte[] dns) {
        if (dns.length < 13) return null;
        int qd = u16(dns, 4);
        if (qd < 1) return null;
        int p = 12;
        StringBuilder host = new StringBuilder();
        int labels = 0;
        while (p < dns.length && labels++ < 128) {
            int n = dns[p++] & 0xFF;
            if (n == 0) break;
            if ((n & 0xC0) != 0 || n > 63 || p + n > dns.length) return null;
            if (host.length() > 0) host.append('.');
            for (int i = 0; i < n; i++) {
                int c = dns[p++] & 0xFF;
                host.append((char) c);
            }
        }
        return host.length() == 0 ? null : host.toString().toLowerCase();
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
        out[1] = 0;
        put16(out, 2, totalLength);
        put16(out, 4, 0);
        put16(out, 6, 0x4000);
        out[8] = 64;
        out[9] = 17;

        // Source = original destination, destination = original source.
        System.arraycopy(request, 16, out, 12, 4);
        System.arraycopy(request, 12, out, 16, 4);
        put16(out, 10, 0);
        put16(out, 10, ipv4Checksum(out, 0, ihl));

        put16(out, ihl, 53);
        put16(out, ihl + 2, clientPort);
        put16(out, ihl + 4, 8 + dnsPayload.length);
        put16(out, ihl + 6, 0); // UDP checksum is optional for IPv4.
        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.length);
        return out;
    }

    private byte[] buildUdpIpv6Response(byte[] request, int clientPort, byte[] dnsPayload) {
        int udpLength = 8 + dnsPayload.length;
        byte[] out = new byte[40 + udpLength];

        out[0] = 0x60;
        put16(out, 4, udpLength);
        out[6] = 17; // UDP
        out[7] = 64;

        // Swap IPv6 source/destination addresses.
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

        // IPv6 pseudo-header: src + dst.
        sum = checksumWords(packet, 8, 16, sum);
        sum = checksumWords(packet, 24, 16, sum);

        // UDP length as 32 bits.
        sum += (udpLength >> 16) & 0xFFFF;
        sum += udpLength & 0xFFFF;
        // Three zero bytes + next-header (17).
        sum += 17;

        sum = checksumWords(packet, udpOffset, udpLength, sum);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    private long checksumWords(byte[] data, int offset, int length, long initial) {
        long sum = initial;
        int end = offset + length;
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
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private void put16(byte[] data, int off, int value) {
        data[off] = (byte) ((value >> 8) & 0xFF);
        data[off + 1] = (byte) (value & 0xFF);
    }

    private synchronized void markStopped() {
        running = false;
        processRunning = false;
        BlocklistStore.setVpnActive(this, false);
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
    }

    private synchronized void stopVpnInternal() {
        running = false;
        processRunning = false;
        BlocklistStore.setVpnActive(this, false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
        stopForeground(true);
    }

    private void scheduleRecovery() {
        if (restarting || intentionalStop || !BlocklistStore.isProtectionEnabled(this)) return;
        restarting = true;
        mainHandler.postDelayed(() -> {
            restarting = false;
            if (!intentionalStop && !running && BlocklistStore.isProtectionEnabled(this)) {
                startInForeground();
                startVpn();
            }
        }, 1200);
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
        boolean shouldRemainEnabled = !intentionalStop && BlocklistStore.isProtectionEnabled(this);
        stopVpnInternal();
        if (!shouldRemainEnabled) BlocklistStore.setProtectionEnabled(this, false);
        super.onDestroy();
    }
}
