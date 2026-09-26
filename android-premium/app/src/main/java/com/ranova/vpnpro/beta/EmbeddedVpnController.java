package com.ranova.vpnpro.beta;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class EmbeddedVpnController {
    interface Listener {
        void onState(String state);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;

    private final RanovaVpnBus.Listener busListener=new RanovaVpnBus.Listener(){
        @Override public void onState(String state){
            if(listener!=null) listener.onState(state);
        }

        @Override public void onError(String message){
            if(listener!=null) listener.onError(message);
        }
    };

    EmbeddedVpnController(Context context,Listener listener){
        this.context=context.getApplicationContext();
        this.listener=listener;
    }

    void bind(){
        RanovaVpnBus.setListener(busListener);
    }

    void start(String config){
        RanovaVpnBus.clearError();

        Intent i=new Intent(context,RanovaVpnService.class);
        i.setAction(RanovaVpnService.ACTION_START);
        i.putExtra(RanovaVpnService.EXTRA_CONFIG,config);

        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            context.startForegroundService(i);
        }else{
            context.startService(i);
        }
    }

    void stop(){
        Intent i=new Intent(context,RanovaVpnService.class);
        i.setAction(RanovaVpnService.ACTION_STOP);
        try{
            context.startService(i);
        }catch(Exception e){
            context.stopService(new Intent(context,RanovaVpnService.class));
            RanovaVpnBus.state("DISCONNECTED");
        }
    }

    void release(){
        RanovaVpnBus.clearListener(busListener);
    }
}
