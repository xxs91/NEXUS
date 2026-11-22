package com.nexus.app;

import android.content.Context;
import android.net.ConnectivityManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.nexus.app.network.NetworkSnapshot;

/** Minimal, read-only diagnostics bridge. Does not start a VPN or collect traffic. */
@CapacitorPlugin(name = "NetworkFoundation")
public class NetworkFoundationPlugin extends Plugin {

    @PluginMethod
    public void getNetworkSnapshot(PluginCall call) {
        Context context = getContext();
        ConnectivityManager manager = context == null ? null
            : (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkSnapshot snapshot = NetworkSnapshot.read(manager);

        JSObject result = new JSObject();
        result.put("transport", snapshot.transport);
        result.put("hasInternetCapability", snapshot.hasInternetCapability);
        result.put("validated", snapshot.validated);
        result.put("metered", snapshot.metered);
        result.put("vpnEngineIntegrated", false);
        result.put("vpsConfigured", false);
        call.resolve(result);
    }
}
