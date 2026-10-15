package org.mozilla.geckoview;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import org.mozilla.gecko.util.GeckoBundle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// android/app/src/main/java/org/mozilla/geckoview/FoliaDirectoryPrompt.java
/** Pinned GeckoView 158 bridge: public FilePrompt confirms file paths but only enumerates SAF trees. */
public final class FoliaDirectoryPrompt {
    private FoliaDirectoryPrompt() { }

    public static GeckoBundle[] prepare(File directory) throws IOException {
        File canonicalRoot = directory.getCanonicalFile();
        List<GeckoBundle> files = new ArrayList<>();
        enumerate(canonicalRoot, canonicalRoot, files);
        return files.toArray(new GeckoBundle[0]);
    }

    public static GeckoSession.PromptDelegate.PromptResponse confirm(
            Context context, GeckoSession.PromptDelegate.FilePrompt prompt, File directory, GeckoBundle[] files) throws IOException {
        File canonicalRoot = directory.getCanonicalFile();
        GeckoSession.PromptDelegate.PromptResponse response = prompt.confirm(context, Uri.fromFile(canonicalRoot));
        // ensureResult is package-private in the deliberately fixed official GeckoView dependency.
        prompt.ensureResult().putBundleArray("filesInWebKitDirectory", files);
        return response;
    }

    /** Traverse only the tree explicitly selected by the user; symlinks may not escape that tree. */
    private static void enumerate(File root, File directory, List<GeckoBundle> result) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("无法读取所选文件夹：" + directory.getName());
        for (File entry : entries) {
            File file = entry.getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)) continue;
            // Avoid following directory symlinks, which can form cycles inside the selected tree.
            if (!file.equals(entry.getAbsoluteFile())) continue;
            if (file.isDirectory()) { enumerate(root, file, result); continue; }
            if (!file.isFile() || !file.canRead()) continue;
            GeckoBundle info = new GeckoBundle(7);
            info.putString("filePath", file.getPath());
            info.putString("uri", Uri.fromFile(file).toString());
            info.putString("name", file.getName());
            info.putString("relativePath", root.getName() + "/" + root.toURI().relativize(file.toURI()).getPath());
            String extension = MimeTypeMap.getFileExtensionFromUrl(file.toURI().toString()).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            info.putString("type", mime == null ? "application/octet-stream" : mime);
            info.putLong("lastModified", file.lastModified());
            info.putLong("size", file.length());
            result.add(info);
        }
    }
}
