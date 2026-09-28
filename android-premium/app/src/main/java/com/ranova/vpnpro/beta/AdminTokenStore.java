package com.ranova.vpnpro.beta;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AdminTokenStore {
    private static final String PREF="admin_auth";
    private static final String OLD_KEY="github_token";
    private static final String ENC_KEY="github_token_enc";
    private static final String IV_KEY="github_token_iv";
    private static final String KEY_ALIAS="RANOVA_ADMIN_TOKEN_KEY";

    private AdminTokenStore() {}

    static void save(Context c,String token){
        if(token==null||token.trim().isEmpty()){
            clear(c);
            return;
        }

        try{
            SecretKey key=getOrCreateKey();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key);

            byte[] encrypted=cipher.doFinal(
                    token.trim().getBytes(StandardCharsets.UTF_8));

            c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                    .edit()
                    .putString(ENC_KEY,Base64.encodeToString(encrypted,Base64.NO_WRAP))
                    .putString(IV_KEY,Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                    .remove(OLD_KEY)
                    .apply();

        }catch(Exception e){
            throw new IllegalStateException("Secure token storage unavailable",e);
        }
    }

    static String get(Context c){
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);

        String enc=p.getString(ENC_KEY,"");
        String iv=p.getString(IV_KEY,"");

        if(!enc.isEmpty()&&!iv.isEmpty()){
            try{
                SecretKey key=getOrCreateKey();
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        key,
                        new GCMParameterSpec(128,Base64.decode(iv,Base64.DEFAULT)));

                byte[] clear=cipher.doFinal(Base64.decode(enc,Base64.DEFAULT));
                return new String(clear,StandardCharsets.UTF_8);

            }catch(Exception e){
                clear(c);
                return "";
            }
        }

        // One-time migration from v0.x plaintext SharedPreferences.
        String legacy=p.getString(OLD_KEY,"");
        if(!legacy.isEmpty()){
            save(c,legacy);
            return legacy;
        }

        return "";
    }

    static boolean has(Context c){
        return !get(c).isEmpty();
    }

    static void clear(Context c){
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .edit()
                .remove(OLD_KEY)
                .remove(ENC_KEY)
                .remove(IV_KEY)
                .apply();
    }

    private static SecretKey getOrCreateKey() throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);

        if(store.containsAlias(KEY_ALIAS)){
            return (SecretKey)store.getKey(KEY_ALIAS,null);
        }

        KeyGenerator generator=KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");

        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());

        return generator.generateKey();
    }
}
