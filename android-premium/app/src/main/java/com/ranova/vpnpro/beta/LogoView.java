package com.ranova.vpnpro.beta;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public class LogoView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LogoView(Context c) {
        super(c);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(), h=getHeight(), cx=w/2f, cy=h/2f;
        float r=Math.min(w,h)*0.35f;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r*0.08f);
        p.setColor(Ui.CYAN);
        p.setShadowLayer(r*0.18f,0,0,Ui.BLUE);
        c.drawCircle(cx,cy,r,p);

        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(12,20,44));
        c.drawCircle(cx,cy,r*0.84f,p);

        Path sh=new Path();
        sh.moveTo(cx,cy-r*0.62f);
        sh.lineTo(cx+r*0.46f,cy-r*0.36f);
        sh.lineTo(cx+r*0.36f,cy+r*0.24f);
        sh.quadTo(cx,cy+r*0.68f,cx-r*0.36f,cy+r*0.24f);
        sh.lineTo(cx-r*0.46f,cy-r*0.36f);
        sh.close();
        p.setColor(Ui.BLUE);
        c.drawPath(sh,p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(r*0.13f);
        p.setColor(Color.WHITE);
        Path rr=new Path();
        rr.moveTo(cx-r*0.17f,cy+r*0.28f);
        rr.lineTo(cx-r*0.17f,cy-r*0.30f);
        rr.lineTo(cx+r*0.08f,cy-r*0.30f);
        rr.quadTo(cx+r*0.30f,cy-r*0.22f,cx+r*0.09f,cy-r*0.02f);
        rr.lineTo(cx-r*0.17f,cy-r*0.02f);
        rr.moveTo(cx+r*0.01f,cy-r*0.02f);
        rr.lineTo(cx+r*0.27f,cy+r*0.30f);
        c.drawPath(rr,p);
    }
}
