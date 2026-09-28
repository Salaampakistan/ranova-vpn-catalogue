package com.ranova.vpnpro.beta;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.Toast;

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


public class MainActivity extends Activity {
    private static final String CATALOGUE =
            "https://raw.githubusercontent.com/etoedxb-jpg/ranova-vpn-catalogue/main/servers.json";
    private static final int REQ_VPN=302;

    private final List<Server> servers=new ArrayList<>();
    private Server selected;

    private TextView flag,country,detail,status,ip,inText,outText,ping,speed,load,proto,connectText;
    private LinearLayout connectCircle;

    private static final int MAX_AUTO_FAILOVER=3;
    private static final long SERVER_COOLDOWN_MS=5L*60L*1000L;

    private EmbeddedVpnController vpnController;
    private boolean connected=false;
    private boolean connectionAttempt=false;
    private boolean autoRetryScheduled=false;
    private boolean manualDisconnect=false;
    private int failoverCount=0;
    private final java.util.HashSet<String> failedThisSession=new java.util.HashSet<>();
    private String pending;
    private long baseRx=-1,baseTx=-1;
    private final Handler h=new Handler(Looper.getMainLooper());

    private final Runnable connectTimeout=()->{
        if(connectionAttempt&&!connected){
            handleConnectionFailure("Connection timed out");
        }
    };

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

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);

        if(getSharedPreferences("session",MODE_PRIVATE)
                .getLong("expiry",0)<System.currentTimeMillis()){
            logout();
            return;
        }

        buildUi();

        vpnController=new EmbeddedVpnController(this,new EmbeddedVpnController.Listener(){
            @Override public void onState(String state){
                runOnUiThread(()->applyState(state,null));
            }

            @Override public void onError(String message){
                runOnUiThread(()->{
                    if(connectionAttempt&&!connected){
                        handleConnectionFailure(message==null?"Server connection failed":message);
                    }else if(message!=null&&!message.trim().isEmpty()){
                        status.setText(message);
                    }
                });
            }
        });
        vpnController.bind();

        fetchCatalogue();
        h.post(traffic);
        checkIp();
    }

    @Override protected void onDestroy(){
        h.removeCallbacks(traffic);
        if(vpnController!=null) vpnController.release();
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
        lv.setContentDescription("Open RANOVA menu");
        lv.setOnClickListener(v->showMainMenu());

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
            if(connected||connectionAttempt) disconnect();
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
                        String lastGood=getSharedPreferences("vpn_health",MODE_PRIVATE)
                                .getString("last_good","");
                        Server preferred=findServerByKey(lastGood);
                        selected=(preferred!=null&&!isCooling(preferred))
                                ?preferred
                                :findBestServer(null);
                        if(selected==null) selected=servers.get(0);
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

        list.setOnItemLongClickListener((p,v,pos,id)->{
            toggleFavorite(filtered.get(pos));
            Toast.makeText(this,"Favorite updated.",Toast.LENGTH_SHORT).show();
            return true;
        });

        d.show();
    }

    private void showMainMenu(){
        ArrayList<String> items=new ArrayList<>();
        for(String x:MenuScreen.ITEMS){
            if("Access Control".equals(x)
                    && !"owner".equalsIgnoreCase(
                    getSharedPreferences("session",MODE_PRIVATE).getString("role","user"))) {
                continue;
            }
            items.add(x);
        }

        String[] menu=items.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("RANOVA Control Center")
                .setItems(menu,(d,which)->{
                    String item=menu[which];

                    if("Locations".equals(item)) picker();
                    else if("Smart Connect".equals(item)) smartConnect();
                    else if("Favorites".equals(item)) showFavorites();
                    else if("Recent Servers".equals(item)) showRecent();
                    else if("My Account".equals(item)) {
                        Intent i=new Intent(this,LoginActivity.class);
                        i.putExtra("change_owner",true);
                        startActivity(i);
                    }
                    else if("Access Control".equals(item)) showAccessControl();
                    else if("Settings".equals(item)) showSettings();
                    else if("About".equals(item)) showAbout();
                    else if("Logout".equals(item)) logout();
                })
                .show();
    }

    private void showAccessControl(){
        if(!"owner".equalsIgnoreCase(
                getSharedPreferences("session",MODE_PRIVATE).getString("role","user"))){
            return;
        }

        String[] items={
                "View Users",
                "Create / Reset User",
                "Enable User",
                "Disable User",
                "Delete User",
                AdminTokenStore.has(this)?"Change GitHub Admin Token":"Set GitHub Admin Token",
                "Remove Saved Admin Token"
        };

        new AlertDialog.Builder(this)
                .setTitle("Access Control")
                .setItems(items,(d,which)->{
                    if(which==0) viewRemoteUsers();
                    else if(which==1) createRemoteUser();
                    else if(which==2) userAction("Enable User","enable");
                    else if(which==3) userAction("Disable User","disable");
                    else if(which==4) userAction("Delete User","delete");
                    else if(which==5) promptAdminToken();
                    else if(which==6) {
                        AdminTokenStore.clear(this);
                        Toast.makeText(this,"Admin token removed.",Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private boolean requireAdminToken(){
        if(AdminTokenStore.has(this)) return true;
        promptAdminToken();
        return false;
    }

    private void promptAdminToken(){
        final EditText input=new EditText(this);
        input.setHint("Fine-grained GitHub token");
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(0xff7887a5);
        input.setBackground(Ui.fieldBg(this));
        input.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10));

        new AlertDialog.Builder(this)
                .setTitle("Owner Admin Token")
                .setMessage("Paste a fine-grained GitHub token with Contents: Read and write access to only ranova-vpn-catalogue. It stays on this owner phone.")
                .setView(input)
                .setPositiveButton("Save",(d,w)->{
                    String token=input.getText().toString().trim();
                    if(token.length()<20) {
                        Toast.makeText(this,"Token looks invalid.",Toast.LENGTH_LONG).show();
                        return;
                    }
                    AdminTokenStore.save(this,token);
                    Toast.makeText(this,"Admin token saved.",Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void viewRemoteUsers(){
        if(!requireAdminToken()) return;
        status.setText("Loading users...");

        new Thread(()->{
            try{
                JSONObject root=RemoteUserManager.read(AdminTokenStore.get(this));
                JSONArray arr=root.optJSONArray("users");
                StringBuilder out=new StringBuilder();

                if(arr==null||arr.length()==0){
                    out.append("No secondary users created.");
                }else{
                    for(int i=0;i<arr.length();i++){
                        JSONObject x=arr.getJSONObject(i);
                        out.append(x.optBoolean("enabled",false)?"● ":"○ ")
                                .append(x.optString("username"))
                                .append("  •  ")
                                .append(x.optString("display_name",x.optString("username")))
                                .append("\n");
                    }
                }

                String text=out.toString().trim();
                runOnUiThread(()->{
                    status.setText("Ready to connect");
                    new AlertDialog.Builder(this)
                            .setTitle("RANOVA Users")
                            .setMessage(text)
                            .setPositiveButton("OK",null)
                            .show();
                });
            }catch(Exception e){
                runOnUiThread(()->adminError(e));
            }
        }).start();
    }

    private void createRemoteUser(){
        if(!requireAdminToken()) return;

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this,8),Ui.dp(this,4),Ui.dp(this,8),0);

        EditText u=adminField("Username");
        EditText n=adminField("Display name");
        EditText p=adminField("Password");
        p.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        box.addView(u);
        box.addView(n);
        box.addView(p);

        new AlertDialog.Builder(this)
                .setTitle("Create / Reset User")
                .setMessage("This user can sign in on another phone with only this username and password.")
                .setView(box)
                .setPositiveButton("Publish",(d,w)->{
                    String username=u.getText().toString().trim();
                    String display=n.getText().toString().trim();
                    char[] pw=p.getText().toString().toCharArray();

                    if(username.length()<4||pw.length<8){
                        Toast.makeText(this,"Username 4+ chars and password 8+ chars.",Toast.LENGTH_LONG).show();
                        java.util.Arrays.fill(pw,'\0');
                        return;
                    }
                    if(username.equalsIgnoreCase(OwnerStore.username(this))){
                        Toast.makeText(this,"Owner username cannot be used as a secondary user.",Toast.LENGTH_LONG).show();
                        java.util.Arrays.fill(pw,'\0');
                        return;
                    }
                    if(display.isEmpty()) display=username;

                    final String finalDisplay=display;
                    status.setText("Publishing user...");

                    new Thread(()->{
                        try{
                            RemoteUserManager.upsertUser(
                                    AdminTokenStore.get(this),
                                    username,
                                    finalDisplay,
                                    pw);
                            runOnUiThread(()->{
                                status.setText("Ready to connect");
                                Toast.makeText(this,"User published. They can now sign in.",Toast.LENGTH_LONG).show();
                            });
                        }catch(Exception e){
                            runOnUiThread(()->adminError(e));
                        }finally{
                            java.util.Arrays.fill(pw,'\0');
                        }
                    }).start();
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void userAction(String title,String action){
        if(!requireAdminToken()) return;

        EditText input=adminField("Username");

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Confirm",(d,w)->{
                    String username=input.getText().toString().trim();
                    if(username.isEmpty()) return;

                    status.setText(title+"...");
                    new Thread(()->{
                        try{
                            if("enable".equals(action))
                                RemoteUserManager.setEnabled(AdminTokenStore.get(this),username,true);
                            else if("disable".equals(action))
                                RemoteUserManager.setEnabled(AdminTokenStore.get(this),username,false);
                            else
                                RemoteUserManager.deleteUser(AdminTokenStore.get(this),username);

                            runOnUiThread(()->{
                                status.setText("Ready to connect");
                                Toast.makeText(this,title+" completed.",Toast.LENGTH_LONG).show();
                            });
                        }catch(Exception e){
                            runOnUiThread(()->adminError(e));
                        }
                    }).start();
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private EditText adminField(String hint){
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(0xff7887a5);
        e.setBackground(Ui.fieldBg(this));
        e.setPadding(Ui.dp(this,12),Ui.dp(this,9),Ui.dp(this,12),Ui.dp(this,9));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin=Ui.dp(this,8);
        e.setLayoutParams(lp);
        return e;
    }

    private void adminError(Exception e){
        status.setText("User management error");
        String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        new AlertDialog.Builder(this)
                .setTitle("Access Control Error")
                .setMessage(message)
                .setPositiveButton("OK",null)
                .show();
    }

    private void showSettings(){
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setMessage(
                        "Automatic catalogue: ON\n"+
                        "Smart Connect: ON\n"+
                        "Country flags: ON\n"+
                        "Live IN / OUT: ON\n"+
                        "Auto failover: ON (up to "+MAX_AUTO_FAILOVER+" backups)\n"+
                        "Dead-server cooldown: 5 minutes\n"+
                        "Session timeout: 12 hours")
                .setPositiveButton("OK",null)
                .show();
    }

    private void showAbout(){
        new AlertDialog.Builder(this)
                .setTitle("RANOVA VPN PRO")
                .setMessage(
                        "Premium Beta 0.7 Stability\n\n"+
                        "Owner: Muhammad Ali Adeel\n"+
                        "Automatic VPN catalogue, smart connect and premium connection dashboard.")
                .setPositiveButton("OK",null)
                .show();
    }

    private String serverKey(Server x){
        return x.ip+"|"+x.port+"|"+x.protocol;
    }

    private void toggleFavorite(Server x){
        java.util.Set<String> current=
                getSharedPreferences("vpn_lists",MODE_PRIVATE)
                        .getStringSet("favorites",new java.util.HashSet<>());

        java.util.HashSet<String> next=new java.util.HashSet<>(current);
        String k=serverKey(x);

        if(next.contains(k)) next.remove(k);
        else next.add(k);

        getSharedPreferences("vpn_lists",MODE_PRIVATE)
                .edit().putStringSet("favorites",next).apply();
    }

    private void showFavorites(){
        java.util.Set<String> fav=
                getSharedPreferences("vpn_lists",MODE_PRIVATE)
                        .getStringSet("favorites",new java.util.HashSet<>());

        ArrayList<Server> list=new ArrayList<>();
        for(Server x:servers) if(fav.contains(serverKey(x))) list.add(x);

        showServerSubset("Favorite Locations",list);
    }

    private void saveRecent(Server x){
        String key=serverKey(x);
        String old=getSharedPreferences("vpn_lists",MODE_PRIVATE)
                .getString("recent","");

        ArrayList<String> keys=new ArrayList<>();
        keys.add(key);

        if(!old.isEmpty()){
            for(String k:old.split("\n")){
                if(!k.isEmpty()&&!k.equals(key)&&keys.size()<8) keys.add(k);
            }
        }

        StringBuilder out=new StringBuilder();
        for(String k:keys){
            if(out.length()>0) out.append("\n");
            out.append(k);
        }

        getSharedPreferences("vpn_lists",MODE_PRIVATE)
                .edit().putString("recent",out.toString()).apply();
    }

    private void showRecent(){
        String recent=getSharedPreferences("vpn_lists",MODE_PRIVATE)
                .getString("recent","");

        ArrayList<Server> list=new ArrayList<>();

        if(!recent.isEmpty()){
            for(String k:recent.split("\n")){
                for(Server x:servers){
                    if(k.equals(serverKey(x))){
                        list.add(x);
                        break;
                    }
                }
            }
        }

        showServerSubset("Recent Servers",list);
    }

    private void showServerSubset(String title,List<Server> data){
        if(data.isEmpty()){
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("No servers saved here yet.")
                    .setPositiveButton("OK",null)
                    .show();
            return;
        }

        ListView list=new ListView(this);
        list.setDividerHeight(0);
        ServerAdapter adapter=new ServerAdapter(this,data);
        list.setAdapter(adapter);

        AlertDialog d=new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(list)
                .setNegativeButton("Close",null)
                .create();

        list.setOnItemClickListener((p,v,pos,id)->{
            selected=data.get(pos);
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

    private void connect(){
        if(selected==null){
            status.setText("Select a location");
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

            pending=sanitizeEmbeddedProfile(cfg);
            saveRecent(selected);
            status.setText("Preparing secure tunnel...");

            Intent permission=VpnService.prepare(this);
            if(permission==null) startPending();
            else startActivityForResult(permission,REQ_VPN);

        }catch(Exception e){
            status.setText("Connect setup failed");
        }
    }

    private static String sanitizeEmbeddedProfile(String raw){
        StringBuilder out=new StringBuilder();

        for(String line:raw.replace("\r\n","\n").replace("\r","\n").split("\n")){
            String t=line.trim().toLowerCase(Locale.US);

            // Keep the server's original cipher/auth profile. Only make retries finite
            // so a dead relay never leaves RANOVA spinning forever.
            if(t.startsWith("connect-retry ")
                    ||t.startsWith("connect-retry-max ")
                    ||t.startsWith("resolv-retry ")){
                continue;
            }

            out.append(line).append("\n");
        }

        out.append("connect-retry 1 1\n");
        out.append("connect-retry-max 1\n");
        out.append("resolv-retry 3\n");
        out.append("block-ipv6\n");

        return out.toString();
    }

    private void startPending(){
        if(vpnController==null||pending==null)return;

        connectionAttempt=true;
        connected=false;
        h.removeCallbacks(connectTimeout);
        h.postDelayed(connectTimeout,20000);

        connectText.setText("CANCEL");
        status.setText("Connecting...");
        status.setTextColor(Ui.CYAN);
        vpnController.start(pending);
    }

    private void disconnect(){
        h.removeCallbacks(connectTimeout);
        connectionAttempt=false;
        if(vpnController!=null) vpnController.stop();

        connected=false;
        connectText.setText("CONNECT");
        connectCircle.setBackground(Ui.gradient(this,Ui.CYAN,Ui.BLUE,100));
        status.setText("Disconnected");
        status.setTextColor(Ui.MUTED);
        inText.setText("0 B");
        outText.setText("0 B");
        checkIp();
    }

    private void applyState(String state,String message){
        String s=state==null?"":state.toUpperCase(Locale.US).trim();

        if("CONNECTED".equals(s)){
            h.removeCallbacks(connectTimeout);
            connectionAttempt=false;
            connected=true;

            baseRx=TrafficStats.getTotalRxBytes();
            baseTx=TrafficStats.getTotalTxBytes();

            connectText.setText("DISCONNECT");
            connectCircle.setBackground(Ui.gradient(this,Ui.GREEN,0xff1bad86,100));
            status.setText("CONNECTED");
            status.setTextColor(Ui.GREEN);
            checkIp();
            return;
        }

        if("CONNECTING".equals(s)
                ||"RESOLVE".equals(s)
                ||"WAIT".equals(s)
                ||"GET_CONFIG".equals(s)
                ||"ASSIGN_IP".equals(s)
                ||"RECONNECTING".equals(s)){
            if(connectionAttempt){
                connectText.setText("CANCEL");
                status.setText("Connecting...");
                status.setTextColor(Ui.CYAN);
            }
            return;
        }

        if("DISCONNECTED".equals(s)
                ||"IDLE".equals(s)
                ||"READYFORCONNECT".equals(s)
                ||"NOPROCESS".equals(s)){
            boolean wasAttempt=connectionAttempt;
            connectionAttempt=false;
            connected=false;
            h.removeCallbacks(connectTimeout);

            connectText.setText("CONNECT");
            connectCircle.setBackground(Ui.gradient(this,Ui.CYAN,Ui.BLUE,100));
            status.setText(wasAttempt?"Connection failed. Choose another server.":"Disconnected");
            status.setTextColor(Ui.MUTED);
            return;
        }

        if(!s.isEmpty()){
            status.setText(s.replace("_"," "));
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

        if(req==REQ_VPN)startPending();
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
