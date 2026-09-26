package com.ranova.vpn.standalone;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.tim.basevpn.IConnectionStateListener;
import com.tim.basevpn.IVPNService;
import com.tim.basevpn.state.ConnectionState;
import com.tim.openvpn.OpenVPNConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String CATALOGUE =
            "https://raw.githubusercontent.com/etoedxb-jpg/ranova-vpn-catalogue/main/servers.json";
    private static final int REQ_VPN = 501;

    private final List<Server> servers = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private TextView status;
    private TextView ip;
    private Spinner spinner;
    private ArrayAdapter<String> adapter;

    private IVPNService vpn;
    private boolean bound;
    private OpenVPNConfig pendingConfig;

    private final IConnectionStateListener listener =
            new IConnectionStateListener.Stub() {
                @Override
                public void stateChanged(ConnectionState state) {
                    runOnUiThread(() -> {
                        if (state == null) return;
                        status.setText("VPN: " + state.name());
                        if (state == ConnectionState.CONNECTED) {
                            checkIp();
                        }
                    });
                }
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            vpn = IVPNService.Stub.asInterface(service);
            bound = true;
            try {
                vpn.registerCallback(listener);
                vpn.startVPN();
                status.setText("Embedded engine started...");
            } catch (Exception e) {
                status.setText("Engine start failed: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpn = null;
            bound = false;
            status.setText("Embedded engine disconnected.");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }

        fetchCatalogue();
        checkIp();
    }

    @Override
    protected void onDestroy() {
        stopAndUnbind();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);

        TextView title = text("RANOVA VPN\nStandalone Engine Test", 26, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView note = text(
                "No separate VPN app is required in this test. " +
                "It uses an engine packaged inside this APK.",
                14, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(10), 0, dp(20));
        root.addView(note);

        status = text("Loading servers...", 16, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(14));
        root.addView(status);

        spinner = new Spinner(this);
        adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels);
        spinner.setAdapter(adapter);
        root.addView(spinner);

        Button connect = new Button(this);
        connect.setText("CONNECT WITH EMBEDDED ENGINE");
        connect.setOnClickListener(v -> connectSelected());
        add(root, connect, 12);

        Button disconnect = new Button(this);
        disconnect.setText("DISCONNECT");
        disconnect.setOnClickListener(v -> stopAndUnbind());
        add(root, disconnect, 8);

        Button refresh = new Button(this);
        refresh.setText("REFRESH SERVER LIST");
        refresh.setOnClickListener(v -> fetchCatalogue());
        add(root, refresh, 8);

        Button ipCheck = new Button(this);
        ipCheck.setText("CHECK PUBLIC IP");
        ipCheck.setOnClickListener(v -> checkIp());
        add(root, ipCheck, 8);

        ip = text("Public IP: checking...", 18, true);
        ip.setPadding(0, dp(18), 0, 0);
        root.addView(ip);

        setContentView(scroll);
    }

    private void add(LinearLayout root, Button b, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        root.addView(b, p);
    }

    private TextView text(String value, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        return t;
    }

    private void fetchCatalogue() {
        status.setText("Downloading RANOVA catalogue...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(CATALOGUE).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(25000);
                c.setRequestProperty("User-Agent", "RANOVA-Standalone-Test/0.1");

                if (c.getResponseCode() != 200) {
                    throw new IllegalStateException("HTTP " + c.getResponseCode());
                }

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

                JSONArray arr = new JSONObject(sb.toString()).getJSONArray("servers");
                List<Server> next = new ArrayList<>();
                List<String> nextLabels = new ArrayList<>();

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
                    nextLabels.add(
                            flag(s.countryCode) + " " + s.country
                                    + " • " + s.protocol.toUpperCase(Locale.US)
                                    + " " + s.port
                                    + " • " + (s.pingMs >= 0 ? s.pingMs + " ms" : "ping ?"));
                }

                runOnUiThread(() -> {
                    servers.clear();
                    servers.addAll(next);
                    labels.clear();
                    labels.addAll(nextLabels);
                    adapter.notifyDataSetChanged();
                    status.setText("Catalogue ready: " + servers.size()
                            + " servers. Embedded engine not connected yet.");
                });

            } catch (Exception e) {
                runOnUiThread(() -> status.setText(
                        "Catalogue failed: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage()));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private void connectSelected() {
        if (servers.isEmpty()) {
            status.setText("Server list is empty.");
            return;
        }

        int pos = spinner.getSelectedItemPosition();
        if (pos < 0 || pos >= servers.size()) pos = 0;
        Server s = servers.get(pos);

        try {
            String raw = new String(
                    Base64.decode(s.configBase64, Base64.DEFAULT),
                    StandardCharsets.UTF_8);

            ParsedConfig parsed = parse(raw, s);

            pendingConfig = new OpenVPNConfig(
                    "RANOVA " + s.country,
                    parsed.host,
                    parsed.port,
                    parsed.proto,
                    parsed.cipher,
                    parsed.auth,
                    parsed.ca,
                    parsed.key,
                    parsed.cert,
                    parsed.tlsCrypt
            );

            Intent permission = VpnService.prepare(this);
            if (permission != null) {
                startActivityForResult(permission, REQ_VPN);
            } else {
                bindEmbeddedEngine();
            }
        } catch (Exception e) {
            status.setText("Profile preparation failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void bindEmbeddedEngine() {
        if (pendingConfig == null) {
            status.setText("No prepared config.");
            return;
        }

        stopAndUnbind();

        Intent service = new Intent();
        service.setClassName(
                getPackageName(),
                "com.tim.openvpn.service.OpenVPNService");
        service.putExtra("CONFIG_EXTRA", pendingConfig);

        try {
            status.setText("Binding embedded VPN engine...");
            boolean ok = bindService(service, connection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                status.setText("Embedded service bind returned false.");
            }
        } catch (Exception e) {
            status.setText("Embedded service bind failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void stopAndUnbind() {
        if (vpn != null) {
            try {
                vpn.stopVPN();
                vpn.unregisterCallback(listener);
            } catch (Exception ignored) {
            }
        }

        if (bound) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
        }

        try {
            stopService(new Intent()
                    .setClassName(getPackageName(),
                            "com.tim.openvpn.service.OpenVPNService"));
        } catch (Exception ignored) {
        }

        vpn = null;
        bound = false;
        if (status != null) status.setText("Disconnected.");
        checkIp();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                bindEmbeddedEngine();
            } else {
                status.setText("Android VPN permission denied.");
            }
        }
    }

    private ParsedConfig parse(String raw, Server fallback) {
        String host = fallback.ip;
        int port = fallback.port;
        String proto = fallback.protocol;

        Matcher remote = Pattern.compile(
                "(?im)^\\s*remote\\s+(\\S+)\\s+(\\d+)(?:\\s+(\\S+))?\\s*$")
                .matcher(raw);
        if (remote.find()) {
            host = remote.group(1);
            port = Integer.parseInt(remote.group(2));
            if (remote.group(3) != null && !remote.group(3).isEmpty()) {
                proto = normalizeProto(remote.group(3));
            }
        }

        Matcher pm = Pattern.compile("(?im)^\\s*proto\\s+(\\S+)\\s*$").matcher(raw);
        if (pm.find()) proto = normalizeProto(pm.group(1));

        String cipher = lineValue(raw, "cipher");
        if (cipher == null || cipher.isEmpty()) cipher = "AES-128-CBC";

        String auth = lineValue(raw, "auth");
        if (auth == null || auth.isEmpty()) auth = "SHA1";

        String ca = block(raw, "ca");
        String cert = block(raw, "cert");
        String key = block(raw, "key");
        String tlsCrypt = block(raw, "tls-crypt");

        if (ca == null || cert == null || key == null) {
            throw new IllegalArgumentException("Required inline certificate block missing");
        }

        return new ParsedConfig(
                host, port, proto, cipher, auth,
                ca, key, cert, tlsCrypt == null ? "" : tlsCrypt);
    }

    private static String lineValue(String raw, String name) {
        Matcher m = Pattern.compile(
                "(?im)^\\s*" + Pattern.quote(name) + "\\s+(.+?)\\s*$")
                .matcher(raw);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String block(String raw, String name) {
        Matcher m = Pattern.compile(
                "(?is)<" + Pattern.quote(name) + ">.*?</" + Pattern.quote(name) + ">")
                .matcher(raw);
        return m.find() ? m.group(0).trim() : null;
    }

    private static String normalizeProto(String p) {
        String x = p.toLowerCase(Locale.US);
        return x.startsWith("udp") ? "udp" : "tcp";
    }

    private void checkIp() {
        if (ip == null) return;
        ip.setText("Public IP: checking...");

        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);

                String value;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    value = br.readLine();
                }

                String out = value == null ? "unknown" : value.trim();
                runOnUiThread(() -> ip.setText("Public IP: " + out));
            } catch (Exception e) {
                runOnUiThread(() -> ip.setText(
                        "Public IP check failed: " + e.getClass().getSimpleName()));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "🌐";
        String u = cc.toUpperCase(Locale.US);
        int a = u.codePointAt(0) - 'A' + 0x1F1E6;
        int b = u.codePointAt(1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    private static final class ParsedConfig {
        final String host;
        final int port;
        final String proto;
        final String cipher;
        final String auth;
        final String ca;
        final String key;
        final String cert;
        final String tlsCrypt;

        ParsedConfig(String host, int port, String proto, String cipher, String auth,
                     String ca, String key, String cert, String tlsCrypt) {
            this.host = host;
            this.port = port;
            this.proto = proto;
            this.cipher = cipher;
            this.auth = auth;
            this.ca = ca;
            this.key = key;
            this.cert = cert;
            this.tlsCrypt = tlsCrypt;
        }
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
    }
}
