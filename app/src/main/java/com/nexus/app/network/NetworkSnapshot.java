package com.nexus.app.network;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Read-only snapshot of Android's active network; contains no traffic or identity data. */
public final class NetworkSnapshot {
    public final String transport;
    public final boolean hasInternetCapability;
    public final boolean validated;
    public final boolean metered;

    private NetworkSnapshot(String transport, boolean hasInternetCapability,
                            boolean validated, boolean metered) {
        this.transport = transport;
        this.hasInternetCapability = hasInternetCapability;
        this.validated = validated;
        this.metered = metered;
    }

    public static NetworkSnapshot read(ConnectivityManager manager) {
        if (manager == null) {
            return new NetworkSnapshot("unknown", false, false, true);
        }

        Network active = manager.getActiveNetwork();
        if (active == null) {
            return new NetworkSnapshot("none", false, false, true);
        }

        NetworkCapabilities caps = manager.getNetworkCapabilities(active);
        if (caps == null) {
            return new NetworkSnapshot("unknown", false, false, true);
        }

        String transport = "other";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transport = "wifi";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transport = "cellular";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transport = "ethernet";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            transport = "bluetooth";
        }

        return new NetworkSnapshot(
            transport,
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        );
    }
}
