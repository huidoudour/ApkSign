package me.huidoudour.apksign.activity;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import me.huidoudour.apksign.R;
import me.huidoudour.apksign.keystore.KeystoreConfig;
import me.huidoudour.apksign.keystore.KeystoreRepository;
import me.huidoudour.apksign.signer.ApkSignTask;
import me.huidoudour.apksign.signer.UriPathResolver;
import me.huidoudour.apksign.ui.InstallerPickerDialog;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_LAST_CONFIG = "last_config_id";
    private static final String PREF_ASKED_STORAGE = "asked_storage_perm";

    private KeystoreRepository repository;
    private List<KeystoreConfig> configs = new ArrayList<>();

    private TextView tvApkInfo;
    private TextView tvNoKeystore;
    private TextView tvLog;
    private Spinner spinnerKeystore;
    private CheckBox cbV1, cbV2, cbV3;
    private Button btnSign;
    private Button btnInstall;
    private LinearProgressIndicator progress;

    private Uri apkUri;
    private String apkDisplayName;

    // 最近一次签名成功的产物（Uri 与文件路径二选一）
    private Uri signedOutputUri;
    private File signedOutputFile;

    private ActivityResultLauncher<String[]> pickApkLauncher;
    private ActivityResultLauncher<String> requestWritePermLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        repository = new KeystoreRepository(this);

        tvApkInfo = findViewById(R.id.tv_apk_info);
        tvNoKeystore = findViewById(R.id.tv_no_keystore);
        tvLog = findViewById(R.id.tv_log);
        spinnerKeystore = findViewById(R.id.spinner_keystore);
        cbV1 = findViewById(R.id.cb_v1);
        cbV2 = findViewById(R.id.cb_v2);
        cbV3 = findViewById(R.id.cb_v3);
        btnSign = findViewById(R.id.btn_sign);
        btnInstall = findViewById(R.id.btn_install);
        progress = findViewById(R.id.progress);

        pickApkLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onApkPicked);
        // Android 10 的传统写权限申请（11+ 走设置页的所有文件访问权限）
        requestWritePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted && apkUri != null) startSign();
                });

        findViewById(R.id.btn_select_apk).setOnClickListener(v ->
                pickApkLauncher.launch(new String[]{"*/*"}));
        findViewById(R.id.btn_manage_keystore).setOnClickListener(v ->
                startActivity(new Intent(this, KeystoreManagerActivity.class)));
        btnSign.setOnClickListener(v -> startSign());
        btnInstall.setOnClickListener(v -> requestInstall());

        // 选中即记住，避免 onResume 刷新列表后跳回第一项
        spinnerKeystore.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < configs.size()) {
                    getPrefs().edit().putString(PREF_LAST_CONFIG, configs.get(position).id).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        handleIncomingIntent(getIntent());
        maybeRequestStorageOnFirstRun();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConfigs();
    }

    /** 支持从文件管理器 "打开方式/分享" 传入 APK */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) onApkPicked(uri);
    }

    private void onApkPicked(Uri uri) {
        if (uri == null) return;
        apkUri = uri;
        long size = -1;
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) name = c.getString(nameIdx);
                if (sizeIdx >= 0) size = c.getLong(sizeIdx);
            }
        } catch (Exception ignored) {
        }
        apkDisplayName = name != null ? name : "unknown.apk";
        String info = apkDisplayName;
        if (size >= 0) {
            info += String.format(Locale.getDefault(), " (%.2f MB)", size / 1048576.0);
        }
        tvApkInfo.setText(info);
    }

    private void refreshConfigs() {
        configs = repository.loadAll();
        List<String> names = new ArrayList<>();
        for (KeystoreConfig c : configs) {
            names.add(c.name + " [" + c.keyAlias + "]");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerKeystore.setAdapter(adapter);

        boolean empty = configs.isEmpty();
        tvNoKeystore.setVisibility(empty ? View.VISIBLE : View.GONE);
        spinnerKeystore.setVisibility(empty ? View.GONE : View.VISIBLE);

        // 恢复上次选中的配置（setSelection(i, false) 不触发一次多余回调）
        String lastId = getPrefs().getString(PREF_LAST_CONFIG, null);
        if (lastId != null) {
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).id.equals(lastId)) {
                    spinnerKeystore.setSelection(i, false);
                    break;
                }
            }
        }
    }

    private void startSign() {
        if (apkUri == null) {
            Toast.makeText(this, R.string.toast_select_apk_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (configs.isEmpty() || spinnerKeystore.getSelectedItemPosition() < 0) {
            Toast.makeText(this, R.string.toast_select_keystore_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cbV1.isChecked() && !cbV2.isChecked() && !cbV3.isChecked()) {
            Toast.makeText(this, R.string.toast_select_scheme_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String outName = apkDisplayName.endsWith(".apk")
                ? apkDisplayName.substring(0, apkDisplayName.length() - 4) + "-signed.apk"
                : apkDisplayName + "-signed.apk";

        // 优先尝试存回原文件所在目录（需能解析出真实路径且持有存储权限）
        File sourceFile = UriPathResolver.resolve(this, apkUri);
        File sourceDir = sourceFile != null ? sourceFile.getParentFile() : null;
        if (sourceDir != null && sourceDir.canRead()) {
            if (canWriteExternal()) {
                File outputFile = uniqueFile(sourceDir, outName);
                doSign(null, outputFile, outputFile.getAbsolutePath());
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_dialog_title)
                    .setMessage(getString(R.string.perm_dialog_msg, sourceDir.getAbsolutePath()))
                    .setPositiveButton(R.string.perm_go_grant, (d, w) -> {
                        requestWriteExternal();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Toast.makeText(this, R.string.perm_granted_retry, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(R.string.perm_use_download, (d, w) -> signToDownloads(outName))
                    .show();
            return;
        }
        signToDownloads(outName);
    }

    /** 回退方案：写入公共下载目录 Download/ApkSign/ */
    private void signToDownloads(String outName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, outName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ApkSign");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri outputUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            Toast.makeText(this, R.string.output_create_failed, Toast.LENGTH_LONG).show();
            return;
        }
        doSign(outputUri, null, Environment.DIRECTORY_DOWNLOADS + "/ApkSign/" + outName);
    }

    private void doSign(Uri outputUri, File outputFile, String locationLabel) {
        KeystoreConfig config = configs.get(spinnerKeystore.getSelectedItemPosition());
        getPrefs().edit().putString(PREF_LAST_CONFIG, config.id).apply();

        // 新一轮签名开始，隐藏上一轮的安装入口
        signedOutputUri = null;
        signedOutputFile = null;
        btnInstall.setVisibility(View.GONE);

        setBusy(true);
        appendLog("开始签名: " + apkDisplayName + " → 密钥[" + config.name + "]");
        ApkSignTask.run(this, apkUri, config,
                cbV1.isChecked(), cbV2.isChecked(), cbV3.isChecked(),
                outputUri, outputFile, new ApkSignTask.Callback() {
                    @Override
                    public void onProgress(String message) {
                        appendLog(message);
                    }

                    @Override
                    public void onSuccess(long elapsedMs) {
                        if (outputUri != null) {
                            // 清除 IS_PENDING，让文件对其他应用可见
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Downloads.IS_PENDING, 0);
                            getContentResolver().update(outputUri, done, null, null);
                        }
                        setBusy(false);
                        signedOutputUri = outputUri;
                        signedOutputFile = outputFile;
                        btnInstall.setVisibility(View.VISIBLE);
                        String msg = getString(R.string.sign_success, elapsedMs);
                        appendLog(msg);
                        appendLog(getString(R.string.sign_output_path, locationLabel));
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(Throwable error) {
                        // 失败时清理占位的输出条目/残留文件
                        try {
                            if (outputUri != null) getContentResolver().delete(outputUri, null, null);
                            if (outputFile != null && outputFile.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                outputFile.delete();
                            }
                        } catch (Exception ignored) {
                        }
                        setBusy(false);
                        String msg = getString(R.string.sign_failed,
                                error.getMessage() != null ? error.getMessage() : error.toString());
                        appendLog(msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setBusy(boolean busy) {
        btnSign.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    /** 签名成功后的"请求安装"：弹出安装器选择对话框 */
    private void requestInstall() {
        Uri installUri = null;
        if (signedOutputFile != null) {
            if (signedOutputFile.isFile()) {
                // 文件路径产物通过 FileProvider 转成可授权的 content Uri
                installUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", signedOutputFile);
            }
        } else {
            installUri = signedOutputUri;
        }
        if (installUri == null) {
            btnInstall.setVisibility(View.GONE);
            Toast.makeText(this, R.string.install_output_missing, Toast.LENGTH_LONG).show();
            return;
        }
        InstallerPickerDialog.show(this, installUri);
    }

    /** 目标目录下同名时自动追加序号，避免覆盖 */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name.endsWith(".apk") ? name.substring(0, name.length() - 4) : name;
        for (int i = 1; ; i++) {
            f = new File(dir, base + "(" + i + ").apk");
            if (!f.exists()) return f;
        }
    }

    /** 首次启动时引导授予存储权限（只弹一次，拒绝后签名时仍有兜底引导） */
    private void maybeRequestStorageOnFirstRun() {
        if (canWriteExternal() || getPrefs().getBoolean(PREF_ASKED_STORAGE, false)) return;
        getPrefs().edit().putBoolean(PREF_ASKED_STORAGE, true).apply();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.first_run_perm_title)
                .setMessage(R.string.first_run_perm_msg)
                .setPositiveButton(R.string.perm_go_grant, (d, w) -> requestWriteExternal())
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private boolean canWriteExternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestWriteExternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestWritePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        tvLog.append("[" + time + "] " + message + "\n");
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("apksign", MODE_PRIVATE);
    }
}
