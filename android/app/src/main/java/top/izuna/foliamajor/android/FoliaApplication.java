package top.izuna.foliamajor.android;

import android.app.Application;

// android/app/src/main/java/top/izuna/foliamajor/android/FoliaApplication.java
public final class FoliaApplication extends Application {
    private FoliaHost host;

    @Override public void onCreate() {
        super.onCreate();
        host = new FoliaHost(this);
    }

    public FoliaHost host() { return host; }
}
