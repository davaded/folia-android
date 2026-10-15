package top.izuna.foliamajor.android;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import java.io.IOException;

// android/app/src/main/java/top/izuna/foliamajor/android/FoliaHost.java
/** Application lifetime, separate from the visible Activity and its GL surface. */
public final class FoliaHost implements MediaSession.Delegate {
    private final Context context;
    private GeckoRuntime runtime;
    private GeckoSession session;
    private LocalAssetServer server;
    private MediaSession media;
    private PlaybackService playbackService;
    private IOException startupError;
    public MediaSession.Metadata metadata;
    public MediaSession.PositionState position;
    public boolean playing;

    public FoliaHost(Context context) { this.context = context.getApplicationContext(); }
    public static FoliaHost get(Context context) {
        return ((FoliaApplication) context.getApplicationContext()).host();
    }

    /** Start the fixed, loopback-only origin before opening the real Vite build. */
    public GeckoSession session() throws IOException {
        if (session != null && session.isOpen()) return session;
        if (startupError != null) throw startupError;
        if (server == null) {
            server = new LocalAssetServer(context);
            try { server.start(); } catch (IOException error) { startupError = error; throw error; }
        }
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(BuildConfig.DEBUG)
                    .remoteDebuggingEnabled(BuildConfig.DEBUG)
                    .build();
            runtime = GeckoRuntime.create(context, settings);
        }
        session = new GeckoSession();
        session.setMediaSessionDelegate(this);
        session.open(runtime);
        session.setActive(true);
        session.loadUri(LocalAssetServer.ORIGIN + (BuildConfig.DEBUG ? "/?androidDebug=1" : "/"));
        return session;
    }

    public GeckoSession existingSession() { return session; }
    public MediaSession media() { return media; }
    public void attachService(PlaybackService service) { playbackService = service; service.refresh(); }
    public void detachService(PlaybackService service) { if (playbackService == service) playbackService = null; }

    /** Keep the audio and automix JS alive after its surface is released by the Activity. */
    public void keepSessionActive() {
        if (session != null && session.isOpen()) session.setActive(true);
    }

    private void refresh() {
        if (playbackService != null) playbackService.refresh();
    }

    @Override public void onActivated(GeckoSession session, MediaSession mediaSession) {
        media = mediaSession;
        refresh();
    }
    @Override public void onDeactivated(GeckoSession session, MediaSession mediaSession) {
        // Deactivation describes the DOM media session, not playback. A released Activity surface
        // can deactivate it while the active GeckoSession keeps WebAudio running in the service.
        if (media == mediaSession) { media = null; refresh(); }
    }
    @Override public void onMetadata(GeckoSession session, MediaSession mediaSession, MediaSession.Metadata meta) {
        media = mediaSession;
        metadata = meta;
        refresh();
    }
    @Override public void onPositionState(GeckoSession session, MediaSession mediaSession, MediaSession.PositionState state) {
        position = state;
        refresh();
    }
    @Override public void onPlay(GeckoSession session, MediaSession mediaSession) {
        media = mediaSession;
        playing = true;
        keepSessionActive();
        if (playbackService != null) { playbackService.onPlaybackStarted(); return; }
        try {
            context.startForegroundService(new Intent(context, PlaybackService.class));
        } catch (RuntimeException error) {
            Log.e("Folia", "Unable to start media service", error);
            playing = false;
            mediaSession.pause();
        }
    }
    @Override public void onPause(GeckoSession session, MediaSession mediaSession) {
        playing = false;
        refresh();
    }
    @Override public void onStop(GeckoSession session, MediaSession mediaSession) {
        playing = false;
        refresh();
    }
}
