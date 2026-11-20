package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * SECTION 9: High-Volume Audible Alarm Controller.
 *
 * <p>Uses Android's built-in TYPE_ALARM ringtone on AudioManager.STREAM_ALARM at 90% max volume
 * + continuous vibration pattern (zero audio clips packed in res/raw).</p>
 */
public final class AlarmController {

    private final Context context;
    private MediaPlayer player;
    private Integer previousVolume;
    private volatile boolean ringing;

    public AlarmController(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRinging() {
        return ringing;
    }

    public synchronized void startHighVolumeAlarm() {
        if (ringing) return;
        ringing = true;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                int target = Math.max(1, Math.min(max, Math.round(max * 0.90f)));
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0);
                } catch (SecurityException ignored) {
                }
            }
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri != null) {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(context, uri);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
                player = mp;
            }
        } catch (Throwable ignored) {
        }
        startVibration();
    }

    public synchronized void stopAlarm() {
        ringing = false;
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        if (previousVolume != null) {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0);
            } catch (Throwable ignored) {
            }
            previousVolume = null;
        }
        stopVibration();
    }

    @SuppressWarnings("deprecation")
    private void startVibration() {
        try {
            Vibrator v = getVibrator();
            if (v == null) return;
            long[] pattern = new long[]{0, 600, 300, 600, 300};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                v.vibrate(pattern, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopVibration() {
        try {
            Vibrator v = getVibrator();
            if (v != null) v.cancel();
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Object mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (mgr instanceof VibratorManager) {
                return ((VibratorManager) mgr).getDefaultVibrator();
            }
            return null;
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
