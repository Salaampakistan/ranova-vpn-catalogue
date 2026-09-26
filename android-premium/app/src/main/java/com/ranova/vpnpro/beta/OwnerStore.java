package com.ranova.vpnpro.beta;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.MessageDigest;

final class OwnerStore {
    private static final String PREF="owner_auth";
    private static final String DEFAULT_USER="Salampakistan";

    private OwnerStore() {}

    static boolean initialized(Context c) {
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).contains("hash");
    }

    static String username(Context c) {
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .getString("username",DEFAULT_USER);
    }

    static void initialize(Context c,String username,char[] password) throws Exception {
        update(c,username,password);
    }

    static boolean verify(Context c,String username,char[] password) {
        if(!initialized(c)) return false;
        if(!username(c).equalsIgnoreCase(username)) return false;

        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String saltB64=p.getString("salt","");
        String hashB64=p.getString("hash","");
        int iter=p.getInt("iterations",AuthUtil.ITERATIONS);

        try {
            byte[] salt=Base64.decode(saltB64,Base64.DEFAULT);
            byte[] expected=Base64.decode(hashB64,Base64.DEFAULT);
            byte[] actual=AuthUtil.hash(password,salt,iter,expected.length);
            return MessageDigest.isEqual(actual,expected);
        } catch(Exception e) {
            return false;
        }
    }

    static void update(Context c,String newUsername,char[] newPassword) throws Exception {
        byte[] salt=AuthUtil.newSalt();
        byte[] digest=AuthUtil.hash(newPassword,salt,AuthUtil.ITERATIONS,32);

        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                .putString("username",newUsername)
                .putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP))
                .putString("hash",Base64.encodeToString(digest,Base64.NO_WRAP))
                .putInt("iterations",AuthUtil.ITERATIONS)
                .apply();
    }
}
