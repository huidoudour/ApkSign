package me.huidoudour.apksign;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import me.huidoudour.apksign.keystore.KeystoreConfig;
import me.huidoudour.apksign.keystore.KeystoreRepository;
import me.huidoudour.apksign.signer.ApkSignTask;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_LAST_CONFIG = "last_config_id";

    private KeystoreRepository repository;
    private List<KeystoreConfig> configs = new ArrayList<>();

    private TextView tvApkInfo;
    private TextView tvNoKeystore;
    private TextView tvLog;
    private Spinner spinnerKeystore;
    private CheckBox cbV1, cbV2, cbV3;
    private Button btnSign;
    private LinearProgressIndicator progress;

    private Uri apkUri;
    private String apkDisplayName;

    private ActivityResultLauncher<String[]> pickApkLauncher;
    private ActivityResultLauncher<String> createOutputLauncher;

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
        progress = findViewById(R.id.progress);

        pickApkLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onApkPicked);
        createOutputLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
                this::onOutputCreated);

        findViewById(R.id.btn_select_apk).setOnClickListener(v ->
                pickApkLauncher.launch(new String[]{"*/*"}));
        findViewById(R.id.btn_manage_keystore).setOnClickListener(v ->
                startActivity(new Intent(this, KeystoreManagerActivity.class)));
        btnSign.setOnClickListener(v -> startSign());

        handleIncomingIntent(getIntent());
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

        // 恢复上次使用的配置
        String lastId = getPrefs().getString(PREF_LAST_CONFIG, null);
        if (lastId != null) {
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).id.equals(lastId)) {
                    spinnerKeystore.setSelection(i);
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
        createOutputLauncher.launch(outName);
    }

    private void onOutputCreated(Uri outputUri) {
        if (outputUri == null) return;
        KeystoreConfig config = configs.get(spinnerKeystore.getSelectedItemPosition());
        getPrefs().edit().putString(PREF_LAST_CONFIG, config.id).apply();

        setBusy(true);
        appendLog("开始签名: " + apkDisplayName + " → 密钥[" + config.name + "]");
        ApkSignTask.run(this, apkUri, config,
                cbV1.isChecked(), cbV2.isChecked(), cbV3.isChecked(),
                outputUri, new ApkSignTask.Callback() {
                    @Override
                    public void onProgress(String message) {
                        appendLog(message);
                    }

                    @Override
                    public void onSuccess(long elapsedMs) {
                        setBusy(false);
                        String msg = getString(R.string.sign_success, elapsedMs);
                        appendLog(msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(Throwable error) {
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

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        tvLog.append("[" + time + "] " + message + "\n");
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("apksign", MODE_PRIVATE);
    }
}
