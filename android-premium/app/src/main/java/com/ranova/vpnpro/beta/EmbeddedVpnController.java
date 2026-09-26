package com.ranova.vpnpro.beta;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.tim.basevpn.IConnectionStateListener;
import com.tim.basevpn.IVPNService;
import com.tim.basevpn.configuration.VpnConfiguration;
import com.tim.basevpn.state.ConnectionState;
import com.tim.openvpn.configuration.OpenVPNConfig;
import com.tim.openvpn.service.OpenVPNService;

import java.util.Collections;

final class EmbeddedVpnController {
    interface Listener {
        void onState(String state);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;

    private IVPNService service;
    private boolean bound;
    private String pendingConfig;

    private final IConnectionStateListener callback = new IConnectionStateListener.Stub() {
        @Override
        public void stateChanged(ConnectionState status) {
            if (listener != null && status != null) {
                listener.onState(status.name());
            }
        }

        @Override
        public void trafficUpdate(long txRate, long rxRate, long txTotal, long rxTotal) {
            // Dashboard currently derives session counters from Android TrafficStats.
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IVPNService.Stub.asInterface(binder);
            bound = true;

            try {
                service.registerCallback(callback);
                ConnectionState state = service.getState();
                if (listener != null) {
                    listener.onState(state == null ? "READYFORCONNECT" : state.name());
                }
            } catch (RemoteException e) {
                if (listener != null) listener.onError("Embedded VPN callback failed");
            }

            if (pendingConfig != null) {
                String cfg = pendingConfig;
                pendingConfig = null;
                startNow(cfg);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            if (listener != null) listener.onState("DISCONNECTED");
        }
    };

    EmbeddedVpnController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void bind() {
        if (bound) return;

        try {
            Intent intent = new Intent(context, OpenVPNService.class);
            boolean ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!ok && listener != null) {
                listener.onError("Embedded VPN service unavailable");
            }
        } catch (Exception e) {
            if (listener != null) listener.onError("Embedded VPN service unavailable");
        }
    }

    void start(String config) {
        if (config == null || config.trim().isEmpty()) {
            if (listener != null) listener.onError("VPN profile is empty");
            return;
        }

        if (service == null) {
            pendingConfig = config;
            bind();
            return;
        }

        startNow(config);
    }

    private void startNow(String config) {
        if (service == null) {
            if (listener != null) listener.onError("Embedded VPN service unavailable");
            return;
        }

        try {
            OpenVPNConfig openConfig = new OpenVPNConfig(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    config
            );

            VpnConfiguration<OpenVPNConfig> vpnConfig =
                    new VpnConfiguration<>(
                            openConfig,
                            Collections.emptySet(),
                            null,
                            null
                    );

            service.startVPN(vpnConfig);

        } catch (Exception e) {
            if (listener != null) {
                listener.onError("Embedded VPN start failed: " + e.getClass().getSimpleName());
            }
        }
    }

    void stop() {
        pendingConfig = null;
        if (service == null) return;

        try {
            service.stopVPN();
        } catch (RemoteException e) {
            if (listener != null) listener.onError("VPN disconnect failed");
        }
    }

    void release() {
        pendingConfig = null;

        if (service != null) {
            try {
                service.unregisterCallback(callback);
            } catch (Exception ignored) {
            }
        }

        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Exception ignored) {
            }
        }

        service = null;
        bound = false;
    }
}
