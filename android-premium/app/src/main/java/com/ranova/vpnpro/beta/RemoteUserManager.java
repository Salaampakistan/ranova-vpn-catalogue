package com.ranova.vpnpro.beta;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class RemoteUserManager {
    private static final String API =
            "https://api.github.com/repos/etoedxb-jpg/ranova-vpn-catalogue/contents/auth/users.json";

    private RemoteUserManager() {}

    static JSONObject read(String token) throws Exception {
        HttpURLConnection c=open("GET",token);
        try {
            if(c.getResponseCode()!=200)
                throw new IllegalStateException("GitHub HTTP "+c.getResponseCode());

            String body=readBody(c);
            JSONObject root=new JSONObject(body);
            String encoded=root.getString("content").replace("\n","");
            byte[] raw=Base64.decode(encoded,Base64.DEFAULT);

            JSONObject out=new JSONObject(new String(raw,StandardCharsets.UTF_8));
            out.put("_sha",root.getString("sha"));
            return out;
        } finally {
            c.disconnect();
        }
    }

    static void upsertUser(String token,String username,String displayName,char[] password) throws Exception {
        JSONObject root=read(token);
        JSONArray users=root.optJSONArray("users");
        if(users==null) users=new JSONArray();

        JSONObject user=AuthUtil.userObject(username,displayName,password);
        JSONArray next=new JSONArray();
        boolean replaced=false;

        for(int i=0;i<users.length();i++){
            JSONObject x=users.getJSONObject(i);
            if(username.equalsIgnoreCase(x.optString("username"))){
                next.put(user);
                replaced=true;
            } else {
                next.put(x);
            }
        }

        if(!replaced) next.put(user);

        JSONObject cleaned=base(root,next);
        write(token,root.getString("_sha"),cleaned,
                replaced ? "Update RANOVA VPN user" : "Add RANOVA VPN user");
    }

    static void setEnabled(String token,String username,boolean enabled) throws Exception {
        JSONObject root=read(token);
        JSONArray users=root.optJSONArray("users");
        if(users==null) throw new IllegalArgumentException("User not found");

        JSONArray next=new JSONArray();
        boolean found=false;

        for(int i=0;i<users.length();i++){
            JSONObject x=users.getJSONObject(i);
            if(username.equalsIgnoreCase(x.optString("username"))){
                x.put("enabled",enabled);
                found=true;
            }
            next.put(x);
        }

        if(!found) throw new IllegalArgumentException("User not found");

        write(token,root.getString("_sha"),base(root,next),
                enabled ? "Enable RANOVA VPN user" : "Disable RANOVA VPN user");
    }

    static void deleteUser(String token,String username) throws Exception {
        JSONObject root=read(token);
        JSONArray users=root.optJSONArray("users");
        if(users==null) throw new IllegalArgumentException("User not found");

        JSONArray next=new JSONArray();
        boolean found=false;

        for(int i=0;i<users.length();i++){
            JSONObject x=users.getJSONObject(i);
            if(username.equalsIgnoreCase(x.optString("username"))){
                found=true;
                continue;
            }
            next.put(x);
        }

        if(!found) throw new IllegalArgumentException("User not found");

        write(token,root.getString("_sha"),base(root,next),"Delete RANOVA VPN user");
    }

    private static JSONObject base(JSONObject old,JSONArray users) throws Exception {
        JSONObject o=new JSONObject();
        o.put("schema",2);
        o.put("updated_at",now());
        o.put("users",users);
        return o;
    }

    private static void write(String token,String sha,JSONObject content,String message) throws Exception {
        JSONObject payload=new JSONObject();
        payload.put("message",message);
        payload.put("sha",sha);
        payload.put("branch","main");
        payload.put("content",Base64.encodeToString(
                (content.toString(2)+"\n").getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP));

        HttpURLConnection c=open("PUT",token);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);

            try(OutputStream out=c.getOutputStream()){
                out.write(body);
            }

            int code=c.getResponseCode();
            if(code!=200 && code!=201){
                String detail=readBody(c);
                throw new IllegalStateException("GitHub HTTP "+code+": "+detail);
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String method,String token) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept","application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        c.setRequestProperty("Authorization","Bearer "+token.trim());
        c.setRequestProperty("User-Agent","RANOVA-VPN-PRO/0.4");
        return c;
    }

    private static String readBody(HttpURLConnection c) throws Exception {
        java.io.InputStream in=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream();
        if(in==null) return "";
        StringBuilder sb=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String line;
            while((line=br.readLine())!=null) sb.append(line);
        }
        return sb.toString();
    }

    private static String now(){
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
