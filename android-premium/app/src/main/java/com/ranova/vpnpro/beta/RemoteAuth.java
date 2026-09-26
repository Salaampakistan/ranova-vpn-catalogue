package com.ranova.vpnpro.beta;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class RemoteAuth {
    private static final String URL_VALUE =
            "https://raw.githubusercontent.com/etoedxb-jpg/ranova-vpn-catalogue/main/auth/users.json";

    private RemoteAuth() {}

    static JSONObject fetch() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(URL_VALUE).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent","RANOVA-VPN-PRO/0.4");
        try {
            if(c.getResponseCode()!=200)
                throw new IllegalStateException("HTTP "+c.getResponseCode());

            StringBuilder sb=new StringBuilder();
            try(BufferedReader br=new BufferedReader(
                    new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while((line=br.readLine())!=null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }
}
