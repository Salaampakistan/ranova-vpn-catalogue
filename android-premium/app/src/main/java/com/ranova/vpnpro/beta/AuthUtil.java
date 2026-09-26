package com.ranova.vpnpro.beta;

import android.util.Base64;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class AuthUtil {
    static final int ITERATIONS = 310000;
    private AuthUtil() {}

    static byte[] newSalt() {
        byte[] s=new byte[16];
        new SecureRandom().nextBytes(s);
        return s;
    }

    static byte[] hash(char[] password, byte[] salt, int iterations, int bytes) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,iterations,bytes*8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static boolean verify(char[] password, String saltB64, String hashB64, int iterations) throws Exception {
        byte[] salt=Base64.decode(saltB64,Base64.DEFAULT);
        byte[] expected=Base64.decode(hashB64,Base64.DEFAULT);
        byte[] actual=hash(password,salt,iterations,expected.length);
        return MessageDigest.isEqual(actual,expected);
    }

    static JSONObject userObject(String username, String displayName, char[] password) throws Exception {
        byte[] salt=newSalt();
        byte[] digest=hash(password,salt,ITERATIONS,32);
        JSONObject o=new JSONObject();
        o.put("username",username);
        o.put("display_name",displayName);
        o.put("role","user");
        o.put("enabled",true);
        o.put("iterations",ITERATIONS);
        o.put("salt_b64",Base64.encodeToString(salt,Base64.NO_WRAP));
        o.put("password_hash_b64",Base64.encodeToString(digest,Base64.NO_WRAP));
        return o;
    }
}
