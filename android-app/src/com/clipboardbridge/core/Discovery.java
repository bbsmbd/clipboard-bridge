package com.clipboardbridge.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds PC apps on the LAN: (1) UDP beacon/query on 54545, (2) TCP sweep of the local /24 on 54546.
 * The sweep works even when the router blocks broadcasts between WiFi and Ethernet.
 */
public final class Discovery {
    private Discovery() {}

    public static class Found {
        public String pcId, name, ip;
        public String toString() { return name + " (" + ip + ")"; }
    }

    public interface UdpBinder {
        void bind(DatagramSocket s);
    }

    public static class LocalNet {
        public Inet4Address addr;
        public int prefix;
        public Inet4Address broadcast;
        public String ifName;
    }

    /** IPv4 LAN addresses of this device, WiFi/hotspot interfaces first. */
    public static List<LocalNet> localNets() {
        List<LocalNet> wifi = new ArrayList<LocalNet>();
        List<LocalNet> other = new ArrayList<LocalNet>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return wifi;
            for (NetworkInterface ni : Collections.list(en)) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") || n.startsWith("dummy")
                        || n.startsWith("p2p") || n.startsWith("ppp") || n.startsWith("clat") || n.startsWith("v4-")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address a = (Inet4Address) ia.getAddress();
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    LocalNet ln = new LocalNet();
                    ln.addr = a;
                    ln.prefix = ia.getNetworkPrefixLength();
                    ln.ifName = n;
                    InetAddress b = ia.getBroadcast();
                    ln.broadcast = b instanceof Inet4Address ? (Inet4Address) b : calcBroadcast(a, ln.prefix);
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")
                            || n.startsWith("rndis") || n.startsWith("softap") || n.startsWith("wifi")) wifi.add(ln);
                    else if (a.isSiteLocalAddress()) other.add(ln);
                }
            }
        } catch (Exception ignored) {
        }
        wifi.addAll(other);
        return wifi;
    }

    static int toInt(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    static String toStr(int ip) {
        return ((ip >>> 24) & 0xff) + "." + ((ip >>> 16) & 0xff) + "." + ((ip >>> 8) & 0xff) + "." + (ip & 0xff);
    }

    static Inet4Address calcBroadcast(Inet4Address a, int prefix) {
        try {
            int p = prefix <= 0 || prefix > 30 ? 24 : prefix;
            int mask = p == 0 ? 0 : 0xffffffff << (32 - p);
            return (Inet4Address) InetAddress.getByName(toStr(toInt(a) | ~mask));
        } catch (Exception e) {
            return null;
        }
    }

    static Found parseBeacon(String m, String ip) {
        // CBV1|<pcId>|<port>|<name>
        if (!m.startsWith("CBV1|")) return null;
        String[] p = m.split("\\|", 4);
        if (p.length < 4) return null;
        Found f = new Found();
        f.pcId = p[1];
        f.name = p[3].trim().length() == 0 ? "PC" : p[3].trim();
        f.ip = ip;
        return f;
    }

    /**
     * UDP discovery for durationMs. If wantPcId != null, returns as soon as that PC is seen.
     */
    public static List<Found> udp(int durationMs, String wantPcId, UdpBinder binder) {
        Map<String, Found> found = new LinkedHashMap<String, Found>();
        DatagramSocket s = null;
        try {
            try {
                s = new DatagramSocket(null);
                s.setReuseAddress(true);
                if (binder != null) binder.bind(s);
                s.bind(new InetSocketAddress(Proto.UDP_PORT));
            } catch (Exception e) {
                if (s != null) s.close();
                s = new DatagramSocket(null);
                if (binder != null) binder.bind(s);
                s.bind(new InetSocketAddress(0));
            }
            s.setBroadcast(true);
            s.setSoTimeout(300);
            List<InetAddress> targets = new ArrayList<InetAddress>();
            try { targets.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
            for (LocalNet ln : localNets()) if (ln.broadcast != null && !targets.contains(ln.broadcast)) targets.add(ln.broadcast);
            byte[] q = "CBV1?".getBytes(Proto.UTF8);
            byte[] buf = new byte[512];
            long end = System.currentTimeMillis() + durationMs;
            long nextQuery = 0;
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextQuery) {
                    for (InetAddress t : targets) {
                        try { s.send(new DatagramPacket(q, q.length, t, Proto.UDP_PORT)); } catch (Exception ignored) {}
                    }
                    nextQuery = System.currentTimeMillis() + 1000;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String m = new String(p.getData(), 0, p.getLength(), Proto.UTF8);
                    Found f = parseBeacon(m, p.getAddress().getHostAddress());
                    if (f != null) {
                        found.put(f.pcId, f);
                        if (wantPcId != null && wantPcId.equals(f.pcId)) break;
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
        return new ArrayList<Found>(found.values());
    }

    /**
     * TCP sweep of each local /24 (or smaller subnet): probes port 54546 with the V1 hello.
     */
    public static List<Found> sweep(final String wantPcId, final String phoneId, final String phoneName,
                                    final BridgeClient.Binder binder) {
        final Map<String, Found> found = Collections.synchronizedMap(new LinkedHashMap<String, Found>());
        final AtomicBoolean stop = new AtomicBoolean(false);
        List<String> hosts = new ArrayList<String>();
        for (LocalNet ln : localNets()) {
            int me = toInt(ln.addr);
            int p = ln.prefix < 24 || ln.prefix > 30 ? 24 : ln.prefix;
            int mask = 0xffffffff << (32 - p);
            int net = me & mask;
            int count = (1 << (32 - p)) - 2;
            for (int i = 1; i <= count; i++) {
                int ip = net + i;
                if (ip == me) continue;
                String h = toStr(ip);
                if (!hosts.contains(h)) hosts.add(h);
            }
            if (hosts.size() > 1100) break;
        }
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (final String h : hosts) {
            pool.execute(new Runnable() {
                public void run() {
                    if (stop.get()) return;
                    try {
                        BridgeClient.Hi hi = BridgeClient.probe(h, 450, phoneId, phoneName, binder);
                        Found f = new Found();
                        f.pcId = hi.pcId;
                        f.name = hi.name;
                        f.ip = h;
                        found.put(f.pcId, f);
                        if (wantPcId != null && wantPcId.equals(hi.pcId)) stop.set(true);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(25, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        synchronized (found) {
            return new ArrayList<Found>(found.values());
        }
    }
}
