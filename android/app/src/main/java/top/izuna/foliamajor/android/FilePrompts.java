package top.izuna.foliamajor.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.widget.EditText;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebResponse;
import org.mozilla.geckoview.FoliaDirectoryPrompt;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

// android/app/src/main/java/top/izuna/foliamajor/android/FilePrompts.java
/** Resolves actual Android document URIs rather than claiming unsupported file operations succeeded. */
public final class FilePrompts implements GeckoSession.PromptDelegate {
    private static final int PICK_FILE = 71;
    private static final int SAVE_FILE = 72;
    private final Activity activity;
    private FilePrompt pendingPrompt;
    private GeckoResult<PromptResponse> pendingResult;
    private WebResponse download;

    public FilePrompts(Activity activity) { this.activity = activity; }

    @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt prompt) {
        cancelPicker();
        pendingPrompt = prompt;
        pendingResult = new GeckoResult<>();
        GeckoResult<PromptResponse> result = pendingResult;
        boolean folder = prompt.type == FilePrompt.Type.FOLDER;
        Intent intent = new Intent(folder ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!folder) {
            intent.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
            List<String> types = new ArrayList<>();
            if (prompt.mimeTypes != null) {
                for (String type : prompt.mimeTypes) {
                    if (type.startsWith(".")) type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                    if (type != null && type.contains("/")) types.add(type);
                }
            }
            if (types.size() == 1) intent.setType(types.get(0));
            else if (!types.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        }
        try { activity.startActivityForResult(intent, PICK_FILE); }
        catch (ActivityNotFoundException error) {
            new SharedStoragePicker(activity, folder, prompt.type == FilePrompt.Type.MULTIPLE, files -> {
                if (files == null || files.isEmpty()) { cancelPicker(); return; }
                FilePrompt selectedPrompt = pendingPrompt;
                GeckoResult<PromptResponse> selectedResult = pendingResult;
                if (selectedPrompt == null || selectedPrompt.isComplete()) return;
                pendingPrompt = null;
                pendingResult = null;
                if (folder) {
                    // Directory enumeration is I/O and must not stall Gecko's UI thread.
                    new Thread(() -> {
                        try {
                            org.mozilla.gecko.util.GeckoBundle[] fileMetadata = FoliaDirectoryPrompt.prepare(files.get(0));
                            // Gecko prompt completion itself must run on the UI thread.
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try { selectedResult.complete(FoliaDirectoryPrompt.confirm(activity.getApplicationContext(), selectedPrompt, files.get(0), fileMetadata)); }
                                catch (Exception failure) { Log.e("Folia", "Directory import failed", failure); if (!selectedPrompt.isComplete()) selectedResult.complete(selectedPrompt.dismiss()); }
                            });
                        } catch (Exception failure) {
                            Log.e("Folia", "Directory import failed", failure);
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (!selectedPrompt.isComplete()) selectedResult.complete(selectedPrompt.dismiss());
                                Toast.makeText(activity, "导入失败：" + failure.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    }, "Folia-folder-selection").start();
                } else {
                    Uri[] uris = files.stream().map(Uri::fromFile).toArray(Uri[]::new);
                    selectedResult.complete(selectedPrompt.confirm(activity.getApplicationContext(), uris));
                }
            }).show();
        }
        return result;
    }

    @Override public GeckoResult<PromptResponse> onFolderUploadPrompt(GeckoSession session, FolderUploadPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        new AlertDialog.Builder(activity).setTitle("导入文件夹")
                .setMessage("允许 Folia 读取所选文件夹中的文件？\n" + (prompt.directoryName == null ? "" : prompt.directoryName))
                .setPositiveButton("导入", (dialog, which) -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.complete(prompt.confirm(AllowOrDeny.DENY)))
                .setOnCancelListener(dialog -> result.complete(prompt.dismiss())).show();
        return result;
    }

    /** Stream Gecko downloads, including blob exports, into a destination chosen by the user. */
    public void save(WebResponse response) {
        if (download != null) {
            close(response.body);
            Toast.makeText(activity, "请先完成当前保存操作", Toast.LENGTH_SHORT).show();
            return;
        }
        if (response.body == null) { Toast.makeText(activity, "下载内容不可用", Toast.LENGTH_LONG).show(); return; }
        download = response;
        String mime = response.headers.get("Content-Type");
        if (mime == null) mime = response.headers.get("content-type");
        String disposition = response.headers.get("Content-Disposition");
        if (disposition == null) disposition = response.headers.get("content-disposition");
        String name = android.webkit.URLUtil.guessFileName(response.uri, disposition, mime);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime == null ? "application/octet-stream" : mime.split(";")[0])
                .putExtra(Intent.EXTRA_TITLE, name);
        try { activity.startActivityForResult(intent, SAVE_FILE); }
        catch (ActivityNotFoundException error) {
            new SharedStoragePicker(activity, true, false, directories -> {
                if (directories == null || directories.isEmpty()) { close(response.body); download = null; return; }
                EditText filename = new EditText(activity);
                filename.setText(name);
                new AlertDialog.Builder(activity).setTitle("保存文件").setView(filename)
                        .setPositiveButton("保存", (dialog, which) -> {
                            String leaf = filename.getText().toString().trim();
                            if (leaf.isEmpty() || leaf.contains("/") || leaf.contains("\\") || leaf.equals(".") || leaf.equals("..")) {
                                close(response.body); download = null; Toast.makeText(activity, "文件名无效", Toast.LENGTH_LONG).show(); return;
                            }
                            File file = new File(directories.get(0), leaf);
                            if (file.exists()) {
                                close(response.body); download = null; Toast.makeText(activity, "同名文件已存在，请更换文件名后重试", Toast.LENGTH_LONG).show(); return;
                            }
                            download = null;
                            writeDownload(response, Uri.fromFile(file));
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> { close(response.body); download = null; })
                        .setOnCancelListener(dialog -> { close(response.body); download = null; }).show();
            }).show();
        }
    }

    public void onResult(int request, int result, Intent data) {
        if (request == PICK_FILE && pendingPrompt != null) {
            if (result != Activity.RESULT_OK || data == null) { cancelPicker(); return; }
            List<Uri> uris = new ArrayList<>();
            ClipData clips = data.getClipData();
            if (clips != null) for (int index = 0; index < clips.getItemCount(); index++) uris.add(clips.getItemAt(index).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (uris.isEmpty()) { cancelPicker(); return; }
            for (Uri uri : uris) {
                try { activity.getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException error) { Log.w("Folia", "Document provider did not grant persistent access"); }
            }
            FilePrompt prompt = pendingPrompt;
            GeckoResult<PromptResponse> response = pendingResult;
            pendingPrompt = null;
            pendingResult = null;
            try { response.complete(prompt.confirm(activity.getApplicationContext(), uris.toArray(new Uri[0]))); }
            catch (RuntimeException error) { Log.e("Folia", "Unable to resolve selected files", error); response.complete(prompt.dismiss()); }
        }
        if (request == SAVE_FILE && download != null) {
            WebResponse response = download;
            download = null;
            if (result != Activity.RESULT_OK || data == null || data.getData() == null) { close(response.body); return; }
            Uri destination = data.getData();
            writeDownload(response, destination);
        }
    }
    private void writeDownload(WebResponse response, Uri destination) {
            new Thread(() -> {
                OutputStream destinationStream = null;
                try (InputStream input = response.body) {
                    // The native fallback returns file:// URIs because this car ROM has no
                    // DocumentsUI provider. ContentResolver cannot open those on every build.
                    if ("file".equalsIgnoreCase(destination.getScheme())) {
                        String path = destination.getPath();
                        if (path == null) throw new java.io.IOException("目标路径不可用");
                        destinationStream = new FileOutputStream(path);
                    } else {
                        destinationStream = activity.getContentResolver().openOutputStream(destination);
                    }
                    try (OutputStream output = destinationStream) {
                    if (output == null) throw new java.io.IOException("无法写入所选位置");
                    byte[] buffer = new byte[65536];
                    for (int size; (size = input.read(buffer)) != -1;) output.write(buffer, 0, size);
                    }
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(activity, "文件已保存", Toast.LENGTH_SHORT).show());
                } catch (Exception error) {
                    Log.e("Folia", "File export failed", error);
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(activity, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "Folia-file-save").start();
    }
    private void cancelPicker() {
        if (pendingPrompt != null) {
            if (!pendingPrompt.isComplete()) pendingResult.complete(pendingPrompt.dismiss());
            pendingPrompt = null;
            pendingResult = null;
        }
    }
    private static void close(InputStream stream) { if (stream != null) try { stream.close(); } catch (Exception ignored) { } }
    public void cancel() { cancelPicker(); if (download != null) { close(download.body); download = null; } }
}
