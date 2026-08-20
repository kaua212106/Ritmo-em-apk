package com.kaua.ritmo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.system.OsConstants;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class DnsBlockVpnService extends VpnService {
    public static final String ACTION_START = "com.kaua.ritmo.START_BLOCKER";
    public static final String ACTION_STOP = "com.kaua.ritmo.STOP_BLOCKER";

    private static final String VPN_IP = "10.111.222.1";
    private static final String DNS_IP = "10.111.222.2";
    private static final String CHANNEL_ID = "ritmo_blocker";
    private static final int NOTIFICATION_ID = 41;

    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private Thread worker;
    private volatile long lastOverlayAt;
    private volatile String lastOverlayDomain = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

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
                .setContentText("Filtro de sites ativo")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private synchronized void startVpn() {
        try {
            Builder builder = new Builder()
                    .setSession("Ritmo - Bloqueio de sites")
                    .setMtu(32767)
                    .addAddress(VPN_IP, 32)
                    .addDnsServer(DNS_IP)
                    .addRoute(DNS_IP, 32);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.allowFamily(OsConstants.AF_INET6);
            }

            tun = builder.establish();
            if (tun == null) {
                BlocklistStore.setVpnActive(this, false);
                stopSelf();
                return;
            }

            running = true;
            BlocklistStore.setVpnActive(this, true);
            worker = new Thread(this::runLoop, "RitmoDnsFilter");
            worker.start();
        } catch (Exception e) {
            running = false;
            BlocklistStore.setVpnActive(this, false);
            stopSelf();
        }
    }

    private void runLoop() {
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {

            byte[] packet = new byte[32767];
            while (running) {
                int len = in.read(packet);
                if (len <= 0) continue;
                byte[] response = processPacket(Arrays.copyOf(packet, len));
                if (response != null) out.write(response);
            }
        } catch (IOException ignored) {
        } finally {
            running = false;
            BlocklistStore.setVpnActive(this, false);
        }
    }

    private byte[] processPacket(byte[] ipPacket) {
        try {
            if (ipPacket.length < 28) return null;
            int version = (ipPacket[0] >> 4) & 0x0F;
            if (version != 4) return null;
            int ihl = (ipPacket[0] & 0x0F) * 4;
            if (ihl < 20 || ipPacket.length < ihl + 8) return null;
            if ((ipPacket[9] & 0xFF) != 17) return null; // UDP only

            int srcPort = u16(ipPacket, ihl);
            int dstPort = u16(ipPacket, ihl + 2);
            if (dstPort != 53) return null;

            int udpLength = u16(ipPacket, ihl + 4);
            int dnsOffset = ihl + 8;
            int dnsLength = Math.min(udpLength - 8, ipPacket.length - dnsOffset);
            if (dnsLength < 12) return null;

            byte[] dnsQuery = Arrays.copyOfRange(ipPacket, dnsOffset, dnsOffset + dnsLength);
            String domain = readQuestionName(dnsQuery);
            if (domain == null) return null;

            byte[] dnsResponse;
            if (BlocklistStore.isBlocked(this, domain)) {
                showBlockedOverlay(domain);
                dnsResponse = buildNxDomain(dnsQuery);
            } else {
                dnsResponse = forwardDns(dnsQuery);
                if (dnsResponse == null) dnsResponse = buildServerFailure(dnsQuery);
            }

            return buildUdpIpv4Response(ipPacket, ihl, srcPort, dnsResponse);
        } catch (Exception e) {
            return null;
        }
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
                socket.setSoTimeout(2500);
                byte[] rx = new byte[4096];
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

    private byte[] buildUdpIpv4Response(byte[] request, int reqIhl, int clientPort, byte[] dnsPayload) {
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

        // Source = original destination (our synthetic DNS), destination = original source.
        System.arraycopy(request, 16, out, 12, 4);
        System.arraycopy(request, 12, out, 16, 4);
        put16(out, 10, 0);
        put16(out, 10, ipv4Checksum(out, 0, ihl));

        put16(out, ihl, 53);
        put16(out, ihl + 2, clientPort);
        put16(out, ihl + 4, 8 + dnsPayload.length);
        put16(out, ihl + 6, 0); // Valid for IPv4 UDP.
        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.length);
        return out;
    }

    private int ipv4Checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int end = offset + length;
        for (int i = offset; i < end; i += 2) {
            int hi = data[i] & 0xFF;
            int lo = (i + 1 < end) ? (data[i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
            while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum) & 0xFFFF;
    }

    private int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private void put16(byte[] data, int off, int value) {
        data[off] = (byte) ((value >> 8) & 0xFF);
        data[off + 1] = (byte) (value & 0xFF);
    }

    private synchronized void stopVpn() {
        running = false;
        BlocklistStore.setVpnActive(this, false);
        if (worker != null) worker.interrupt();
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
        stopForeground(true);
    }

    @Override
    public void onRevoke() {
        stopVpn();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
