package top.izuna.foliamajor.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;
import android.net.Uri;
import android.provider.Settings;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

// android/app/src/main/java/top/izuna/foliamajor/android/SharedStoragePicker.java
/** A fallback for car ROMs without DocumentsUI; enumerates one user-visible directory at a time. */
public final class SharedStoragePicker {
    private final Activity activity;
    private final File root = Environment.getExternalStorageDirectory();
    private final Set<File> selected = new LinkedHashSet<>();
    private final boolean folder;
    private final boolean multiple;
    private final Consumer<List<File>> completion;

    public SharedStoragePicker(Activity activity, boolean folder, boolean multiple, Consumer<List<File>> completion) {
        this.activity = activity;
        this.folder = folder;
        this.multiple = multiple;
        this.completion = completion;
    }
    public void show() { showDirectory(root); }

    private boolean allowed(File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String path = file.getCanonicalPath();
            return (path.equals(rootPath) || path.startsWith(rootPath + File.separator))
                    && !path.startsWith(rootPath + "/Android/") && !path.equals(rootPath + "/Android");
        } catch (IOException error) { return false; }
    }

    private void showDirectory(File directory) {
        File[] children = directory.listFiles(file -> allowed(file) && (!folder || file.isDirectory()));
        if (children == null) {
            new AlertDialog.Builder(activity).setTitle("无法读取所选目录")
                    .setMessage("当前应用没有此目录的访问权限。请在系统应用权限中授予 Folia 文件访问权限后重试。\n" + directory)
                    .setPositiveButton("授予文件访问", (dialog, which) -> {
                        completion.accept(null);
                        try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName()))); }
                        catch (ActivityNotFoundException error) { Toast.makeText(activity, "此系统未提供文件权限设置界面，请在应用管理中授权", Toast.LENGTH_LONG).show(); }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> completion.accept(null)).show();
            return;
        }
        Arrays.sort(children, Comparator.comparing((File file) -> !file.isDirectory()).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        List<File> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (!directory.equals(root)) { entries.add(directory.getParentFile()); labels.add("↑ 上一级"); }
        for (File child : children) {
            entries.add(child);
            labels.add((child.isDirectory() ? "▸ " : selected.contains(child) ? "✓ " : "  ") + child.getName());
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle((folder ? "选择文件夹" : "选择文件") + " · " + directory.getName())
                .setItems(labels.toArray(new String[0]), (choice, index) -> {
                    File chosen = entries.get(index);
                    if (chosen.isDirectory()) { showDirectory(chosen); return; }
                    if (!multiple) { completion.accept(java.util.Collections.singletonList(chosen)); return; }
                    if (!selected.add(chosen)) selected.remove(chosen);
                    showDirectory(directory);
                })
                .setNegativeButton(android.R.string.cancel, (choice, which) -> completion.accept(null))
                .setOnCancelListener(choice -> completion.accept(null))
                .create();
        if (folder) dialog.setButton(AlertDialog.BUTTON_POSITIVE, "使用此文件夹", (choice, which) -> {
            // Importing the entire shared storage is deliberately not offered by the fallback.
            if (directory.equals(root)) {
                Toast.makeText(activity, "请进入 Music、Download 或其他具体文件夹后选择", Toast.LENGTH_LONG).show();
                showDirectory(directory);
            } else completion.accept(java.util.Collections.singletonList(directory));
        });
        else if (multiple) dialog.setButton(AlertDialog.BUTTON_POSITIVE, "导入已选文件 (" + selected.size() + ")", (choice, which) -> completion.accept(new ArrayList<>(selected)));
        dialog.show();
    }
}
