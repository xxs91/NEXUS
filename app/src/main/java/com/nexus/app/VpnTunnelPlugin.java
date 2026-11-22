package com.nexus.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Peer;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native WireGuard tunnel bridge.
 *
 * The app must obtain a per-user WireGuard configuration from its authorized
 * control plane. No endpoint, key, or server credential is hard-coded here.
 */
@CapacitorPlugin(name = "NexusVpn")
public final class VpnTunnelPlugin extends Plugin {
    private static final String TUNNEL_NAME = "NEXUS";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile GoBackend backend;
    private volatile Tunnel tunnel;
    private volatile String state = "disconnected";

    @Override
    public void load() {
        super.load();
        backend = new GoBackend(getContext().getApplicationContext());
        tunnel = new Tunnel() {
            @Override
            public String getName() {
                return TUNNEL_NAME;
            }

            @Override
            public void onStateChange(State newState) {
                state = newState == State.UP ? "connected" : "disconnected";
            }
        };
    }

    @PluginMethod
    public void connect(PluginCall call) {
        String rawConfig = call.getString("config");
        if (rawConfig == null || rawConfig.trim().isEmpty()) {
            call.reject("缺少服务器下发的 WireGuard 配置。");
            return;
        }

        final Config parsed;
        try {
            parsed = Config.parse(new BufferedReader(new StringReader(rawConfig)));
            validateFullTunnel(parsed);
        } catch (Exception error) {
            call.reject("WireGuard 配置无效或未满足全隧道安全要求：" + safeMessage(error));
            return;
        }

        Intent permissionIntent = VpnService.prepare(getContext());
        if (permissionIntent != null) {
            startActivityForResult(call, permissionIntent, "vpnPermissionResult");
            return;
        }
        startTunnel(call, parsed);
    }

    @ActivityCallback
    private void vpnPermissionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK) {
            call.reject("用户未授权 Android VPN 连接。");
            return;
        }
        String rawConfig = call.getString("config");
        try {
            Config parsed = Config.parse(new BufferedReader(new StringReader(rawConfig)));
            validateFullTunnel(parsed);
            startTunnel(call, parsed);
        } catch (Exception error) {
            call.reject("WireGuard 配置无效：" + safeMessage(error));
        }
    }

    private void validateFullTunnel(Config config) throws Exception {
        if (config.getPeers().size() != 1) {
            throw new IllegalArgumentException("当前版本要求每条隧道使用一个服务器 Peer，以启用系统级全隧道保护。");
        }
        if (config.getInterface().getDnsServers().isEmpty()) {
            throw new IllegalArgumentException("配置必须包含 DNS 服务器。");
        }
        boolean ipv4Default = false;
        boolean ipv6Default = false;
        for (Peer peer : config.getPeers()) {
            for (InetNetwork route : peer.getAllowedIps()) {
                InetAddress address = route.getAddress();
                if (route.getMask() == 0 && address instanceof Inet4Address) ipv4Default = true;
                if (route.getMask() == 0 && address instanceof Inet6Address) ipv6Default = true;
            }
        }
        if (!ipv4Default || !ipv6Default) {
            throw new IllegalArgumentException("AllowedIPs 必须同时包含 0.0.0.0/0 和 ::/0，避免 IPv4/IPv6 流量绕过隧道。");
        }
    }

    private void startTunnel(PluginCall call, Config config) {
        worker.execute(() -> {
            try {
                backend.setState(tunnel, Tunnel.State.UP, config);
                JSObject result = new JSObject();
                result.put("state", state);
                result.put("engine", backend.getVersion());
                result.put("protocol", "wireguard");
                call.resolve(result);
            } catch (Exception error) {
                state = "disconnected";
                call.reject("WireGuard 连接失败：" + safeMessage(error));
            }
        });
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        worker.execute(() -> {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
                state = "disconnected";
                JSObject result = new JSObject();
                result.put("state", state);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("断开 VPN 失败：" + safeMessage(error));
            }
        });
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("state", state);
        result.put("protocol", "wireguard");
        result.put("engine", backend == null ? "unavailable" : backend.getVersion());
        call.resolve(result);
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
