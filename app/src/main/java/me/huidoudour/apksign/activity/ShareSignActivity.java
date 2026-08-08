package me.huidoudour.apksign.activity;

import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.huidoudour.apksign.R;
import me.huidoudour.apksign.keystore.KeystoreConfig;
import me.huidoudour.apksign.keystore.KeystoreRepository;
import me.huidoudour.apksign.signer.ApkSignTask;
import me.huidoudour.apksign.ui.InstallerPickerDialog;

/**
 * 分享入口的快速签名：透明 Activity 承接 ACTION_SEND，
 * 用一个简单对话框完成 选密钥 → 签名 → 安装，不进入主界面。
 * 产物固定写入 Download/ApkSign/，对话框 UI 全部用 Java 代码构建。
 */
public class ShareSignActivity extends AppCompatActivity {

    private static final String PREF_LAST_CONFIG = "last_config_id";

    private KeystoreRepository repository;
    private List<KeystoreConfig> configs = new ArrayList<>();

    private Uri apkUri;
    private String apkDisplayName;

    private AlertDialog dialog;
    private Spinner spinnerKeystore;
    private CheckBox cbV1, cbV2, cbV3;
    private LinearProgressIndicator progress;
    private TextView tvStatus;

    // 签名成功后的产物，供"安装"按钮使用
    private Uri signedOutputUri;
    private boolean signing;
    private boolean wentToManage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new KeystoreRepository(this);

        apkUri = Intent.ACTION_SEND.equals(getIntent().getAction())
                ? getIntent().getParcelableExtra(Intent.EXTRA_STREAM)
                : getIntent().getData();
        if (apkUri == null) {
            Toast.makeText(this, R.string.share_sign_read_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setupFlow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从密钥管理页回来后重新检查配置，有密钥了就直接进入签名对话框
        if (wentToManage) {
            wentToManage = false;
            if (dialog != null) {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
            }
            setupFlow();
        }
    }

    private void setupFlow() {
        configs = repository.loadAll();
        if (configs.isEmpty()) {
            dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.share_sign_title)
                    .setMessage(R.string.no_keystore_hint)
                    .setPositiveButton(R.string.btn_go_manage, (d, w) -> {
                        wentToManage = true;
                        startActivity(new Intent(this, KeystoreManagerActivity.class));
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .setOnDismissListener(d -> {
                        if (!wentToManage) finish();
                    })
                    .show();
            return;
        }
        showSignDialog();
    }

    private void showSignDialog() {
        dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_sign_title)
                .setView(buildContent())
                .setPositiveButton(R.string.btn_sign, null)
                .setNegativeButton(R.string.btn_cancel, null)
                .setOnDismissListener(d -> finish())
                .create();
        // 手动接管正按钮点击，避免点击后自动关闭对话框
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> startSign()));
        dialog.show();
    }

    /** 对话框内容：APK 信息 + 密钥选择 + 签名方案 + 进度/状态 */
    private View buildContent() {
        Resources res = getResources();
        int dp8 = dp(res, 8);
        int dp16 = dp(res, 16);
        int dp24 = dp(res, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp24, dp8, dp24, 0);

        // APK 名称与大小
        TextView tvApk = new TextView(this);
        tvApk.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        tvApk.setText(resolveApkInfo());
        root.addView(tvApk);

        // 密钥选择
        TextView tvKeyLabel = new TextView(this);
        tvKeyLabel.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        tvKeyLabel.setText(R.string.title_keystore);
        LinearLayout.LayoutParams labelParams = wrapParams();
        labelParams.topMargin = dp16;
        root.addView(tvKeyLabel, labelParams);

        spinnerKeystore = new Spinner(this);
        List<String> names = new ArrayList<>();
        for (KeystoreConfig c : configs) {
            names.add(c.name + " [" + c.keyAlias + "]");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerKeystore.setAdapter(adapter);
        // 默认选中上次使用的密钥
        String lastId = getPrefs().getString(PREF_LAST_CONFIG, null);
        if (lastId != null) {
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).id.equals(lastId)) {
                    spinnerKeystore.setSelection(i, false);
                    break;
                }
            }
        }
        root.addView(spinnerKeystore, wrapParams());

        // 签名方案（与主页默认一致：全选）
        LinearLayout schemes = new LinearLayout(this);
        schemes.setOrientation(LinearLayout.HORIZONTAL);
        cbV1 = newSchemeCheckBox(R.string.scheme_v1_short);
        cbV2 = newSchemeCheckBox(R.string.scheme_v2);
        cbV3 = newSchemeCheckBox(R.string.scheme_v3);
        schemes.addView(cbV1);
        schemes.addView(cbV2);
        schemes.addView(cbV3);
        LinearLayout.LayoutParams schemeParams = wrapParams();
        schemeParams.topMargin = dp8;
        root.addView(schemes, schemeParams);

        // 进度与状态（签名开始后可见）
        progress = new LinearProgressIndicator(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = wrapParams();
        progressParams.topMargin = dp16;
        root.addView(progress, progressParams);

        tvStatus = new TextView(this);
        tvStatus.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        tvStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = wrapParams();
        statusParams.topMargin = dp8;
        root.addView(tvStatus, statusParams);

        return root;
    }

    private String resolveApkInfo() {
        long size = -1;
        String name = null;
        try (Cursor c = getContentResolver().query(apkUri, null, null, null, null)) {
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
        return info;
    }

    /** 分享场景拿不到原目录写权限，产物统一写入 Download/ApkSign/ */
    private void startSign() {
        if (!cbV1.isChecked() && !cbV2.isChecked() && !cbV3.isChecked()) {
            Toast.makeText(this, R.string.toast_select_scheme_first, Toast.LENGTH_SHORT).show();
            return;
        }
        KeystoreConfig config = configs.get(spinnerKeystore.getSelectedItemPosition());
        getPrefs().edit().putString(PREF_LAST_CONFIG, config.id).apply();

        String outName = apkDisplayName.endsWith(".apk")
                ? apkDisplayName.substring(0, apkDisplayName.length() - 4) + "-signed.apk"
                : apkDisplayName + "-signed.apk";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, outName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ApkSign");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri outputUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            // API 29 部分设备（华为 EMUI 10）不支持 RELATIVE_PATH 子目录，降级到 Download 根目录
            values.remove(MediaStore.Downloads.RELATIVE_PATH);
            values.put(MediaStore.Downloads.DISPLAY_NAME, "ApkSign_" + outName);
            outputUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        }
        if (outputUri == null) {
            Toast.makeText(this, R.string.output_create_failed, Toast.LENGTH_LONG).show();
            return;
        }
        final Uri finalUri = outputUri;
        String locationLabel = finalUri.getLastPathSegment() != null
                ? Environment.DIRECTORY_DOWNLOADS + "/" + finalUri.getLastPathSegment()
                : Environment.DIRECTORY_DOWNLOADS + "/ApkSign/" + outName;

        setBusy(true);
        ApkSignTask.run(this, apkUri, config,
                cbV1.isChecked(), cbV2.isChecked(), cbV3.isChecked(),
                finalUri, null, new ApkSignTask.Callback() {
                    @Override
                    public void onProgress(String message) {
                        tvStatus.setText(message);
                    }

                    @Override
                    public void onSuccess(long elapsedMs) {
                        // 清除 IS_PENDING，让文件对其他应用可见
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(finalUri, done, null, null);
                        signedOutputUri = finalUri;
                        setBusy(false);
                        tvStatus.setText(getString(R.string.sign_success, elapsedMs) + "\n"
                                + getString(R.string.sign_output_path, locationLabel));
                        onSignFinished(true);
                    }

                    @Override
                    public void onError(Throwable error) {
                        // 失败时清理占位的输出条目
                        try {
                            getContentResolver().delete(finalUri, null, null);
                        } catch (Exception ignored) {
                        }
                        setBusy(false);
                        tvStatus.setText(getString(R.string.sign_failed,
                                error.getMessage() != null ? error.getMessage() : error.toString()));
                        onSignFinished(false);
                    }
                });
    }

    private void setBusy(boolean busy) {
        signing = busy;
        dialog.setCancelable(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvStatus.setVisibility(View.VISIBLE);
        spinnerKeystore.setEnabled(!busy);
        cbV1.setEnabled(!busy);
        cbV2.setEnabled(!busy);
        cbV3.setEnabled(!busy);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
    }

    /** 结束态：成功后右侧正按钮变"完成"、左侧负按钮变"安装"；失败保持原按钮可重试 */
    private void onSignFinished(boolean success) {
        if (!success) return;
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setText(R.string.btn_done);
        positive.setOnClickListener(v -> dialog.dismiss());
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        negative.setText(R.string.btn_install);
        // 接管点击，避免默认行为关闭对话框
        negative.setOnClickListener(v -> InstallerPickerDialog.show(this, signedOutputUri));
    }

    private CheckBox newSchemeCheckBox(int textRes) {
        CheckBox cb = new CheckBox(this);
        cb.setText(textRes);
        cb.setChecked(true);
        return cb;
    }

    private static LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Resources res, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, res.getDisplayMetrics());
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("apksign", MODE_PRIVATE);
    }
}
