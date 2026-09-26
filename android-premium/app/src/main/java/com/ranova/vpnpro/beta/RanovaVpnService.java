package com.ranova.vpnpro.beta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.tim.basevpn.state.ConnectionState;
import com.tim.openvpn.model.CIDRIP;
import com.tim.openvpn.service.IOpenVPNService;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RanovaVpnService extends VpnService implements IOpenVPNService {
    public static final String ACTION_START="com.ranova.vpnpro.START";
    public static final String ACTION_STOP="com.ranova.vpnpro.STOP";
    public static final String EXTRA_CONFIG="config";

    private static final String CHANNEL_ID="ranova_vpn";
    private static final int NOTIFICATION_ID=9201;

    private volatile RanovaOpenVpnThread engine;
    private volatile Thread engineThread;
    private volatile boolean stopping;

    private int mtu=1500;
    private CIDRIP localIp;
    private String localIpv6;
    private String domain;

    private final List<String> dns=new ArrayList<>();
    private final Set<String> includeV4=new LinkedHashSet<>();
    private final Set<String> includeV6=new LinkedHashSet<>();
    private final Set<String> excludeV4=new LinkedHashSet<>();
    private final Set<String> excludeV6=new LinkedHashSet<>();

    @Override
    public void onCreate(){
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null) return START_NOT_STICKY;

        String action=intent.getAction();

        if(ACTION_STOP.equals(action)){
            stopEngine();
            return START_NOT_STICKY;
        }

        if(ACTION_START.equals(action)){
            String config=intent.getStringExtra(EXTRA_CONFIG);
            if(config==null||config.trim().isEmpty()){
                RanovaVpnBus.error("VPN profile is empty");
                stopSelf();
                return START_NOT_STICKY;
            }

            startForeground(NOTIFICATION_ID,notification("Connecting securely…"));
            startEngine(config);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private synchronized void startEngine(String config){
        stopEngineInternal(false);
        resetTunState();
        stopping=false;
        RanovaVpnBus.clearError();
        RanovaVpnBus.state("CONNECTING");

        engine=new RanovaOpenVpnThread(this,config);
        engineThread=new Thread(engine,"RANOVA-VPN-ENGINE");
        engineThread.start();
    }

    private synchronized void stopEngine(){
        stopping=true;
        stopEngineInternal(true);
    }

    private void stopEngineInternal(boolean publish){
        RanovaOpenVpnThread e=engine;
        engine=null;

        if(e!=null){
            try{ e.stopVPN(); }catch(Throwable ignored){}
        }

        Thread t=engineThread;
        engineThread=null;
        if(t!=null){
            try{ t.interrupt(); }catch(Throwable ignored){}
        }

        if(publish) RanovaVpnBus.state("DISCONNECTED");
        stopForeground(true);
        if(publish) stopSelf();
    }

    private void resetTunState(){
        mtu=1500;
        localIp=null;
        localIpv6=null;
        domain=null;
        dns.clear();
        includeV4.clear();
        includeV6.clear();
        excludeV4.clear();
        excludeV6.clear();
    }

    @Override
    public void setMtu(int value){
        if(value>=576&&value<=10000) mtu=value;
    }

    @Override
    public void addDNS(String value){
        if(value!=null&&!value.trim().isEmpty()&&!dns.contains(value.trim())){
            dns.add(value.trim());
        }
    }

    @Override
    public void addRoute(CIDRIP route,boolean include){
        if(route==null||route.getIp()==null) return;
        String cidr=route.getIp()+"/"+route.getLen();
        if(include) includeV4.add(cidr);
        else excludeV4.add(cidr);
    }

    @Override
    public void addRoute(String dest,String mask,String gateway,String device){
        if(dest==null||mask==null) return;
        int prefix=maskToPrefix(mask);
        String cidr=dest+"/"+prefix;
        if(isTunDevice(device)) includeV4.add(cidr);
        else excludeV4.add(cidr);
    }

    @Override
    public void addRoutev6(String network,String device){
        if(network==null||network.trim().isEmpty()) return;
        if(isTunDevice(device)) includeV6.add(network.trim());
        else excludeV6.add(network.trim());
    }

    @Override
    public void setDomain(String value){
        domain=value;
    }

    @Override
    public boolean addHttpProxy(String host,int port){
        return false;
    }

    @Override
    public ParcelFileDescriptor openTun(){
        try{
            Builder b=new Builder()
                    .setSession("RANOVA VPN PRO")
                    .setMtu(mtu);

            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
                b.setMetered(false);
            }

            if(localIp==null){
                RanovaVpnBus.error("VPN server did not assign an IPv4 address");
                return null;
            }

            b.addAddress(localIp.getIp(),localIp.getLen());

            if(localIpv6!=null&&localIpv6.contains("/")){
                String[] p=localIpv6.split("/",2);
                b.addAddress(p[0],Integer.parseInt(p[1]));
            }

            if(dns.isEmpty()){
                b.addDnsServer("1.1.1.1");
                b.addDnsServer("8.8.8.8");
            }else{
                for(String d:dns){
                    try{ b.addDnsServer(d); }catch(Exception ignored){}
                }
            }

            if(domain!=null&&!domain.trim().isEmpty()){
                try{ b.addSearchDomain(domain.trim()); }catch(Exception ignored){}
            }

            if(includeV4.isEmpty()){
                includeV4.add("0.0.0.0/0");
            }

            for(String r:includeV4) addRouteToBuilder(b,r);
            for(String r:includeV6) addRouteToBuilder(b,r);

            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
                for(String r:excludeV4) excludeRoute(b,r);
                for(String r:excludeV6) excludeRoute(b,r);
            }

            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP_MR1){
                b.setUnderlyingNetworks(null);
            }

            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
                b.setBlocking(true);
            }

            ParcelFileDescriptor tun=b.establish();
            if(tun==null) RanovaVpnBus.error("Android could not establish the VPN tunnel");
            return tun;

        }catch(Throwable t){
            RanovaVpnBus.error("Tunnel setup failed: "+t.getClass().getSimpleName());
            return null;
        }
    }

    private static void addRouteToBuilder(Builder b,String cidr){
        try{
            String[] p=cidr.split("/",2);
            b.addRoute(p[0],Integer.parseInt(p[1]));
        }catch(Exception ignored){}
    }

    private static void excludeRoute(Builder b,String cidr){
        try{
            String[] p=cidr.split("/",2);
            InetAddress address=InetAddress.getByName(p[0]);
            b.excludeRoute(new IpPrefix(address,Integer.parseInt(p[1])));
        }catch(Exception ignored){}
    }

    @Override
    public void setLocalIP(CIDRIP ip){
        localIp=ip;
    }

    @Override
    public void setLocalIPv6(String ip){
        localIpv6=ip;
    }

    @Override
    public boolean protectFd(int fd){
        return protect(fd);
    }

    @Override
    public void trigger_sso(String info){
        RanovaVpnBus.error("This relay requires unsupported interactive authentication");
    }

    @Override
    public ContentResolver getCtResolver(){
        return getContentResolver();
    }

    @Override
    public ConnectivityManager getConnectivityManager(){
        return (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public void openvpnStopped(){
        if(!stopping){
            RanovaVpnBus.state("DISCONNECTED");
        }
        engine=null;
        engineThread=null;
        stopForeground(true);
        if(stopping) stopSelf();
    }

    @Override
    public void updateStateThread(ConnectionState state){
        if(state==null) return;

        String value=state.name();
        RanovaVpnBus.state(value);

        if(ConnectionState.CONNECTED.equals(state)){
            updateNotification("Connected securely");
        }else if(ConnectionState.CONNECTING.equals(state)){
            updateNotification("Connecting securely…");
        }else if(ConnectionState.DISCONNECTED.equals(state)){
            updateNotification("Disconnected");
        }
    }

    @Override
    public IBinder onBind(Intent intent){
        return super.onBind(intent);
    }

    @Override
    public void onRevoke(){
        stopEngine();
        super.onRevoke();
    }

    @Override
    public void onDestroy(){
        stopping=true;
        stopEngineInternal(false);
        super.onDestroy();
    }

    private boolean isTunDevice(String device){
        return device==null
                ||device.startsWith("tun")
                ||"(null)".equals(device)
                ||"vpnservice-tun".equals(device);
    }

    private static int maskToPrefix(String mask){
        if(mask==null) return 32;
        if(mask.indexOf('.')<0){
            try{return Integer.parseInt(mask);}catch(Exception e){return 32;}
        }

        String[] parts=mask.split("\\.");
        if(parts.length!=4) return 32;

        int count=0;
        for(String part:parts){
            int value;
            try{value=Integer.parseInt(part);}catch(Exception e){return 32;}
            count+=Integer.bitCount(value&0xff);
        }
        return count;
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            NotificationChannel channel=new NotificationChannel(
                    CHANNEL_ID,
                    "RANOVA VPN Connection",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("RANOVA VPN secure tunnel status");
            channel.setSound(null,null);

            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification notification(String text){
        Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi=PendingIntent.getActivity(
                this,0,launch,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O
                ?new Notification.Builder(this,CHANNEL_ID)
                :new Notification.Builder(this);

        return b.setSmallIcon(com.ranova.vpnpro.beta.R.drawable.ic_ranova_status)
                .setContentTitle("RANOVA VPN PRO")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID,notification(text));
    }
}
