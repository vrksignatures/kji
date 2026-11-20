package com.vrksignatures.sudhaautobot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * Aakriti's messages to the person: "work is done", "I learned a new name", "may I?",
 * "I am stuck - here is the report".
 *
 * <p>Nothing here can hold the bot up: every call returns at once and a notification that
 * cannot be shown (not allowed, no channel) is simply not shown. The same facts are always
 * on the setup screen too.</p>
 */
final class AakritiNotifier {

    private static final String TAG = "SudhaAutoBot";
    /** A channel that makes no sound and does not vibrate; the old, audible one is removed. */
    private static final String CHANNEL = "aakriti_silent";
    private static final String OLD_CHANNEL = "aakriti";
    static final String EXTRA_OPEN = "aakriti_open";
    static final String OPEN_HOME = "home";
    static final String OPEN_NOTEBOOK = "notebook";
    static final String OPEN_CHAT = "chat";
    static final String OPEN_SHARE_WHATSAPP = "share_whatsapp";
    static final String OPEN_SHARE = "share";

    private static final int ID_COMPLETE = 7101;
    private static final int ID_STUCK = 7102;
    private static final int ID_LEARNED = 7103;
    private static final int ID_ASK = 7104;

    private AakritiNotifier() {
    }

    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    /** "Kaam complete hua, aapka kaam complete hua". */
    static void complete(Context context, String tally) {
        show(context, ID_COMPLETE, context.getString(R.string.notify_complete_title),
                context.getString(R.string.notify_complete_text) + "\n" + tally, false, OPEN_HOME);
    }

    static void learned(Context context, List<String> lines, boolean resumed) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        text.append(context.getString(resumed
                ? R.string.notify_learned_resumed : R.string.notify_learned_noted));
        show(context, ID_LEARNED, context.getString(R.string.notify_learned_title), text.toString(),
                false, OPEN_NOTEBOOK);
    }

    static void ask(Context context, String what, String shown) {
        show(context, ID_ASK, context.getString(R.string.notify_ask_title),
                context.getString(R.string.notify_ask_text, what, shown), false, OPEN_NOTEBOOK);
    }

    static void stuck(Context context, String headline, String detail) {
        show(context, ID_STUCK, headline, detail, true, OPEN_NOTEBOOK);
    }

    static void clearStuck(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(ID_STUCK);
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent open(Context context, int request, String what) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction("aakriti." + what)
                .putExtra(EXTRA_OPEN, what)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void show(Context raw, int id, String title, String text, boolean withShare,
                             String opens) {
        try {
            Context context = raw.getApplicationContext();
            if (!allowed(context)) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (manager.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL,
                            context.getString(R.string.notify_channel),
                            NotificationManager.IMPORTANCE_HIGH);
                    channel.setDescription(context.getString(R.string.notify_channel_about));
                    channel.setSound(null, null);
                    channel.enableVibration(false);
                    manager.createNotificationChannel(channel);
                    try {
                        manager.deleteNotificationChannel(OLD_CHANNEL);
                    } catch (Throwable ignored) {
                    }
                }
                builder = new Notification.Builder(context, CHANNEL);
            } else {
                builder = new Notification.Builder(context);
                builder.setPriority(Notification.PRIORITY_HIGH);
                builder.setSound(null);
                builder.setDefaults(0);
            }
            builder.setSmallIcon(R.drawable.ic_aakriti_small)
                    .setLargeIcon(BitmapFactory.decodeResource(context.getResources(), R.drawable.aakriti))
                    .setColor(MagicUi.BRAND)
                    .setContentTitle(title)
                    .setContentText(text.indexOf('\n') > 0 ? text.substring(0, text.indexOf('\n')) : text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(open(context, id, opens));
            if (withShare) {
                builder.addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.notify_send_whatsapp),
                        open(context, id + 100, OPEN_SHARE_WHATSAPP)).build());
                builder.addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.notify_send_other),
                        open(context, id + 200, OPEN_SHARE)).build());
            }
            manager.notify(id, builder.build());
        } catch (Throwable e) {
            Log.w(TAG, "Notification not shown", e);
        }
    }
}
