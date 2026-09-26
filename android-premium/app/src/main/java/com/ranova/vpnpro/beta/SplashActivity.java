package com.ranova.vpnpro.beta;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(Ui.dp(this,24),0,Ui.dp(this,24),0);
        root.setBackground(Ui.gradient(this,Ui.BG,0xff0c1835,0));

        LogoView logo=new LogoView(this);
        logo.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this,190),Ui.dp(this,190)));
        logo.setScaleX(.55f);
        logo.setScaleY(.55f);
        logo.setAlpha(0f);
        root.addView(logo);

        TextView title=Ui.text(this,"RANOVA VPN PRO",30,Ui.TEXT,true);
        title.setGravity(Gravity.CENTER);
        title.setAlpha(0f);
        root.addView(title);

        TextView tag=Ui.text(this,"PRIVATE  •  FAST  •  GLOBAL",13,Ui.CYAN,true);
        tag.setGravity(Gravity.CENTER);
        tag.setLetterSpacing(.10f);
        tag.setPadding(0,Ui.dp(this,10),0,0);
        tag.setAlpha(0f);
        root.addView(tag);

        TextView intro=Ui.text(this,"Created by Muhammad Ali Adeel",14,Ui.MUTED,false);
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(0,Ui.dp(this,34),0,0);
        intro.setAlpha(0f);
        root.addView(intro);

        setContentView(root);

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        title.animate().alpha(1f).setStartDelay(450).setDuration(650).start();
        tag.animate().alpha(1f).setStartDelay(800).setDuration(600).start();
        intro.animate().alpha(1f).setStartDelay(1150).setDuration(650).start();

        root.postDelayed(() -> {
            startActivity(new Intent(this,LoginActivity.class));
            overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);
            finish();
        },2600);
    }
}
