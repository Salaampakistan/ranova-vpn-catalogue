package com.ranova.vpnpro.beta;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class LoginActivity extends Activity {
    private static final String EXPECTED_USER = "rana-owner";
    private static final int ITERATIONS = 180000;
    private static final String SALT_B64 = "USX8uLQ0aHGlqzlXGPrBMA==";
    private static final String HASH_B64 = "k7PkgkUz5tXV3fExh1tAP4tAFxrXcCfAnOL8OaWkExo=";

    private EditText user, pass;
    private TextView msg;
    private Button login;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences sp=getSharedPreferences("session",MODE_PRIVATE);
        if (sp.getLong("expiry",0)>System.currentTimeMillis()) {
            dashboard();
            return;
        }

        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this,24),Ui.dp(this,36),Ui.dp(this,24),Ui.dp(this,24));
        root.setBackground(Ui.gradient(this,Ui.BG,0xff101a39,0));
        scroll.addView(root);

        LogoView logo=new LogoView(this);
        logo.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this,112),Ui.dp(this,112)));
        root.addView(logo);

        TextView title=Ui.text(this,"SECURE ACCESS",27,Ui.TEXT,true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub=Ui.text(this,"Owner-controlled RANOVA account",14,Ui.MUTED,false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,24));
        root.addView(sub);

        LinearLayout card=Ui.card(this);
        root.addView(card);

        card.addView(label("RANOVA ID"));
        user=field("Username",false);
        card.addView(user);

        TextView pl=label("PASSWORD");
        pl.setPadding(0,Ui.dp(this,16),0,0);
        card.addView(pl);
        pass=field("Password",true);
        card.addView(pass);

        login=new Button(this);
        login.setText("Unlock RANOVA");
        login.setAllCaps(false);
        login.setTextColor(0xff06111f);
        login.setTextSize(15);
        login.setBackground(Ui.gradient(this,Ui.CYAN,0xff6598ff,18));
        Ui.topMargin(login,this,20);
        card.addView(login);

        progress=new ProgressBar(this);
        progress.setVisibility(ProgressBar.GONE);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(Ui.dp(this,34),Ui.dp(this,34));
        pp.gravity=Gravity.CENTER_HORIZONTAL;
        pp.topMargin=Ui.dp(this,12);
        progress.setLayoutParams(pp);
        card.addView(progress);

        msg=Ui.text(this,"",13,Ui.RED,false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0,Ui.dp(this,10),0,0);
        card.addView(msg);

        TextView note=Ui.text(this,"Beta owner access. Production user accounts will use revocable server-side access.",12,Ui.MUTED,false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0,Ui.dp(this,20),0,0);
        root.addView(note);

        login.setOnClickListener(v->authenticate());
        setContentView(scroll);
    }

    private TextView label(String s) {
        TextView t=Ui.text(this,s,11,Ui.CYAN,true);
        t.setLetterSpacing(.08f);
        return t;
    }

    private EditText field(String hint, boolean password) {
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xff7786a5);
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setPadding(Ui.dp(this,13),Ui.dp(this,10),Ui.dp(this,13),Ui.dp(this,10));
        e.setBackground(Ui.fieldBg(this));
        if(password) e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void authenticate() {
        final String u=user.getText().toString().trim();
        final char[] pw=pass.getText().toString().toCharArray();

        if(u.isEmpty()||pw.length==0){
            msg.setText("Enter username and password.");
            return;
        }

        login.setEnabled(false);
        progress.setVisibility(ProgressBar.VISIBLE);
        msg.setText("Checking access...");

        new Thread(() -> {
            try {
                if(!EXPECTED_USER.equalsIgnoreCase(u)) throw new SecurityException();

                byte[] salt=Base64.decode(SALT_B64,Base64.DEFAULT);
                byte[] expected=Base64.decode(HASH_B64,Base64.DEFAULT);
                PBEKeySpec spec=new PBEKeySpec(pw,salt,ITERATIONS,expected.length*8);
                byte[] actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
                spec.clearPassword();

                if(!MessageDigest.isEqual(actual,expected)) throw new SecurityException();

                getSharedPreferences("session",MODE_PRIVATE).edit()
                        .putString("username",u)
                        .putString("display_name","Mr. Rana")
                        .putString("role","owner")
                        .putLong("expiry",System.currentTimeMillis()+12L*60L*60L*1000L)
                        .apply();

                runOnUiThread(this::dashboard);
            } catch(SecurityException e) {
                runOnUiThread(()->fail("Invalid RANOVA access."));
            } catch(Exception e) {
                runOnUiThread(()->fail("Login error: "+e.getClass().getSimpleName()));
            } finally {
                Arrays.fill(pw,'\0');
            }
        }).start();
    }

    private void fail(String s){
        msg.setText(s);
        login.setEnabled(true);
        progress.setVisibility(ProgressBar.GONE);
    }

    private void dashboard(){
        startActivity(new Intent(this,MainActivity.class));
        finish();
    }
}
