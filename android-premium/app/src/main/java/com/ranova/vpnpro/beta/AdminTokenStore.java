package com.ranova.vpnpro.beta;

import android.content.Context;
import android.content.SharedPreferences;

final class AdminTokenStore {
    private static final String PREF="admin_auth";
    private static final String KEY="github_token";

    private AdminTokenStore() {}

    static void save(Context c,String token){
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .edit().putString(KEY,token.trim()).apply();
    }

    static String get(Context c){
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .getString(KEY,"");
    }

    static boolean has(Context c){
        return !get(c).isEmpty();
    }

    static void clear(Context c){
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .edit().remove(KEY).apply();
    }
}
