package top.izuna.foliamajor.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// android/app/src/main/java/top/izuna/foliamajor/android/MainActivity.java
/** Full-screen native host for the unchanged Vite application and its GPU-rendered effects. */
public final class MainActivity extends Activity {
    private FoliaHost host;
    private GeckoView view;
    private FilePrompts filePrompts;
    private boolean canGoBack;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        host = FoliaHost.get(this);
        filePrompts = new FilePrompts(this);
        view = new GeckoView(this);
        setContentView(view);
        try { attachSession(); } catch (IOException error) {
            new AlertDialog.Builder(this).setTitle("Folia 启动失败").setMessage(error.getMessage())
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> finish()).show();
        }
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!android.os.Environment.isExternalStorageManager() && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 90);
    }

    private void attachSession() throws IOException {
        GeckoSession session = host.session();
        session.setPromptDelegate(filePrompts);
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(GeckoSession session, WebResponse response) {
                filePrompts.save(response);
            }
            @Override public void onCrash(GeckoSession session) {
                new AlertDialog.Builder(MainActivity.this).setTitle("播放器进程已退出")
                        .setMessage("请重新打开 Folia；播放队列与设置由主项目自身存储恢复。")
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> finish()).show();
            }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession session, boolean value) { canGoBack = value; }
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                Uri uri = Uri.parse(request.uri);
                if (LocalAssetServer.ORIGIN.equals(uri.getScheme() + "://" + uri.getAuthority()) || "about".equals(uri.getScheme())) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                if (request.hasUserGesture) openExternal(uri);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                openExternal(Uri.parse(uri));
                return null;
            }
        });
        if (view.getSession() == null) view.setSession(session);
        host.keepSessionActive();
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || "javascript".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)
                || "content".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onStart() {
        super.onStart();
        if (host != null && view != null && view.getSession() == null) {
            try { attachSession(); } catch (IOException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onStop() {
        // releaseSession explicitly keeps GeckoSession active while freeing only the visible surface.
        if (view != null && view.getSession() != null) view.releaseSession();
        if (host != null) host.keepSessionActive();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (view != null && view.getSession() != null) view.releaseSession();
        if (host != null && host.existingSession() != null) {
            host.existingSession().setPromptDelegate(null);
            host.existingSession().setContentDelegate(null);
            host.existingSession().setNavigationDelegate(null);
            host.keepSessionActive();
        }
        if (filePrompts != null) filePrompts.cancel();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (canGoBack && host.existingSession() != null) host.existingSession().goBack();
        else moveTaskToBack(true);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        filePrompts.onResult(request, result, data);
    }
}
