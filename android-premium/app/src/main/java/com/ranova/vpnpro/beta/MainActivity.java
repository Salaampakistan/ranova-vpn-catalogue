package com.ranova.vpnpro.beta;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
import java.util.Locale;

import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

public class MainActivity extends Activity {
    private static final String CATALOGUE =
            "https://raw.githubusercontent.com/etoedxb-jpg/ranova-vpn-catalogue/main/servers.json";
    private static final int REQ_API=301, REQ_VPN=302;

    private final List<Server> servers=new ArrayList<>();
    private Server selected;

    private TextView flag,country,detail,status,ip,inText,outText,ping,speed,load,proto,connectText;
    private LinearLayout connectCircle;

    private IOpenVPNAPIService vpn;
    private boolean bound=false;
    private boolean connected=false;
    private String pending;
    private long baseRx=-1,baseTx=-1;
    private final Handler h=new Handler(Looper.getMainLooper());

    private final Runnable traffic=new Runnable(){
        @Override public void run(){
            if(connected){
                long rx=TrafficStats.getTotalRxBytes();
                long tx=TrafficStats.getTotalTxBytes();
                if(baseRx>=0&&rx>=baseRx)inText.setText(bytes(rx-baseRx));
                if(baseTx>=0&&tx>=baseTx)outText.setText(bytes(tx-baseTx));
            }
            h.postDelayed(this,1000);
        }
    };

    private final IOpenVPNStatusCallback cb=new IOpenVPNStatusCallback.Stub(){
        @Override public void newStatus(String uuid,String state,String message,String level){
            runOnUiThread(()->applyState(state,message));
        }
    };

    private final ServiceConnection conn=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName n,IBinder b){
            vpn=IOpenVPNAPIService.Stub.asInterface(b);
            bound=true;
            status.setText("Engine ready");
            requestApi();
        }
        @Override public void onServiceDisconnected(ComponentName n){
            vpn=null;
            bound=false;
            status.setText("Engine disconnected");
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);

        if(getSharedPreferences("session",MODE_PRIVATE)
                .getLong("expiry",0)<System.currentTimeMillis()){
            logout();
            return;
        }

        buildUi();
        fetchCatalogue();
        h.post(traffic);
        checkIp();
    }

    @Override protected void onStart(){
        super.onStart();
        bindOpenVpn();
    }

    @Override protected void onDestroy(){
        h.removeCallbacks(traffic);
        if(vpn!=null){
            try{vpn.unregisterStatusCallback(cb);}catch(Exception ignored){}
        }
        if(bound){
            try{unbindService(conn);}catch(Exception ignored){}
        }
        super.onDestroy();
    }

    private void buildUi(){
        SharedPreferences sp=getSharedPreferences("session",MODE_PRIVATE);
        String display=sp.getString("display_name","RANOVA User");

        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this,18),Ui.dp(this,18),Ui.dp(this,18),Ui.dp(this,30));
        root.setBackground(Ui.gradient(this,Ui.BG,0xff0b1730,0));
        scroll.addView(root);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top);

        LogoView lv=new LogoView(this);
        top.addView(lv,new LinearLayout.LayoutParams(Ui.dp(this,58),Ui.dp(this,58)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);
        tp.leftMargin=Ui.dp(this,10);
        top.addView(titles,tp);

        titles.addView(Ui.text(this,"RANOVA VPN PRO",20,Ui.TEXT,true));
        titles.addView(Ui.text(this,"Welcome, "+display,13,Ui.MUTED,false));

        TextView lo=Ui.pill(this,"LOGOUT");
        lo.setOnClickListener(v->logout());
        top.addView(lo);

        LinearLayout loc=Ui.card(this);
        Ui.topMargin(loc,this,18);
        root.addView(loc);
        loc.setOnClickListener(v->picker());

        LinearLayout lr=new LinearLayout(this);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        loc.addView(lr);

        flag=Ui.text(this,"🌐",34,Ui.TEXT,false);
        lr.addView(flag);

        LinearLayout lt=new LinearLayout(this);
        lt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ltp=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);
        ltp.leftMargin=Ui.dp(this,12);
        lr.addView(lt,ltp);

        country=Ui.text(this,"Loading locations...",18,Ui.TEXT,true);
        lt.addView(country);

        detail=Ui.text(this,"Automatic server catalogue",12,Ui.MUTED,false);
        lt.addView(detail);

        lr.addView(Ui.pill(this,"CHANGE"));

        LinearLayout hero=new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0,Ui.dp(this,26),0,Ui.dp(this,8));
        root.addView(hero);

        connectCircle=new LinearLayout(this);
        connectCircle.setGravity(Gravity.CENTER);
        connectCircle.setElevation(Ui.dp(this,10));
        connectCircle.setBackground(Ui.gradient(this,Ui.CYAN,Ui.BLUE,100));
        hero.addView(connectCircle,new LinearLayout.LayoutParams(Ui.dp(this,172),Ui.dp(this,172)));

        connectText=Ui.text(this,"CONNECT",19,0xff06111f,true);
        connectText.setGravity(Gravity.CENTER);
        connectCircle.addView(connectText);

        connectCircle.setOnClickListener(v->{
            if(connected) disconnect();
            else connect();
        });

        status=Ui.text(this,"Preparing engine...",14,Ui.MUTED,true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0,Ui.dp(this,14),0,0);
        hero.addView(status);

        LinearLayout trafficRow=new LinearLayout(this);
        trafficRow.setOrientation(LinearLayout.HORIZONTAL);
        Ui.topMargin(trafficRow,this,12);
        root.addView(trafficRow);

        LinearLayout a=stat("IN / DOWNLOAD","0 B");
        inText=(TextView)a.getChildAt(1);
        trafficRow.addView(a,half(false));

        LinearLayout b=stat("OUT / UPLOAD","0 B");
        outText=(TextView)b.getChildAt(1);
        trafficRow.addView(b,half(true));

        TextView st=Ui.text(this,"SERVER INTELLIGENCE",11,Ui.CYAN,true);
        st.setLetterSpacing(.08f);
        st.setPadding(0,Ui.dp(this,24),0,Ui.dp(this,10));
        root.addView(st);

        LinearLayout r1=new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(r1);

        LinearLayout p1=stat("PING","—");
        ping=(TextView)p1.getChildAt(1);
        r1.addView(p1,half(false));

        LinearLayout p2=stat("SPEED","—");
        speed=(TextView)p2.getChildAt(1);
        r1.addView(p2,half(true));

        LinearLayout r2=new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        Ui.topMargin(r2,this,10);
        root.addView(r2);

        LinearLayout p3=stat("LOAD","—");
        load=(TextView)p3.getChildAt(1);
        r2.addView(p3,half(false));

        LinearLayout p4=stat("PROTOCOL","—");
        proto=(TextView)p4.getChildAt(1);
        r2.addView(p4,half(true));

        LinearLayout ipc=Ui.card(this);
        Ui.topMargin(ipc,this,14);
        root.addView(ipc);

        ipc.addView(Ui.text(this,"PUBLIC IP",11,Ui.CYAN,true));
        ip=Ui.text(this,"Checking...",20,Ui.TEXT,true);
        ipc.addView(ip);
        ipc.setOnClickListener(v->checkIp());

        LinearLayout quick=new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        Ui.topMargin(quick,this,14);
        root.addView(quick);

        Button smart=button("⚡ SMART CONNECT");
        smart.setOnClickListener(v->smartConnect());
        quick.addView(smart,half(false));

        Button locations=button("🌍 LOCATIONS");
        locations.setOnClickListener(v->picker());
        quick.addView(locations,half(true));

        TextView note=Ui.text(this,
                "IN/OUT is live device traffic during this VPN session. Load is shown as active relay sessions.",
                11,Ui.MUTED,false);
        note.setPadding(0,Ui.dp(this,16),0,0);
        root.addView(note);

        setContentView(scroll);
    }

    private LinearLayout stat(String label,String value){
        LinearLayout c=Ui.card(this);
        TextView l=Ui.text(this,label,10,Ui.MUTED,true);
        l.setLetterSpacing(.06f);
        c.addView(l);

        TextView v=Ui.text(this,value,20,Ui.TEXT,true);
        v.setPadding(0,Ui.dp(this,5),0,0);
        c.addView(v);
        return c;
    }

    private LinearLayout.LayoutParams half(boolean left){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(
                0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);
        if(left)p.leftMargin=Ui.dp(this,5);
        else p.rightMargin=Ui.dp(this,5);
        return p;
    }

    private Button button(String t){
        Button b=new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setTextSize(12);
        b.setBackground(Ui.cardBg(this));
        return b;
    }

    private void fetchCatalogue(){
        new Thread(()->{
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(CATALOGUE).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent","RANOVA-VPN-PRO/0.3");

                if(c.getResponseCode()!=200)
                    throw new IllegalStateException("HTTP "+c.getResponseCode());

                StringBuilder sb=new StringBuilder();
                try(BufferedReader br=new BufferedReader(
                        new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                    char[] buf=new char[8192];
                    int n,total=0;
                    while((n=br.read(buf))!=-1){
                        total+=n;
                        if(total>5000000)throw new IllegalStateException("too large");
                        sb.append(buf,0,n);
                    }
                }

                JSONArray arr=new JSONObject(sb.toString()).getJSONArray("servers");
                List<Server> next=new ArrayList<>();

                for(int i=0;i<arr.length();i++){
                    JSONObject o=arr.getJSONObject(i);
                    next.add(new Server(
                            o.optString("country_code","--"),
                            o.optString("country","Unknown"),
                            o.getString("ip"),
                            o.getInt("port"),
                            o.getString("protocol"),
                            o.optInt("ping_ms",-1),
                            o.optLong("speed_bps",0),
                            o.optInt("sessions",0),
                            o.getString("openvpn_config_base64")));
                }

                runOnUiThread(()->{
                    servers.clear();
                    servers.addAll(next);
                    if(!servers.isEmpty()){
                        selected=servers.get(0);
                        showSelected();
                    }
                });

            }catch(Exception e){
                runOnUiThread(()->{
                    country.setText("Catalogue unavailable");
                    detail.setText(e.getClass().getSimpleName());
                });
            }finally{
                if(c!=null)c.disconnect();
            }
        }).start();
    }

    private void showSelected(){
        if(selected==null)return;

        flag.setText(flag(selected.cc));
        country.setText(selected.country);
        detail.setText(selected.protocol.toUpperCase(Locale.US)+" • "+selected.ip+":"+selected.port);
        ping.setText(selected.ping>=0?selected.ping+" ms":"—");
        speed.setText(selected.speed>0
                ?String.format(Locale.US,"%.0f Mbps",selected.speed/1000000.0)
                :"—");
        load.setText(selected.sessions+" sess.");
        proto.setText(selected.protocol.toUpperCase(Locale.US));
    }

    private void picker(){
        if(servers.isEmpty()){
            status.setText("Locations are still loading");
            return;
        }

        LinearLayout wrap=new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10),0);

        EditText search=new EditText(this);
        search.setHint("Search country or IP");
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(0xff7887a5);
        search.setBackground(Ui.fieldBg(this));
        search.setPadding(Ui.dp(this,12),Ui.dp(this,9),Ui.dp(this,12),Ui.dp(this,9));
        wrap.addView(search);

        ListView list=new ListView(this);
        list.setDividerHeight(0);
        wrap.addView(list,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,Ui.dp(this,430)));

        List<Server> filtered=new ArrayList<>(servers);
        ServerAdapter ad=new ServerAdapter(this,filtered);
        list.setAdapter(ad);

        AlertDialog d=new AlertDialog.Builder(this)
                .setTitle("Choose Location")
                .setView(wrap)
                .setNegativeButton("Close",null)
                .create();

        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){
                String q=s.toString().toLowerCase(Locale.US).trim();
                filtered.clear();

                for(Server x:servers){
                    if(q.isEmpty()
                            ||x.country.toLowerCase(Locale.US).contains(q)
                            ||x.cc.toLowerCase(Locale.US).contains(q)
                            ||x.ip.contains(q)){
                        filtered.add(x);
                    }
                }
                ad.notifyDataSetChanged();
            }
            public void afterTextChanged(android.text.Editable e){}
        });

        list.setOnItemClickListener((p,v,pos,id)->{
            selected=filtered.get(pos);
            showSelected();
            d.dismiss();
        });

        d.show();
    }

    private void smartConnect(){
        if(servers.isEmpty())return;

        Server best=null;
        for(Server s:servers){
            if(s.ping>0&&(best==null||s.ping<best.ping))
                best=s;
        }

        selected=best==null?servers.get(0):best;
        showSelected();
        connect();
    }

    private void bindOpenVpn(){
        Intent i=new Intent(IOpenVPNAPIService.class.getName());
        i.setPackage("de.blinkt.openvpn");

        try{
            boolean ok=bindService(i,conn,Context.BIND_AUTO_CREATE);
            if(!ok)status.setText("Install OpenVPN for Android");
        }catch(Exception e){
            status.setText("OpenVPN engine unavailable");
        }
    }

    private void requestApi(){
        if(vpn==null)return;

        try{
            Intent i=vpn.prepare(getPackageName());
            if(i!=null)startActivityForResult(i,REQ_API);
            else apiReady();
        }catch(RemoteException e){
            status.setText("API access error");
        }
    }

    private void apiReady(){
        try{
            vpn.registerStatusCallback(cb);
            status.setText("Ready to connect");
        }catch(Exception e){
            status.setText("Engine callback error");
        }
    }

    private void connect(){
        if(selected==null){
            status.setText("Select a location");
            return;
        }

        if(vpn==null){
            status.setText("OpenVPN for Android required");
            return;
        }

        try{
            String cfg=new String(
                    Base64.decode(selected.cfg,Base64.DEFAULT),
                    StandardCharsets.UTF_8);

            if(!cfg.toLowerCase(Locale.US).contains("client")
                    ||!cfg.contains("remote "+selected.ip+" "+selected.port)){
                throw new IllegalStateException();
            }

            pending=cfg;
            status.setText("Preparing secure tunnel...");

            Intent p=vpn.prepareVPNService();
            if(p==null)startPending();
            else startActivityForResult(p,REQ_VPN);

        }catch(Exception e){
            status.setText("Connect setup failed");
        }
    }

    private void startPending(){
        if(vpn==null||pending==null)return;

        try{
            status.setText("Connecting...");
            vpn.startVPN(pending);
        }catch(Exception e){
            status.setText("VPN start failed");
        }
    }

    private void disconnect(){
        if(vpn==null)return;

        try{
            vpn.disconnect();
            connected=false;
            connectText.setText("CONNECT");
            connectCircle.setBackground(Ui.gradient(this,Ui.CYAN,Ui.BLUE,100));
            status.setText("Disconnected");
            status.setTextColor(Ui.MUTED);
            inText.setText("0 B");
            outText.setText("0 B");
            checkIp();
        }catch(Exception e){
            status.setText("Disconnect failed");
        }
    }

    private void applyState(String state,String message){
        String s=state==null?"":state.toUpperCase(Locale.US);

        status.setText(state==null?"Unknown":state.replace("_"," "));

        if(s.contains("CONNECTED")&&!connected){
            connected=true;
            baseRx=TrafficStats.getTotalRxBytes();
            baseTx=TrafficStats.getTotalTxBytes();

            connectText.setText("DISCONNECT");
            connectCircle.setBackground(Ui.gradient(this,Ui.GREEN,0xff1bad86,100));
            status.setTextColor(Ui.GREEN);
            checkIp();

        }else if(!s.contains("CONNECTED")
                &&(s.contains("DISCONNECTED")||s.contains("NOPROCESS"))){

            connected=false;
            connectText.setText("CONNECT");
            connectCircle.setBackground(Ui.gradient(this,Ui.CYAN,Ui.BLUE,100));
            status.setTextColor(Ui.MUTED);
        }
    }

    private void checkIp(){
        ip.setText("Checking...");

        new Thread(()->{
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL("https://api.ipify.org").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(12000);

                String val;
                try(BufferedReader br=new BufferedReader(
                        new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                    val=br.readLine();
                }

                String out=val==null?"Unknown":val.trim();
                runOnUiThread(()->ip.setText(out));

            }catch(Exception e){
                runOnUiThread(()->ip.setText("Unavailable"));
            }finally{
                if(c!=null)c.disconnect();
            }
        }).start();
    }

    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);

        if(res!=RESULT_OK){
            status.setText("Permission denied");
            return;
        }

        if(req==REQ_API)apiReady();
        else if(req==REQ_VPN)startPending();
    }

    private void logout(){
        getSharedPreferences("session",MODE_PRIVATE).edit().clear().apply();
        startActivity(new Intent(this,LoginActivity.class));
        finish();
    }

    private static String flag(String cc){
        if(cc==null||cc.length()!=2)return "🌐";

        String u=cc.toUpperCase(Locale.US);
        int a=u.codePointAt(0)-'A'+0x1F1E6;
        int b=u.codePointAt(1)-'A'+0x1F1E6;

        return new String(Character.toChars(a))
                +new String(Character.toChars(b));
    }

    private static String bytes(long n){
        if(n<1024)return n+" B";

        double kb=n/1024.0;
        if(kb<1024)return String.format(Locale.US,"%.1f KB",kb);

        double mb=kb/1024.0;
        if(mb<1024)return String.format(Locale.US,"%.1f MB",mb);

        return String.format(Locale.US,"%.2f GB",mb/1024.0);
    }

    private static final class Server{
        final String cc,country,ip,protocol,cfg;
        final int port,ping,sessions;
        final long speed;

        Server(String cc,String country,String ip,int port,String protocol,
               int ping,long speed,int sessions,String cfg){
            this.cc=cc;
            this.country=country;
            this.ip=ip;
            this.port=port;
            this.protocol=protocol;
            this.ping=ping;
            this.speed=speed;
            this.sessions=sessions;
            this.cfg=cfg;
        }
    }

    private static final class ServerAdapter extends ArrayAdapter<Server>{
        private final Context c;

        ServerAdapter(Context c,List<Server> items){
            super(c,0,items);
            this.c=c;
        }

        @Override public View getView(int pos,View convert,android.view.ViewGroup parent){
            Server s=getItem(pos);

            LinearLayout row=new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c,12),Ui.dp(c,10),Ui.dp(c,12),Ui.dp(c,10));
            row.setBackground(Ui.cardBg(c));

            row.addView(Ui.text(c,flag(s.cc),28,Ui.TEXT,false));

            LinearLayout mid=new LinearLayout(c);
            mid.setOrientation(LinearLayout.VERTICAL);

            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(
                    0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);
            mp.leftMargin=Ui.dp(c,10);
            row.addView(mid,mp);

            mid.addView(Ui.text(c,s.country,15,Ui.TEXT,true));
            mid.addView(Ui.text(c,
                    s.ip+" • "+s.protocol.toUpperCase(Locale.US)+" "+s.port,
                    11,Ui.MUTED,false));

            TextView right=Ui.text(c,
                    (s.ping>=0?s.ping+" ms":"—")+"\n"+s.sessions+" sess.",
                    11,Ui.CYAN,true);
            right.setGravity(Gravity.END);
            row.addView(right);

            row.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                    Ui.dp(c,70)));

            return row;
        }
    }
}
