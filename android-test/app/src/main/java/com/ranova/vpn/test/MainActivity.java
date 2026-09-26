package com.ranova.vpn.test;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

public class MainActivity extends Activity {
    private static final String CATALOGUE_URL =
            "https://raw.githubusercontent.com/etoedxb-jpg/ranova-vpn-catalogue/main/servers.json";
    private static final int REQ_API_PERMISSION = 100;
    private static final int REQ_VPN_PERMISSION = 101;

    private final List<Server> servers = new ArrayList<>();
    private final List<String> serverLabels = new ArrayList<>();

    private TextView status;
    private TextView publicIp;
    private Spinner serverSpinner;
    private ArrayAdapter<String> serverAdapter;
    private IOpenVPNAPIService vpnService;
    private boolean serviceBound;
    private String pendingConfig;

    private final IOpenVPNStatusCallback callback = new IOpenVPNStatusCallback.Stub() {
        @Override
        public void newStatus(String uuid, String state, String message, String level) {
            runOnUiThread(() -> {
                status.setText("VPN: " + state + (message == null || message.isEmpty() ? "" : "\n" + message));
                if (state != null && state.toUpperCase().contains("CONNECTED")) {
                    checkPublicIp();
                }
            });
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            vpnService = IOpenVPNAPIService.Stub.asInterface(binder);
            serviceBound = true;
            setStatus("OpenVPN engine found. Requesting API access...");
            requestApiAccess();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnService = null;
            serviceBound = false;
            setStatus("OpenVPN engine disconnected.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        fetchCatalogue();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindOpenVpnService();
    }

    @Override
    protected void onDestroy() {
        if (vpnService != null) {
            try {
                vpnService.unregisterStatusCallback(callback);
            } catch (Exception ignored) {
            }
        }
        if (serviceBound) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("RANOVA VPN\nConnection Test");
        title.setTextSize(28);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Purpose: prove GitHub catalogue → OpenVPN profile → real Android VPN tunnel.\nThis test build uses OpenVPN for Android as the tunnel engine.");
        note.setTextSize(15);
        note.setPadding(0, 0, 0, dp(16));
        root.addView(note);

        status = new TextView(this);
        status.setText("Starting...");
        status.setTextSize(16);
        status.setPadding(0, 0, 0, dp(14));
        root.addView(status);

        serverSpinner = new Spinner(this);
        serverAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, serverLabels);
        serverSpinner.setAdapter(serverAdapter);
        root.addView(serverSpinner);

        Button refresh = button("Refresh server list");
        refresh.setOnClickListener(v -> fetchCatalogue());
        root.addView(refresh);

        Button connect = button("Connect selected server");
        connect.setOnClickListener(v -> connectSelected());
        root.addView(connect);

        Button disconnect = button("Disconnect");
        disconnect.setOnClickListener(v -> disconnectVpn());
        root.addView(disconnect);

        Button ip = button("Check public IP");
        ip.setOnClickListener(v -> checkPublicIp());
        root.addView(ip);

        Button install = button("Open OpenVPN for Android page");
        install.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/schwabe/ics-openvpn/releases"));
            startActivity(i);
        });
        root.addView(install);

        publicIp = new TextView(this);
        publicIp.setText("Public IP: not checked");
        publicIp.setTextSize(17);
        publicIp.setPadding(0, dp(14), 0, 0);
        root.addView(publicIp);

        setContentView(scroll);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void bindOpenVpnService() {
        Intent intent = new Intent(IOpenVPNAPIService.class.getName());
        intent.setPackage("de.blinkt.openvpn");
        try {
            boolean ok = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                setStatus("OpenVPN for Android is not installed or its API service is unavailable.");
            }
        } catch (Exception e) {
            setStatus("OpenVPN service bind failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void requestApiAccess() {
        if (vpnService == null) return;
        try {
            Intent permission = vpnService.prepare(getPackageName());
            if (permission != null) {
                startActivityForResult(permission, REQ_API_PERMISSION);
            } else {
                onApiAccessReady();
            }
        } catch (RemoteException e) {
            setStatus("API permission request failed: " + e.getMessage());
        }
    }

    private void onApiAccessReady() {
        if (vpnService == null) return;
        try {
            vpnService.registerStatusCallback(callback);
            setStatus("OpenVPN API allowed. Catalogue can now be connected.");
        } catch (RemoteException e) {
            setStatus("Status callback failed: " + e.getMessage());
        }
    }

    private void fetchCatalogue() {
        setStatus("Downloading RANOVA server catalogue...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL(CATALOGUE_URL);
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(25000);
                c.setRequestProperty("User-Agent", "RANOVA-VPN-Test/0.1");

                int code = c.getResponseCode();
                if (code != 200) throw new IllegalStateException("HTTP " + code);

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[8192];
                    int n;
                    int total = 0;
                    while ((n = br.read(buf)) != -1) {
                        total += n;
                        if (total > 5_000_000) throw new IllegalStateException("Catalogue too large");
                        sb.append(buf, 0, n);
                    }
                }

                JSONObject root = new JSONObject(sb.toString());
                JSONArray arr = root.getJSONArray("servers");
                List<Server> next = new ArrayList<>();
                List<String> labels = new ArrayList<>();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Server s = new Server(
                            o.optString("country_code", "--"),
                            o.optString("country", "Unknown"),
                            o.getString("ip"),
                            o.getInt("port"),
                            o.getString("protocol"),
                            o.optInt("ping_ms", -1),
                            o.getString("openvpn_config_base64")
                    );
                    next.add(s);
                    labels.add(s.label());
                }

                runOnUiThread(() -> {
                    servers.clear();
                    servers.addAll(next);
                    serverLabels.clear();
                    serverLabels.addAll(labels);
                    serverAdapter.notifyDataSetChanged();
                    setStatus("Catalogue ready: " + servers.size() + " validated servers.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(
                        "Catalogue fetch failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private void connectSelected() {
        if (vpnService == null) {
            setStatus("OpenVPN for Android is not connected. Install/open it first.");
            return;
        }
        if (servers.isEmpty()) {
            setStatus("No server catalogue loaded.");
            return;
        }

        int index = serverSpinner.getSelectedItemPosition();
        if (index < 0 || index >= servers.size()) index = 0;
        Server s = servers.get(index);

        try {
            byte[] raw = Base64.decode(s.configBase64, Base64.DEFAULT);
            String config = new String(raw, StandardCharsets.UTF_8);
            String lower = config.toLowerCase();
            String expectedRemote = "remote " + s.ip + " " + s.port;
            if (!lower.contains("client") || !config.contains(expectedRemote)) {
                throw new IllegalStateException("Profile sanity check failed");
            }

            pendingConfig = config;
            Intent vpnPermission = vpnService.prepareVPNService();
            if (vpnPermission == null) {
                startPendingVpn();
            } else {
                startActivityForResult(vpnPermission, REQ_VPN_PERMISSION);
            }
        } catch (Exception e) {
            setStatus("Connect preparation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void startPendingVpn() {
        if (vpnService == null || pendingConfig == null) return;
        try {
            setStatus("Starting VPN tunnel...");
            vpnService.startVPN(pendingConfig);
        } catch (RemoteException e) {
            setStatus("VPN start failed: " + e.getMessage());
        }
    }

    private void disconnectVpn() {
        if (vpnService == null) {
            setStatus("No OpenVPN service connection.");
            return;
        }
        try {
            vpnService.disconnect();
            setStatus("Disconnect requested.");
        } catch (RemoteException e) {
            setStatus("Disconnect failed: " + e.getMessage());
        }
    }

    private void checkPublicIp() {
        publicIp.setText("Public IP: checking...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "RANOVA-VPN-Test/0.1");
                String value;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    value = br.readLine();
                }
                String result = value == null ? "unknown" : value.trim();
                runOnUiThread(() -> publicIp.setText("Public IP: " + result));
            } catch (Exception e) {
                runOnUiThread(() -> publicIp.setText(
                        "Public IP check failed: " + e.getClass().getSimpleName()));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            setStatus("Permission was not granted.");
            return;
        }
        if (requestCode == REQ_API_PERMISSION) {
            onApiAccessReady();
        } else if (requestCode == REQ_VPN_PERMISSION) {
            startPendingVpn();
        }
    }

    private void setStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Server {
        final String countryCode;
        final String country;
        final String ip;
        final int port;
        final String protocol;
        final int pingMs;
        final String configBase64;

        Server(String countryCode, String country, String ip, int port,
               String protocol, int pingMs, String configBase64) {
            this.countryCode = countryCode;
            this.country = country;
            this.ip = ip;
            this.port = port;
            this.protocol = protocol;
            this.pingMs = pingMs;
            this.configBase64 = configBase64;
        }

        String label() {
            String ping = pingMs >= 0 ? pingMs + " ms" : "ping ?";
            return countryCode + " • " + country + " • " + protocol.toUpperCase()
                    + " " + port + " • " + ping + " • " + ip;
        }
    }
}
