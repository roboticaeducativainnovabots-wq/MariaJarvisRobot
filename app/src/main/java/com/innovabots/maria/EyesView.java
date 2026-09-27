package com.innovabots.maria;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class EyesView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private long nextBlinkAt = System.currentTimeMillis() + 2200;
    private long blinkStarted = -1;

    public EyesView(Context context) { super(context); init(); }
    public EyesView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setBackgroundColor(Color.rgb(5, 8, 13));
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (blinkStarted < 0 && now >= nextBlinkAt) blinkStarted = now;
            if (blinkStarted >= 0 && now - blinkStarted > 180) {
                blinkStarted = -1;
                nextBlinkAt = now + 1800 + random.nextInt(2800);
            }
            invalidate();
            handler.postDelayed(this, 33);
        }
    };

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float eyeW = w * 0.31f;
        float eyeH = h * 0.42f;
        float gap = w * 0.09f;
        float cy = h * 0.52f;
        float leftCx = w / 2f - eyeW / 2f - gap / 2f;
        float rightCx = w / 2f + eyeW / 2f + gap / 2f;

        long t = System.currentTimeMillis();
        float lookX = (float)Math.sin(t / 1150.0) * eyeW * 0.11f;
        float lookY = (float)Math.sin(t / 1700.0 + 1.2) * eyeH * 0.08f;

        float blink = 1f;
        if (blinkStarted >= 0) {
            float p = Math.min(1f, (t - blinkStarted) / 180f);
            blink = p < 0.5f ? 1f - p * 1.9f : (p - 0.5f) * 1.9f;
            blink = Math.max(0.06f, blink);
        }

        drawEye(canvas, leftCx, cy, eyeW, eyeH * blink, lookX, lookY * blink);
        drawEye(canvas, rightCx, cy, eyeW, eyeH * blink, lookX, lookY * blink);
    }

    private void drawEye(Canvas c, float cx, float cy, float ew, float eh, float px, float py) {
        RectF glow = new RectF(cx-ew/2-7, cy-eh/2-7, cx+ew/2+7, cy+eh/2+7);
        paint.setColor(Color.rgb(0, 145, 190));
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(glow, eh/2, eh/2, paint);

        RectF eye = new RectF(cx-ew/2, cy-eh/2, cx+ew/2, cy+eh/2);
        paint.setColor(Color.rgb(220, 252, 255));
        c.drawRoundRect(eye, eh/2, eh/2, paint);

        float pupil = Math.max(7f, Math.min(ew, eh) * 0.19f);
        paint.setColor(Color.rgb(8, 35, 45));
        c.drawCircle(cx + px, cy + py, pupil, paint);
        paint.setColor(Color.rgb(40, 220, 245));
        c.drawCircle(cx + px - pupil*0.25f, cy + py - pupil*0.25f, pupil*0.25f, paint);
    }
}
