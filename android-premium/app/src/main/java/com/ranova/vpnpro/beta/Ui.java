package com.ranova.vpnpro.beta;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(5, 9, 22);
    static final int PANEL = Color.rgb(14, 22, 44);
    static final int PANEL2 = Color.rgb(19, 30, 56);
    static final int TEXT = Color.rgb(244, 248, 255);
    static final int MUTED = Color.rgb(143, 160, 189);
    static final int CYAN = Color.rgb(68, 226, 255);
    static final int BLUE = Color.rgb(77, 111, 255);
    static final int GREEN = Color.rgb(68, 230, 161);
    static final int RED = Color.rgb(255, 101, 117);

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable gradient(Context c, int a, int b, int radius) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    static GradientDrawable cardBg(Context c) {
        GradientDrawable g = gradient(c, PANEL, PANEL2, 20);
        g.setStroke(dp(c,1), Color.rgb(35, 51, 84));
        return g;
    }

    static GradientDrawable fieldBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(9, 16, 34));
        g.setCornerRadius(dp(c,14));
        g.setStroke(dp(c,1), Color.rgb(38, 57, 95));
        return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14));
        l.setBackground(cardBg(c));
        return l;
    }

    static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    static TextView pill(Context c, String s) {
        TextView t = text(c,s,11,CYAN,true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c,11),dp(c,7),dp(c,11),dp(c,7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(8, 27, 47));
        g.setCornerRadius(dp(c,18));
        g.setStroke(dp(c,1), Color.rgb(42, 139, 173));
        t.setBackground(g);
        return t;
    }

    static void topMargin(View v, Context c, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, margin);
        v.setLayoutParams(p);
    }
}
