package com.clipboardbridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import com.clipboardbridge.core.BridgeClient;
import com.clipboardbridge.core.Discovery;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;

/**
 * Binds sockets to the WiFi network. Without this, Android sends LAN traffic over mobile data
 * whenever the WiFi has no internet (very common with home routers / PC hotspots).
 */
final class Net {
    private Net() {}

    static Network wifiNetwork(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean hasLan(Context c) {
        return wifiNetwork(c) != null || !Discovery.localNets().isEmpty();
    }

    private static boolean inSubnet(Context c, Network n, String ip) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return false;
            InetAddress target = InetAddress.getByName(ip);
            if (!(target instanceof Inet4Address)) return false;
            byte[] t = target.getAddress();
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address)) continue;
                byte[] a = la.getAddress().getAddress();
                int p = la.getPrefixLength();
                int full = p / 8, rest = p % 8;
                boolean ok = true;
                for (int i = 0; i < full && ok; i++) ok = a[i] == t[i];
                if (ok && rest > 0) {
                    int m = (0xff << (8 - rest)) & 0xff;
                    ok = (a[full] & m) == (t[full] & m);
                }
                if (ok) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static BridgeClient.Binder tcpBinder(final Context c) {
        final Context app = c.getApplicationContext();
        return new BridgeClient.Binder() {
            public void bind(Socket s, String ip) {
                Network n = wifiNetwork(app);
                if (n != null && inSubnet(app, n, ip)) {
                    try { n.bindSocket(s); } catch (Exception ignored) {}
                }
            }
        };
    }

    static Discovery.UdpBinder udpBinder(final Context c) {
        final Context app = c.getApplicationContext();
        return new Discovery.UdpBinder() {
            public void bind(DatagramSocket s) {
                Network n = wifiNetwork(app);
                if (n != null) {
                    try { n.bindSocket(s); } catch (Exception ignored) {}
                }
            }
        };
    }

    static String myIps() {
        List<Discovery.LocalNet> l = Discovery.localNets();
        StringBuilder sb = new StringBuilder();
        for (Discovery.LocalNet n : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n.addr.getHostAddress());
            if (sb.length() > 60) break;
        }
        return sb.toString();
    }

    static WifiManager.MulticastLock multicastLock(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock l = wm.createMulticastLock("cbv1-discovery");
            l.setReferenceCounted(false);
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    static WifiManager.WifiLock wifiLock(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.WifiLock l = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cbv1");
            l.setReferenceCounted(false);
            return l;
        } catch (Exception e) {
            return null;
        }
    }
}
