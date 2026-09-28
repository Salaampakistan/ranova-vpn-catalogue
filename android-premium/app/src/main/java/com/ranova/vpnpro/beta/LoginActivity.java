package com.ranova.vpnpro.beta;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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

        boolean migrated=OwnerStore.ensureCurrentDefault(this);
        if(migrated) getSharedPreferences("session",MODE_PRIVATE).edit().clear().apply();

        if(getIntent().getBooleanExtra("change_owner",false)) {
            buildOwnerChange();
            return;
        }

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
                "Owner-controlled RANOVA access",
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
        user.setText(OwnerStore.username(this));
        card.addView(user);

        TextView pl=Ui.text(this,"PASSWORD",11,Ui.CYAN,true);
        pl.setLetterSpacing(.08f);
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

        msg=Ui.text(this,"",12,Ui.RED,false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0,Ui.dp(this,10),0,0);
        card.addView(msg);

        login.setOnClickListener(v->authenticate());
        setContentView(scroll);
    }

    private void buildOwnerChange() {
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this,24),Ui.dp(this,34),Ui.dp(this,24),Ui.dp(this,24));
        root.setBackground(Ui.gradient(this,Ui.BG,0xff101a39,0));
        scroll.addView(root);

        LogoView logo=new LogoView(this);
        logo.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this,104),Ui.dp(this,104)));
        root.addView(logo);

        TextView title=Ui.text(this,"OWNER ACCOUNT",26,Ui.TEXT,true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView note=Ui.text(this,"Change the RANOVA owner username and password",13,Ui.MUTED,false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,20));
        root.addView(note);

        LinearLayout card=Ui.card(this);
        root.addView(card);

        EditText u=field("Owner username",false);
        u.setText(OwnerStore.username(this));
        card.addView(u);

        EditText p1=field("New password",true);
        LinearLayout.LayoutParams p1lp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p1lp.topMargin=Ui.dp(this,10);
        p1.setLayoutParams(p1lp);
        card.addView(p1);

        EditText p2=field("Confirm new password",true);
        LinearLayout.LayoutParams p2lp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p2lp.topMargin=Ui.dp(this,10);
        p2.setLayoutParams(p2lp);
        card.addView(p2);

        TextView status=Ui.text(this,"",12,Ui.RED,false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0,Ui.dp(this,10),0,0);
        card.addView(status);

        Button save=new Button(this);
        save.setText("Save Owner Login");
        save.setAllCaps(false);
        save.setTextColor(0xff06111f);
        save.setBackground(Ui.gradient(this,Ui.CYAN,0xff6598ff,18));
        Ui.topMargin(save,this,18);
        card.addView(save);

        save.setOnClickListener(v->{
            String nu=u.getText().toString().trim();
            char[] a=p1.getText().toString().toCharArray();
            char[] b=p2.getText().toString().toCharArray();

            try {
                if(nu.length()<4||a.length<8||!java.util.Arrays.equals(a,b)) {
                    status.setText("Username 4+ chars, password 8+ chars, and both passwords must match.");
                    return;
                }

                OwnerStore.update(this,nu,a);
                getSharedPreferences("session",MODE_PRIVATE).edit()
                        .putString("username",nu)
                        .putString("display_name","Mr. Rana")
                        .putString("role","owner")
                        .putLong("expiry",System.currentTimeMillis()+12L*60L*60L*1000L)
                        .apply();

                java.util.Arrays.fill(a,'\0');
                java.util.Arrays.fill(b,'\0');

                Intent i=new Intent(this,MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();

            } catch(Exception e) {
                status.setText("Could not update owner login.");
            }
        });

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
            PasswordToggle.attach(e);
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
