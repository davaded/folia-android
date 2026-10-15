package top.izuna.foliamajor.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

// android/app/src/main/java/top/izuna/foliamajor/android/PlaybackService.java
/** Publishes the real Gecko/WebAudio player; it does not create a second native audio player. */
public final class PlaybackService extends Service {
    private static final String CHANNEL = "folia-playback";
    private static final int NOTIFICATION_ID = 739;
    private FoliaHost host;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private boolean hasFocus;
    private org.mozilla.geckoview.Image requestedArtwork;
    private Bitmap artwork;

    @Override public void onCreate() {
        super.onCreate();
        host = FoliaHost.get(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Folia 音乐播放", NotificationManager.IMPORTANCE_LOW));
        audioManager = getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .build();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Folia:WebAudioPlayback");
        wakeLock.setReferenceCounted(false);
        session = new MediaSession(this, "Folia");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (requestFocus() && host.media() != null) host.media().play(); }
            @Override public void onPause() { if (host.media() != null) host.media().pause(); }
            @Override public void onSkipToNext() { if (host.media() != null) host.media().nextTrack(); }
            @Override public void onSkipToPrevious() { if (host.media() != null) host.media().previousTrack(); }
            @Override public void onSeekTo(long position) { if (host.media() != null) host.media().seekTo(position / 1000.0, false); }
            @Override public void onStop() { stopPlayback(); }
        });
        session.setSessionActivity(openPendingIntent());
        session.setActive(true);
        startForeground(NOTIFICATION_ID, notification());
        host.attachService(this);
        if (host.playing) onPlaybackStarted();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "play" -> { if (requestFocus() && host.media() != null) host.media().play(); }
                case "pause" -> { if (host.media() != null) host.media().pause(); }
                case "next" -> { if (host.media() != null) host.media().nextTrack(); }
                case "previous" -> { if (host.media() != null) host.media().previousTrack(); }
                case "stop" -> stopPlayback();
                default -> { }
            }
        }
        return START_NOT_STICKY;
    }

    void onPlaybackStarted() {
        if (!requestFocus()) {
            host.playing = false;
            if (host.media() != null) host.media().pause();
        }
        refresh();
    }

    private boolean requestFocus() {
        if (hasFocus) return true;
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (hasFocus && host.media() != null) {
            // A synchronous grant after a permanent loss has no GAIN callback; clear Gecko's
            // platform interruption before asking the DOM MediaSession to resume WebAudio.
            host.media().notifySystemAudioFocusChange(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_GAIN);
        }
        if (!hasFocus) Log.w("Folia", "Audio focus denied; playback paused");
        return hasFocus;
    }

    /** Gecko interrupts all media elements and WebAudio together, preserving the shared graph. */
    private void onFocusChange(int change) {
        org.mozilla.geckoview.MediaSession media = host.media();
        if (media == null) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true;
            media.notifySystemAudioFocusChange(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_GAIN);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            hasFocus = false;
            media.notifySystemAudioFocusChange(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_PERMANENT_LOSS);
            media.pause();
        } else {
            hasFocus = false;
            // Treat duck requests as transient interruption until actual car mixing is verified.
            media.notifySystemAudioFocusChange(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_TRANSIENT_LOSS);
        }
    }

    /** Reflect playback metadata and time reported by Gecko, never an independent wall clock. */
    void refresh() {
        if (session == null) return;
        MediaMetadata.Builder builder = new MediaMetadata.Builder();
        org.mozilla.geckoview.MediaSession.Metadata meta = host.metadata;
        if (meta != null) {
            builder.putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, meta.album);
            if (requestedArtwork != meta.artwork) {
                requestedArtwork = meta.artwork;
                artwork = null;
                if (requestedArtwork != null) {
                    org.mozilla.geckoview.Image request = requestedArtwork;
                    request.getBitmap(512).accept(bitmap -> {
                        if (request == requestedArtwork && session != null) { artwork = bitmap; refresh(); }
                    }, error -> Log.w("Folia", "Unable to decode media artwork", error));
                }
            }
        }
        if (artwork != null) builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        long positionMs = 0;
        float rate = 1f;
        if (host.position != null) {
            positionMs = (long) (host.position.position * 1000);
            rate = (float) host.position.playbackRate;
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (host.position.duration * 1000));
        }
        session.setMetadata(builder.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(host.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        positionMs, host.playing ? rate : 0f, SystemClock.elapsedRealtime()).build());
        if (host.playing && !wakeLock.isHeld()) wakeLock.acquire();
        if (!host.playing && wakeLock.isHeld()) wakeLock.release();
        // A DOM pause is a user transport decision; release the platform focus so another
        // vehicle source can play. Transient system interruptions set hasFocus=false before
        // suspending Gecko audio and therefore keep their resumable state intact.
        if (!host.playing && hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            hasFocus = false;
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private PendingIntent command(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent openPendingIntent() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        String title = host != null && host.metadata != null ? host.metadata.title : "Folia";
        String artist = host != null && host.metadata != null ? host.metadata.artist : "";
        boolean playing = host != null && host.playing;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_folia).setContentTitle(title).setContentText(artist)
                .setContentIntent(openPendingIntent()).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing).setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一首", command("previous")).build())
                .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", command(playing ? "pause" : "play")).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "下一首", command("next")).build());
        if (artwork != null) builder.setLargeIcon(artwork);
        if (session != null) builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    private void stopPlayback() {
        if (host.media() != null) host.media().pause();
        host.playing = false;
        audioManager.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) { if (!host.playing) stopSelf(); }
    @Override public void onDestroy() {
        host.detachService(this);
        if (host.playing && host.media() != null) host.media().pause();
        host.playing = false;
        audioManager.abandonAudioFocusRequest(focusRequest);
        if (wakeLock.isHeld()) wakeLock.release();
        session.release();
        session = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
