package com.vrksignatures.sudhaautobot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look of the app in one place: paper, ink, one brand colour and three signals; two
 * weights of one typeface; and the few shapes everything is built from (a card, three kinds
 * of button). The same colours are in {@code res/values/colors.xml} for the layouts.
 *
 * <p>Framework widgets only - no AndroidX, no Material library - so the APK stays small and
 * there is nothing to keep in step with a design system.</p>
 */
final class MagicUi {

    // Paper.
    static final int BG = 0xFFF6F7FB;
    static final int SURFACE = 0xFFFFFFFF;
    static final int LINE = 0xFFE4E6EF;
    // Ink.
    static final int TEXT = 0xFF10122B;
    static final int MUTED = 0xFF62677D;
    static final int FAINT = 0xFF9BA1B5;
    // The navy of Aakriti's avatar, and a tint of it.
    static final int BRAND = 0xFF1B0F7A;
    static final int BRAND_SOFT = 0xFFEEEDFA;
    // Signals.
    static final int GOOD = 0xFF0E9F6E;
    static final int WARN = 0xFFD9822B;
    static final int BAD = 0xFFD63B4B;
    static final int GOOD_SOFT = 0xFFE7F6F0;
    static final int WARN_SOFT = 0xFFFCF1E4;
    static final int BAD_SOFT = 0xFFFBEAEC;

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);


    /** Corner radius of a card, in dp. Buttons and fields use {@link #RADIUS_CONTROL}. */
    static final float RADIUS_CARD = 20;
    static final float RADIUS_CONTROL = 14;

    private MagicUi() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ------------------------------------------------------------------ shapes

    static GradientDrawable rounded(Context context, int fill, int stroke, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radiusDp));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(context, 1)), stroke);
        return shape;
    }

    private static Drawable pressable(Context context, int fill, int stroke, float radiusDp, int ripple) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(context, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(ripple),
                rounded(context, fill, stroke, radiusDp), mask);
    }

    // ------------------------------------------------------------------- views

    static TextView text(Context context, CharSequence words, float sp, int color) {
        TextView view = new TextView(context);
        view.setText(words);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private static TextView button(Context context, CharSequence words, float sp, int textColor,
                                   Drawable background, int minHeightDp, View.OnClickListener action) {
        TextView view = text(context, words, sp, textColor);
        view.setTypeface(MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setBackground(background);
        view.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        view.setMinHeight(dp(context, minHeightDp));
        view.setMaxLines(2);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(action);
        springy(view);
        return view;
    }

    /** The one filled button of a screen. */
    static TextView primaryButton(Context context, CharSequence words, View.OnClickListener action) {
        return button(context, words, 16, 0xFFFFFFFF,
                pressable(context, BRAND, 0, RADIUS_CONTROL, 0x33FFFFFF), 54, action);
    }

    /** A secondary action: a tint of the brand colour. */
    static TextView tonalButton(Context context, CharSequence words, View.OnClickListener action) {
        return button(context, words, 14, BRAND,
                pressable(context, BRAND_SOFT, 0, RADIUS_CONTROL, 0x221B0F7A), 46, action);
    }

    /** A quiet action: white with a hairline. {@code color} is the colour of the words. */
    static TextView quietButton(Context context, CharSequence words, int color,
                                View.OnClickListener action) {
        return button(context, words, 13, color,
                pressable(context, SURFACE, LINE, RADIUS_CONTROL, 0x1A1B0F7A), 44, action);
    }

    /** A small rounded label, e.g. a ready-made question. */
    static TextView chip(Context context, CharSequence words, View.OnClickListener action) {
        TextView view = button(context, words, 12, BRAND,
                pressable(context, BRAND_SOFT, 0, 18, 0x221B0F7A), 36, action);
        view.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        view.setMaxLines(1);
        return view;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(context, SURFACE, LINE, RADIUS_CARD));
        int pad = dp(context, 18);
        card.setPadding(pad, pad, pad, pad);
        return card;
    }

    /** A vector icon in one colour. */
    static ImageView icon(Context context, int drawable, int color) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setColorFilter(color);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return view;
    }

    /** A hairline between rows. */
    static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(LINE);
        return line;
    }

    /**
     * Silent UI: switches off the "click" the phone plays when something is tapped, for a
     * view and everything inside it. (The phone only plays it when "touch sounds" is on in
     * its settings, but this app does not add to it either way.) Call again after adding views.
     */
    static void silence(View view) {
        if (view == null) return;
        view.setSoundEffectsEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) silence(group.getChildAt(i));
        }
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill(Context context, float topDp) {
        LinearLayout.LayoutParams params = fill();
        params.topMargin = dp(context, topDp);
        return params;
    }

    static LinearLayout.LayoutParams wrap(Context context, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topDp);
        return params;
    }

    static LinearLayout.LayoutParams share(Context context, float weight, float startDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMarginStart(dp(context, startDp));
        return params;
    }

    // ------------------------------------------------------------------ motion

    /** The view gives very slightly when pressed. Clicks still arrive. */
    @SuppressLint("ClickableViewAccessibility")
    static void springy(View view) {
        view.setOnTouchListener((pressed, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressed.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
