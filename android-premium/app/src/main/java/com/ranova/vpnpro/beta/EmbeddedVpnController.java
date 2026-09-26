package com.ranova.vpnpro.beta;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import com.tim.basevpn.IConnectionStateListener;
import com.tim.basevpn.IVPNService;
import com.tim.basevpn.state.ConnectionState;
import com.tim.openvpn.configuration.OpenVPNConfig;
import com.tim.openvpn.service.OpenVPNService;

final class EmbeddedVpnController {
    interface Listener {
        void onState(String state);
        void onError(String message);
    }

    private static final String EXTRA_ACTION = "ACTION_KEY";
    private static final String ACTION_START = "ACTION_START_KEY";
    private static final String ACTION_STOP = "ACTION_STOP_KEY";
    private static final String EXTRA_CONFIG = "CONFIGURATION_KEY";
    private static final String EXTRA_NOTIFICATION = "NOTIFICATION_IMPL_CLASS_KEY";
    private static final String EXTRA_ALLOWED_APPS = "ALLOWED_APPS_KEY";

    private final Context context;
    private final Listener listener;

    private IVPNService service;
    private boolean bound;

    private final IConnectionStateListener callback = new IConnectionStateListener.Stub() {
        @Override
        public void stateChanged(ConnectionState status) {
            if (listener != null && status != null) {
                listener.onState(status.name());
            }
        }

        @Override
        public void trafficUpdate(long txRate, long rxRate, long txTotal, long rxTotal) {
            // Dashboard currently derives counters from Android TrafficStats.
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
            if (!ok && listener != null) listener.onError("Embedded VPN service unavailable");
        } catch (Exception e) {
            if (listener != null) listener.onError("Embedded VPN service unavailable");
        }
    }

    void start(String config) {
        if (config == null || config.trim().isEmpty()) {
            if (listener != null) listener.onError("VPN profile is empty");
            return;
        }

        try {
            OpenVPNConfig openConfig = new OpenVPNConfig(
                    "RANOVA VPN",
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

            Intent intent = new Intent(context, OpenVPNService.class);
            intent.putExtra(EXTRA_ACTION, ACTION_START);
            intent.putExtra(EXTRA_CONFIG, openConfig);
            intent.putExtra(EXTRA_NOTIFICATION, (String) null);
            intent.putExtra(EXTRA_ALLOWED_APPS, new String[0]);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }

            bind();

        } catch (Exception e) {
            if (listener != null) {
                listener.onError("Embedded VPN start failed: " + e.getClass().getSimpleName());
            }
        }
    }

    void stop() {
        try {
            Intent intent = new Intent(context, OpenVPNService.class);
            intent.putExtra(EXTRA_ACTION, ACTION_STOP);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            try {
                if (service != null) service.stopVPN();
            } catch (Exception ignored) {
            }
        }
    }

    void release() {
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
