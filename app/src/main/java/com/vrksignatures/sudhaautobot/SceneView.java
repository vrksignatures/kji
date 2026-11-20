package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Outline;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * The two full-screen moments: the list is done, or the run has stopped.
 *
 * <p>One white card on a dimmed screen: Aakriti (or the person's own picture) with a tick or
 * an alert mark, a headline, one line under it and the figures. It arrives with one small
 * movement and then holds still. It makes no sound.</p>
 *
 * <p>Pure presentation: built from words handed to it, it reads nothing from the run.</p>
 */
final class SceneView extends FrameLayout {

    enum Kind { VICTORY, DRAMA }

    private final LinearLayout card;
    private final View mark;
    private final TextView subline;
    private Drawable picture;

    SceneView(Context context, Kind kind, String headlineWords, String sublineWords,
              String detailWords, File ownPicture) {
        super(context);
        boolean victory = kind == Kind.VICTORY;
        int signal = victory ? MagicUi.GOOD : MagicUi.BAD;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setBackgroundColor(0xB310122B);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(MagicUi.rounded(context, MagicUi.SURFACE, 0, 28));
        card.setElevation(MagicUi.dp(context, 12));
        int pad = MagicUi.dp(context, 26);
        card.setPadding(pad, MagicUi.dp(context, 30), pad, MagicUi.dp(context, 20));
        LayoutParams cardAt = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        int side = MagicUi.dp(context, 28);
        cardAt.setMargins(side, side, side, side);
        addView(card, cardAt);

        // The picture, round, with the mark sitting on its rim.
        int size = MagicUi.dp(context, 104);
        FrameLayout hero = new FrameLayout(context);
        hero.setClipChildren(false);
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        image.setClipToOutline(true);
        picture = load(context, ownPicture);
        if (picture != null) image.setImageDrawable(picture);
        else image.setImageResource(R.drawable.aakriti);
        hero.addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ImageView badge = MagicUi.icon(context,
                victory ? R.drawable.ic_check_circle : R.drawable.ic_alert_circle, signal);
        GradientDrawable rim = new GradientDrawable();
        rim.setShape(GradientDrawable.OVAL);
        rim.setColor(MagicUi.SURFACE);
        badge.setBackground(rim);
        int inset = MagicUi.dp(context, 2);
        badge.setPadding(inset, inset, inset, inset);
        int badgeSize = MagicUi.dp(context, 34);
        hero.addView(badge, new LayoutParams(badgeSize, badgeSize, Gravity.BOTTOM | Gravity.END));
        mark = badge;
        card.addView(hero, new LinearLayout.LayoutParams(size, size));

        TextView headline = MagicUi.text(context, headlineWords, 22, MagicUi.TEXT);
        headline.setTypeface(MagicUi.MEDIUM);
        headline.setGravity(Gravity.CENTER);
        headline.setLetterSpacing(0.01f);
        card.addView(headline, MagicUi.fill(context, 20));

        subline = MagicUi.text(context, sublineWords, 15, MagicUi.MUTED);
        subline.setGravity(Gravity.CENTER);
        subline.setLineSpacing(0, 1.15f);
        subline.setMinLines(2);
        card.addView(subline, MagicUi.fill(context, 6));

        if (detailWords != null && !detailWords.trim().isEmpty()) {
            TextView words = MagicUi.text(context, detailWords.trim(), 13, MagicUi.TEXT);
            words.setGravity(Gravity.CENTER);
            words.setMaxLines(6);
            words.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.setLineSpacing(0, 1.2f);
            words.setBackground(MagicUi.rounded(context,
                    victory ? MagicUi.GOOD_SOFT : MagicUi.BAD_SOFT, 0, MagicUi.RADIUS_CONTROL));
            int inner = MagicUi.dp(context, 14);
            words.setPadding(inner, inner, inner, inner);
            card.addView(words, MagicUi.fill(context, 14));
        }

        TextView hint = MagicUi.text(context, "Tap anywhere to close", 12, MagicUi.FAINT);
        LinearLayout.LayoutParams hintAt = MagicUi.wrap(context, 18);
        hintAt.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(hint, hintAt);
    }

    /** The card rises into place and the mark lands on the picture. Then nothing moves. */
    void play() {
        if (picture instanceof Animatable) ((Animatable) picture).start();
        setAlpha(0f);
        animate().alpha(1f).setDuration(200).start();
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.setTranslationY(MagicUi.dp(getContext(), 16));
        card.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        mark.setScaleX(0f);
        mark.setScaleY(0f);
        mark.animate().scaleX(1f).scaleY(1f).setStartDelay(260).setDuration(360)
                .setInterpolator(new OvershootInterpolator(2.4f)).start();
    }

    void stop() {
        card.animate().cancel();
        mark.animate().cancel();
        if (picture instanceof Animatable) ((Animatable) picture).stop();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    /** The person's own picture or GIF, scaled down; null when there is none or it is unreadable. */
    private static Drawable load(Context context, File file) {
        if (!FeedbackPrefs.has(file)) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return ImageDecoder.decodeDrawable(ImageDecoder.createSource(file),
                        (decoder, info, source) -> {
                            int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                            int sample = 1;
                            while (longest / sample > 720) sample *= 2;
                            decoder.setTargetSampleSize(sample);
                        });
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 720) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return bitmap == null ? null
                    : new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
        } catch (Throwable e) {
            return null;
        }
    }
}
