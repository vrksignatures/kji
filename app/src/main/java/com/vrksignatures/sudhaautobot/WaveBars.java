package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The little moving bars beside the song title on the mini player. They move while a song
 * is being heard and lie low when it is not. Drawing only; makes no sound.
 */
final class WaveBars extends View {
    private static final int BARS = 4;
    private static final float[] SPEED = {1.9f, 2.7f, 2.2f, 3.1f};
    private static final float[] PHASE = {0f, 1.3f, 2.1f, 0.6f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private boolean playing;

    WaveBars(Context context) {
        super(context);
        paint.setColor(MagicUi.BRAND);
        setSoundEffectsEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        paint.setColor(on ? MagicUi.BRAND : MagicUi.FAINT);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float slot = w / BARS;
        float thick = slot * 0.55f;
        float seconds = SystemClock.uptimeMillis() / 1000f;
        for (int i = 0; i < BARS; i++) {
            float level = playing
                    ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(seconds * SPEED[i] * 2.2f + PHASE[i]))
                    : 0.22f;
            float left = i * slot + (slot - thick) / 2f;
            bar.set(left, h - Math.max(thick, h * level), left + thick, h);
            canvas.drawRoundRect(bar, thick / 2f, thick / 2f, paint);
        }
        if (playing && isShown()) postInvalidateOnAnimation();
    }
}
