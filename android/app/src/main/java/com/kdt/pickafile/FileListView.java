package com.kdt.pickafile;

import androidx.appcompat.app.*;
import android.content.*;
import android.util.*;
import android.widget.*;

import com.ipaulpro.afilechooser.*;
import java.io.*;
import java.util.*;
import net.kdt.pojavlaunch.*;
import android.os.*;

public class FileListView extends LinearLayout
{
    //For list view:
    private File fullPath;
    private ListView mainLv;
    private Context context;

    //For File selected listener:
    private FileSelectedListener fileSelectedListener;
    private DialogTitleListener dialogTitleListener;
    private File lockPath = new File("/");

    //For filtering by file types:
    private final String[] fileSuffixes;
    private boolean showFiles = true;
    private boolean showFolders = true;

    /**
     * 名字以这些前缀开头的**文件**不列出来。
     *
     * 控制布局目录里混着导入流程的中转文件（`TMP_IMPORT_FILE*.json`）：App 内的布局列表
     * 把它们过滤掉了（用户看不到、也就没有删除入口），而这个对话框原先照单全收 ——
     * 结果用户在这里看到一堆「删不掉的怪文件」。调用方用 {@link #skipFilePrefixes} 指定。
     */
    private final java.util.List<String> skipNamePrefixes = new java.util.ArrayList<>();

    public void skipFilePrefixes(String... prefixes) {
        skipNamePrefixes.clear();
        for (String p : prefixes) {
            if (p != null && !p.isEmpty()) skipNamePrefixes.add(p);
        }
    }

    private boolean isSkippedName(String name) {
        for (String prefix : skipNamePrefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    public FileListView(AlertDialog build) {
        this(build.getContext(), null, new String[0]);
        dialogToTitleListener(build);
    }

    public FileListView(AlertDialog build, String fileSuffix) {
        this(build.getContext(), null, new String[]{fileSuffix});
        dialogToTitleListener(build);
    }

    public FileListView(AlertDialog build, String[] fileSuffixes){
        this(build.getContext(), null, fileSuffixes);
        dialogToTitleListener(build);
    }

    public FileListView(Context context){
        this(context, null);
    }

    public FileListView(Context context, AttributeSet attrs){
        this(context, attrs, new String[0]);
    }

    public FileListView(Context context, AttributeSet attrs, String[] fileSuffixes) {
        this(context, attrs, 0, fileSuffixes);
    }

    public FileListView(Context context, AttributeSet attrs, int defStyle, String[] fileSuffixes) {
        super(context, attrs, defStyle);
        this.fileSuffixes = fileSuffixes;
        init(context);
    }

    private void dialogToTitleListener(AlertDialog dialog) {
        if(dialog != null) dialogTitleListener = dialog::setTitle;
    }

    public void init(final Context context) {
        //Main setup:
        this.context = context;

        LayoutParams layParam = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);

        setOrientation(VERTICAL);

        mainLv = new ListView(context);

        mainLv.setOnItemClickListener((p1, p2, p3, p4) -> {
            // TODO: Implement this method
            File mainFile = new File(p1.getItemAtPosition(p3).toString());
            if (p3 == 0 && !lockPath.equals(fullPath)) {
                parentDir();
            } else {
                listFileAt(mainFile);
            }
        });

        mainLv.setOnItemLongClickListener((p1, p2, p3, p4) -> {
            // TODO: Implement this method
            File mainFile = new File(p1.getItemAtPosition(p3).toString());
            if (mainFile.isFile()) {
                fileSelectedListener.onFileLongClick(mainFile, mainFile.getAbsolutePath());
                return true;
            }
            return false;
        });
        addView(mainLv, layParam);

        // 这里原来会先列一次 /sdcard（上游遗留）：targetSdk 36 + scoped storage 下
        // 必然失败，用户打开「加载控制布局」时会先吃两个错误弹窗。
        // 控制布局本来就全在应用私有目录，不需要这次探测。
    }
    public void setFileSelectedListener(FileSelectedListener listener)
    {
        this.fileSelectedListener = listener;
    }
    public void setDialogTitleListener(DialogTitleListener listener) {
        this.dialogTitleListener = listener;
    }

    public void listFileAt(final File path) {
        if (path == null) return; // 上游没做空判断，传 null 会走到 NPE
        try{
            if(path.exists()){
                if(path.isDirectory()){
                    fullPath = path;

                    File[] listFile = path.listFiles();
                    FileListAdapter fileAdapter = new FileListAdapter(context);
                    if(!path.equals(lockPath)){
                        fileAdapter.add(new File(path, ".."));
                    }

                    if(listFile != null && listFile.length != 0){
                        Arrays.sort(listFile, new SortFileName());

                        for(File file : listFile){
                            if(file.isDirectory()){
                                if(showFolders && ((!file.getName().startsWith(".")) || file.getName().equals(".minecraft")))
                                    fileAdapter.add(file);
                                continue;
                            }

                            if(showFiles){
                                // 中转/临时文件（`TMP_IMPORT_FILE` 这类）一律不列：它们不是
                                // 玩家的布局，列出来只会让人以为「目录里怎么有这些怪文件、还删不掉」
                                if(isSkippedName(file.getName())) continue;
                                if(fileSuffixes.length > 0){
                                    for(String suffix : fileSuffixes){
                                        if(file.getName().endsWith("." + suffix)){
                                            fileAdapter.add(file);
                                            break;
                                        }
                                    }
                                }else {
                                    fileAdapter.add(file);
                                }
                            }
                        }
                    }
                    mainLv.setAdapter(fileAdapter);
                    if(dialogTitleListener != null) dialogTitleListener.onChangeDialogTitle(path.getAbsolutePath());
                } else {
                    fileSelectedListener.onFileSelected(path, path.getAbsolutePath());
                }
            } else {
                Toast.makeText(context, "This folder (or file) doesn't exist", Toast.LENGTH_SHORT).show();
                refreshPath();
            }
        } catch (Exception e){
            Tools.showError(context, e);
        }
    }

    public File getFullPath(){
        return fullPath;
    }

    public void refreshPath() {
        listFileAt(getFullPath());
    }

    public void parentDir() {
        if(!fullPath.getAbsolutePath().equals("/")){
            listFileAt(fullPath.getParentFile());
        }
    }

    public void lockPathAt(File path) {
        lockPath = path;
        listFileAt(path);
    }

    public void setShowFiles(boolean showFiles){
        this.showFiles = showFiles;
    }

    public void setShowFolders(boolean showFolders){
        this.showFolders = showFolders;
    }
}
