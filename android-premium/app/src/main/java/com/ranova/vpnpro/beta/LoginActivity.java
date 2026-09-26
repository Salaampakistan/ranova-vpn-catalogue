package com.ranova.vpnpro.beta;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

public class LoginActivity extends Activity {
    private EditText user, pass;
    private TextView msg, sub;
    private Button login;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        SharedPreferences sp=getSharedPreferences("session",MODE_PRIVATE);
        if(sp.getLong("expiry",0)>System.currentTimeMillis()) {
            dashboard();
            return;
        }

        buildUi();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this,24),Ui.dp(this,34),Ui.dp(this,24),Ui.dp(this,24));
        root.setBackground(Ui.gradient(this,Ui.BG,0xff101a39,0));
        scroll.addView(root);

        LogoView logo=new LogoView(this);
        logo.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this,112),Ui.dp(this,112)));
        root.addView(logo);

        TextView title=Ui.text(this,"SECURE ACCESS",27,Ui.TEXT,true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        sub=Ui.text(this,
                OwnerStore.initialized(this)
                        ?"Owner-controlled RANOVA access"
                        :"First launch: activate owner account",
                14,Ui.MUTED,false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,24));
        root.addView(sub);

        LinearLayout card=Ui.card(this);
        root.addView(card);

        TextView ul=Ui.text(this,"RANOVA ID",11,Ui.CYAN,true);
        ul.setLetterSpacing(.08f);
        card.addView(ul);

        user=field("Username",false);
        if(!OwnerStore.initialized(this)) user.setText("Salampakistan");
        card.addView(user);

        TextView pl=Ui.text(this,"PASSWORD",11,Ui.CYAN,true);
        pl.setLetterSpacing(.08f);
        pl.setPadding(0,Ui.dp(this,16),0,0);
        card.addView(pl);

        pass=field("Password",true);
        card.addView(pass);

        login=new Button(this);
        login.setText(OwnerStore.initialized(this)?"Unlock RANOVA":"Activate Owner");
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

        msg=Ui.text(this,
                OwnerStore.initialized(this)
                        ?""
                        :"Set your owner password once. It is stored as a local PBKDF2 hash, not plaintext.",
                12,OwnerStore.initialized(this)?Ui.RED:Ui.MUTED,false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0,Ui.dp(this,10),0,0);
        card.addView(msg);

        login.setOnClickListener(v->authenticate());
        setContentView(scroll);
    }

    private EditText field(String hint,boolean password) {
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xff7786a5);
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setPadding(Ui.dp(this,13),Ui.dp(this,10),Ui.dp(this,13),Ui.dp(this,10));
        e.setBackground(Ui.fieldBg(this));
        if(password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return e;
    }

    private void authenticate() {
        final String u=user.getText().toString().trim();
        final char[] pw=pass.getText().toString().toCharArray();

        if(u.isEmpty()||pw.length<6) {
            msg.setText("Enter username and a password of at least 6 characters.");
            msg.setTextColor(Ui.RED);
            return;
        }

        login.setEnabled(false);
        progress.setVisibility(ProgressBar.VISIBLE);
        msg.setText("Checking access...");
        msg.setTextColor(Ui.MUTED);

        new Thread(()->{
            try {
                if(!OwnerStore.initialized(this)) {
                    if(!"Salampakistan".equalsIgnoreCase(u)) throw new SecurityException();
                    OwnerStore.initialize(this,u,pw);
                    saveSession(u,"Mr. Rana","owner");
                    runOnUiThread(this::dashboard);
                    return;
                }

                if(OwnerStore.verify(this,u,pw)) {
                    saveSession(OwnerStore.username(this),"Mr. Rana","owner");
                    runOnUiThread(this::dashboard);
                    return;
                }

                JSONObject root=RemoteAuth.fetch();
                JSONArray users=root.optJSONArray("users");
                JSONObject match=null;

                if(users!=null) {
                    for(int i=0;i<users.length();i++) {
                        JSONObject x=users.getJSONObject(i);
                        if(u.equalsIgnoreCase(x.optString("username"))) {
                            match=x;
                            break;
                        }
                    }
                }

                if(match==null||!match.optBoolean("enabled",false))
                    throw new SecurityException();

                int iterations=match.optInt("iterations",AuthUtil.ITERATIONS);
                if(!AuthUtil.verify(
                        pw,
                        match.getString("salt_b64"),
                        match.getString("password_hash_b64"),
                        iterations))
                    throw new SecurityException();

                saveSession(
                        match.getString("username"),
                        match.optString("display_name",match.getString("username")),
                        match.optString("role","user"));

                runOnUiThread(this::dashboard);

            } catch(SecurityException e) {
                runOnUiThread(()->fail("Invalid or disabled RANOVA access."));
            } catch(Exception e) {
                runOnUiThread(()->fail("Login service unavailable: "+e.getClass().getSimpleName()));
            } finally {
                Arrays.fill(pw,'\0');
            }
        }).start();
    }

    private void saveSession(String username,String display,String role) {
        getSharedPreferences("session",MODE_PRIVATE).edit()
                .putString("username",username)
                .putString("display_name",display)
                .putString("role",role)
                .putLong("expiry",System.currentTimeMillis()+12L*60L*60L*1000L)
                .apply();
    }

    private void fail(String s) {
        msg.setText(s);
        msg.setTextColor(Ui.RED);
        login.setEnabled(true);
        progress.setVisibility(ProgressBar.GONE);
    }

    private void dashboard() {
        startActivity(new Intent(this,MainActivity.class));
        finish();
    }
}
