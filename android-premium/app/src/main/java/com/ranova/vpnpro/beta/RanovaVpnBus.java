package com.ranova.vpnpro.beta;

final class RanovaVpnBus {
    interface Listener {
        void onState(String state);
        void onError(String message);
    }

    private static volatile Listener listener;
    private static volatile String lastState="DISCONNECTED";
    private static volatile String lastError="";

    private RanovaVpnBus(){}

    static void setListener(Listener l){
        listener=l;
        if(l!=null){
            l.onState(lastState);
            if(!lastError.isEmpty()) l.onError(lastError);
        }
    }

    static void clearListener(Listener l){
        if(listener==l) listener=null;
    }

    static void state(String s){
        lastState=s==null?"DISCONNECTED":s;
        Listener l=listener;
        if(l!=null) l.onState(lastState);
    }

    static void error(String s){
        lastError=s==null?"":s;
        Listener l=listener;
        if(l!=null && !lastError.isEmpty()) l.onError(lastError);
    }

    static String state(){ return lastState; }

    static void clearError(){ lastError=""; }
}
