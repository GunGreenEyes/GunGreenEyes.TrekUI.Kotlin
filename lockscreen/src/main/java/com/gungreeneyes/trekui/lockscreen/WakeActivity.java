package com.gungreeneyes.trekui.lockscreen;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.gungreeneyes.trekui.lockscreen.logic.DecimalDate;
import com.gungreeneyes.trekui.licensing.Licensing;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The wake screen: shown over the keyguard when the screen turns on ({@link LockListener} starts it), or as
 * a PREVIEW from settings. It draws {@link WakeView} and wires its controls to the system.
 *
 * <p><b>Security.</b> This activity is a cosmetic layer, never a lock. It is shown with
 * {@code setShowWhenLocked} (window flags on Android 8.0), which leaves the real keyguard in place
 * underneath; it never calls a "dismiss without credentials" API. Unlocking is always
 * {@link KeyguardManager#requestDismissKeyguard}: the system asks for the PIN, pattern, password or
 * biometric and only then lets anything behind it show. A notification's intent, the purchase screen and
 * the non-secure camera are only opened after that succeeds. CAMERA uses
 * {@link MediaStore#INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE} (the camera app's locked mode, which cannot
 * open the gallery), and TORCH only switches the flash. BACK simply reveals the system keyguard.
 *
 * <p><b>Licensing.</b> The wake screen never blocks: when the trial session is over it only shows a small
 * UNLOCK APP chip, which opens the purchase screen after the device has been unlocked.
 */
public final class WakeActivity extends Activity implements WakeView.Actions, LockHub.Observer {
    /** Boolean extra: started from settings to preview (the device is unlocked; UNLOCK just closes it). */
    static final String EXTRA_PREVIEW = "preview";

    private static WeakReference<WakeActivity> showing = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private WakeView view;
    private boolean preview;
    private boolean started;
    private BroadcastReceiver receiver;
    private int batteryPercent = -1;
    private boolean charging;
    private MediaSessionManager sessions;
    private MediaController controller;
    private final List<MediaController> watched = new ArrayList<>();
    private CameraManager cameras;
    private String torchCamera;
    private boolean torchOn;

    /** Starts (or brings back) the wake screen from the listener. Never throws. */
    static void start(Context context) {
        Intent i = new Intent(context, WakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            context.startActivity(i);
        } catch (RuntimeException e) {
            // blocked by background-start rules (no overlay permission on Android 10+); the keyguard shows as usual
        }
    }

    /** Opens the wake screen as a preview (from settings, device unlocked). */
    static void preview(Context context) {
        context.startActivity(new Intent(context, WakeActivity.class).putExtra(EXTRA_PREVIEW, true));
    }

    /** True while the wake screen is in front. */
    static boolean isShowing() {
        return showing.get() != null;
    }

    /** Closes the wake screen if it is up (the user unlocked some other way). */
    static void finishShowing() {
        WakeActivity a = showing.get();
        if (a != null && !a.preview) a.finish();
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverKeyguard();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        preview = getIntent().getBooleanExtra(EXTRA_PREVIEW, false);
        view = new WakeView(this, this);
        view.setOnApplyWindowInsetsListener((v, raw) -> {
            WindowInsetsCompat w = WindowInsetsCompat.toWindowInsetsCompat(raw, v);
            Insets bars = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setSafeInsets(bars.left, bars.top, bars.right, bars.bottom);
            return raw;
        });
        setContentView(view);
        view.requestApplyInsets();
        cameras = getSystemService(CameraManager.class);
        torchCamera = findTorchCamera();
    }

    /** Shows the window over the keyguard and turns the screen on (attributes on 8.1+, flags on 8.0). */
    @SuppressWarnings("deprecation")
    private void showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false);
        LockListener.refreshNow();
        update();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        registerReceivers();
        watchMedia();
        if (cameras != null && torchCamera != null) cameras.registerTorchCallback(torchCallback, main);
        LockHub.observe(this);
        LockListener.refreshNow();
        LockTheme.refresh(this, () -> {
            view.reloadLook();
            update();
        });
        update();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (!preview && km != null && !km.isKeyguardLocked()) {
            finish(); // already unlocked: never sit on top of an unlocked phone
            return;
        }
        showing = new WeakReference<>(this);
        view.reloadLook();
        update();
    }

    @Override
    protected void onPause() {
        if (showing.get() == this) showing = new WeakReference<>(null);
        super.onPause();
    }

    @Override
    protected void onStop() {
        started = false;
        LockHub.unobserve(this);
        unwatchMedia();
        if (cameras != null && torchCamera != null) cameras.unregisterTorchCallback(torchCallback);
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        super.onStop();
    }

    @Override
    public void onItemsChanged() {
        if (started) update();
    }

    // ---- the model ----------------------------------------------------------------------------

    /** Rebuilds the view model from the clock, battery, media, torch, licence and notification list. */
    private void update() {
        WakeView.Model m = new WakeView.Model();
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        Locale locale = Locale.getDefault();
        m.time = DateFormat.format(h24 ? "HH:mm" : "h:mm", now).toString();
        m.amPm = h24 ? "" : DateFormat.format("a", now).toString().toUpperCase(locale);
        m.date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), now).toString()
                .toUpperCase(locale);
        Calendar cal = Calendar.getInstance();
        int second = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND);
        m.decimalDate = DecimalDate.format(cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR), second);
        m.alarm = nextAlarm(h24);
        m.batteryPercent = batteryPercent;
        m.charging = charging;

        MediaController mc = controller;
        if (mc != null) {
            MediaMetadata meta = mc.getMetadata();
            PlaybackState state = mc.getPlaybackState();
            m.hasMedia = meta != null || state != null;
            if (meta != null) {
                m.mediaTitle = nonNull(meta.getString(MediaMetadata.METADATA_KEY_TITLE));
                m.mediaArtist = nonNull(meta.getString(MediaMetadata.METADATA_KEY_ARTIST));
            }
            if (m.mediaTitle.isEmpty()) m.mediaTitle = getString(R.string.wake_media_unknown);
            m.mediaPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        }

        m.torchAvailable = torchCamera != null;
        m.torchOn = torchOn;
        m.trialChip = !Licensing.isUnlocked(this) && Licensing.trialRemainingMillis(this) <= 0;
        m.listenerOn = LockListener.isConnected();
        m.items = LockHub.items();
        view.setModel(m);
    }

    private String nextAlarm(boolean h24) {
        AlarmManager am = getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo next = am == null ? null : am.getNextAlarmClock();
        if (next == null) return "";
        long t = next.getTriggerTime();
        if (t - System.currentTimeMillis() > 24L * 60 * 60 * 1000) {
            return DateFormat.format(h24 ? "EEE HH:mm" : "EEE h:mm a", t).toString().toUpperCase(Locale.getDefault());
        }
        return DateFormat.format(h24 ? "HH:mm" : "h:mm a", t).toString().toUpperCase(Locale.getDefault());
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    // ---- receivers: battery and time ----------------------------------------------------------

    private void registerReceivers() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) readBattery(intent);
                update();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        Intent sticky = ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        if (sticky != null) readBattery(sticky);
    }

    private void readBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        batteryPercent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    // ---- media (through the notification listener's access) -----------------------------------

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { pickController(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
        @Override public void onSessionDestroyed() { pickController(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickController();

    private ComponentName listenerComponent() {
        return new ComponentName(this, LockListener.class);
    }

    private void watchMedia() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent(), main);
        } catch (SecurityException e) {
            sessions = null; // no notification access: no media controls
            return;
        }
        pickController();
    }

    private void unwatchMedia() {
        if (sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        for (MediaController c : watched) c.unregisterCallback(mediaCallback);
        watched.clear();
        controller = null;
    }

    /** The session to show: the one playing, else the most recent with something loaded. */
    private void pickController() {
        if (sessions == null) return;
        List<MediaController> list;
        try {
            list = sessions.getActiveSessions(listenerComponent());
        } catch (SecurityException e) {
            list = new ArrayList<>();
        }
        for (MediaController c : watched) c.unregisterCallback(mediaCallback);
        watched.clear();
        MediaController best = null;
        for (MediaController c : list) {
            c.registerCallback(mediaCallback, main);
            watched.add(c);
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                best = c;
                break;
            }
            if (best == null && c.getMetadata() != null) best = c;
        }
        controller = best;
        if (started) update();
    }

    @Override
    public void media(int command) {
        MediaController c = controller;
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        if (command < 0) {
            t.skipToPrevious();
        } else if (command > 0) {
            t.skipToNext();
        } else {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause();
            else t.play();
        }
    }

    // ---- torch --------------------------------------------------------------------------------

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCamera)) {
                torchOn = enabled;
                if (started) update();
            }
        }
    };

    /** The id of a camera with a flash unit (back camera preferred), or null. */
    private String findTorchCamera() {
        if (cameras == null) return null;
        try {
            String any = null;
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics ch = cameras.getCameraCharacteristics(id);
                if (!Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) continue;
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                if (any == null) any = id;
            }
            return any;
        } catch (CameraAccessException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void torch() {
        if (cameras == null || torchCamera == null) return;
        try {
            cameras.setTorchMode(torchCamera, !torchOn); // needs no CAMERA permission
        } catch (CameraAccessException | RuntimeException e) {
            Toast.makeText(this, R.string.wake_torch_busy, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- camera, unlock, notifications, purchase -----------------------------------------------

    @Override
    public void camera() {
        try {
            startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE));
        } catch (ActivityNotFoundException e) {
            // no secure-camera mode on this device: only open the regular camera after a real unlock
            unlockThen(() -> {
                try {
                    startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException none) {
                    Toast.makeText(getApplicationContext(), R.string.wake_no_camera, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void unlock() {
        unlockThen(null);
    }

    @Override
    public void openItem(LockItem item) {
        PendingIntent pi = item.contentIntent;
        unlockThen(pi == null ? null : () -> {
            try {
                pi.send();
            } catch (PendingIntent.CanceledException ignored) {
                // the app withdrew it
            }
        });
    }

    @Override
    public void purchase() {
        unlockThen(() -> Licensing.openPurchaseScreen(getApplicationContext()));
    }

    /**
     * Hands over to the system keyguard; {@code after} runs only once the user has really unlocked (the
     * system verified the PIN / pattern / password / biometric). Cancelling leaves the wake screen up.
     */
    private void unlockThen(Runnable after) {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) {
            if (after != null) after.run();
            finish();
            return;
        }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                if (after != null) after.run();
                finish();
            }
        });
    }
}
