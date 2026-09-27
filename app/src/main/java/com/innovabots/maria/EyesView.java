package com.innovabots.maria;

import android.content.Context;
import android.graphics.BlurMaskFilter;
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
    public static final int MODE_IDLE = 0;
    public static final int MODE_LISTENING = 1;
    public static final int MODE_THINKING = 2;
    public static final int MODE_SPEAKING = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private int mode = MODE_IDLE;
    private boolean connected = false;
    private float voiceLevel = 0f;

    private long nextBlinkAt = System.currentTimeMillis() + 1800;
    private long blinkStarted = -1;
    private int blinkCountRemaining = 0;

    private float targetLookX = 0f;
    private float targetLookY = 0f;
    private float lookX = 0f;
    private float lookY = 0f;
    private long nextLookAt = System.currentTimeMillis() + 900;

    public EyesView(Context context) {
        super(context);
        init();
    }

    public EyesView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackgroundColor(Color.rgb(2, 5, 9));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        glowPaint.setMaskFilter(new BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL));
        handler.post(tick);
    }

    public void setMode(int newMode) {
        mode = newMode;
        invalidate();
    }

    public void setConnected(boolean value) {
        connected = value;
        invalidate();
    }

    public void setVoiceLevel(float rms) {
        voiceLevel = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();

            updateBlink(now);
            updateLook(now);

            invalidate();
            handler.postDelayed(this, 30);
        }
    };

    private void updateBlink(long now) {
        if (blinkStarted < 0 && now >= nextBlinkAt) {
            blinkStarted = now;
            blinkCountRemaining = mode == MODE_THINKING ? 1 : (random.nextInt(5) == 0 ? 2 : 1);
        }

        if (blinkStarted >= 0 && now - blinkStarted > 170) {
            blinkCountRemaining--;

            if (blinkCountRemaining > 0) {
                blinkStarted = now + 80;
            } else {
                blinkStarted = -1;

                int base;
                if (mode == MODE_LISTENING) base = 2300;
                else if (mode == MODE_THINKING) base = 1300;
                else if (mode == MODE_SPEAKING) base = 1600;
                else base = 1800;

                nextBlinkAt = now + base + random.nextInt(2200);
            }
        }
    }

    private void updateLook(long now) {
        if (mode == MODE_THINKING) {
            targetLookX = (float)Math.sin(now / 280.0) * 0.65f;
            targetLookY = (float)Math.cos(now / 430.0) * 0.35f;
        } else if (mode == MODE_SPEAKING) {
            targetLookX = (float)Math.sin(now / 650.0) * 0.25f;
            targetLookY = (float)Math.sin(now / 900.0) * 0.15f;
        } else if (now >= nextLookAt) {
            targetLookX = -0.65f + random.nextFloat() * 1.3f;
            targetLookY = -0.35f + random.nextFloat() * 0.7f;
            nextLookAt = now + 900 + random.nextInt(1700);
        }

        lookX += (targetLookX - lookX) * 0.09f;
        lookY += (targetLookY - lookY) * 0.09f;
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();

        float eyeW = Math.min(w * 0.34f, h * 0.92f);
        float baseEyeH = Math.min(h * 0.48f, eyeW * 0.62f);
        float gap = Math.max(w * 0.08f, eyeW * 0.20f);
        float cy = h * 0.50f;

        float leftCx = w / 2f - eyeW / 2f - gap / 2f;
        float rightCx = w / 2f + eyeW / 2f + gap / 2f;

        long now = System.currentTimeMillis();

        float blink = 1f;
        if (blinkStarted >= 0 && now >= blinkStarted) {
            float p = Math.min(1f, (now - blinkStarted) / 170f);
            blink = p < 0.5f
                    ? 1f - p * 1.90f
                    : (p - 0.5f) * 1.90f;
            blink = Math.max(0.055f, blink);
        }

        float stateScale = 1f;
        float eyeHeightScale = 1f;

        if (mode == MODE_LISTENING) {
            stateScale = 1f + 0.035f * (float)Math.sin(now / 260.0) + voiceLevel * 0.06f;
            eyeHeightScale = 1.04f + voiceLevel * 0.06f;
        } else if (mode == MODE_THINKING) {
            stateScale = 0.98f + 0.02f * (float)Math.sin(now / 190.0);
            eyeHeightScale = 0.92f;
        } else if (mode == MODE_SPEAKING) {
            stateScale = 1f + 0.025f * (float)Math.sin(now / 105.0);
            eyeHeightScale = 0.88f + 0.08f * (float)Math.abs(Math.sin(now / 125.0));
        }

        float ew = eyeW * stateScale;
        float eh = baseEyeH * eyeHeightScale * blink;

        float pupilTravelX = ew * 0.13f;
        float pupilTravelY = Math.max(0f, eh * 0.12f);

        drawEye(
                canvas,
                leftCx,
                cy,
                ew,
                eh,
                lookX * pupilTravelX,
                lookY * pupilTravelY,
                now
        );

        drawEye(
                canvas,
                rightCx,
                cy,
                ew,
                eh,
                lookX * pupilTravelX,
                lookY * pupilTravelY,
                now
        );
    }

    private void drawEye(
            Canvas canvas,
            float cx,
            float cy,
            float ew,
            float eh,
            float px,
            float py,
            long now
    ) {
        float radius = Math.max(12f, eh * 0.42f);

        int glowBlue = connected ? 230 : 195;
        int glowGreen = mode == MODE_LISTENING ? 220 : 180;

        glowPaint.setColor(Color.rgb(0, glowGreen, glowBlue));
        glowPaint.setAlpha(mode == MODE_LISTENING ? 205 : 155);

        RectF outer = new RectF(
                cx - ew / 2f - 10f,
                cy - eh / 2f - 10f,
                cx + ew / 2f + 10f,
                cy + eh / 2f + 10f
        );
        canvas.drawRoundRect(outer, radius, radius, glowPaint);

        paint.setStyle(Paint.Style.FILL);

        int coreBlue = mode == MODE_THINKING ? 242 : 255;
        paint.setColor(Color.rgb(214, 250, coreBlue));

        RectF eye = new RectF(
                cx - ew / 2f,
                cy - eh / 2f,
                cx + ew / 2f,
                cy + eh / 2f
        );
        canvas.drawRoundRect(eye, radius, radius, paint);

        float pupil = Math.max(9f, Math.min(ew, eh) * 0.18f);

        if (mode == MODE_LISTENING) {
            pupil *= 1.03f + voiceLevel * 0.10f;
        }

        paint.setColor(Color.rgb(3, 25, 34));
        canvas.drawCircle(cx + px, cy + py, pupil, paint);

        float irisPulse = 0.88f + 0.12f * (float)Math.sin(now / 260.0);
        paint.setColor(Color.rgb(20, 205, 245));
        canvas.drawCircle(cx + px, cy + py, pupil * 0.48f * irisPulse, paint);

        paint.setColor(Color.WHITE);
        canvas.drawCircle(
                cx + px - pupil * 0.25f,
                cy + py - pupil * 0.28f,
                Math.max(2.5f, pupil * 0.13f),
                paint
        );
    }
}
