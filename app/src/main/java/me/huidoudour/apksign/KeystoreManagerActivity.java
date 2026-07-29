package me.huidoudour.apksign;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.huidoudour.apksign.keystore.KeyGenHelper;
import me.huidoudour.apksign.keystore.KeystoreConfig;
import me.huidoudour.apksign.keystore.KeystoreHelper;
import me.huidoudour.apksign.keystore.KeystoreRepository;

public class KeystoreManagerActivity extends AppCompatActivity {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private KeystoreRepository repository;
    private final List<KeystoreConfig> configs = new ArrayList<>();
    private ConfigAdapter adapter;
    private TextView tvEmpty;

    // 导入对话框状态
    private File pendingImportFile;          // 已拷贝到 cache 的待导入文件
    private String pendingImportName;        // 原始文件名
    private TextView tvImportFileName;
    private ActivityResultLauncher<String[]> pickKeystoreLauncher;

    // 导出状态
    private KeystoreConfig pendingExportConfig;
    private ActivityResultLauncher<String> exportLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_keystore_manager);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.keystore_manager_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        repository = new KeystoreRepository(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_empty);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConfigAdapter();
        recycler.setAdapter(adapter);

        pickKeystoreLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onKeystoreFilePicked);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                this::onExportTargetCreated);

        findViewById(R.id.fab_add).setOnClickListener(v -> showAddChoiceDialog());

        refresh();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refresh() {
        configs.clear();
        configs.addAll(repository.loadAll());
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(configs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showAddChoiceDialog() {
        String[] items = {getString(R.string.action_import), getString(R.string.action_generate)};
        new MaterialAlertDialogBuilder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) showImportDialog();
                    else showGenerateDialog();
                })
                .show();
    }

    // ─────────────────────────── 导入 ───────────────────────────

    private void showImportDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_import_keystore, null);
        tvImportFileName = view.findViewById(R.id.tv_file_name);
        EditText etStorePassword = view.findViewById(R.id.et_store_password);
        EditText etKeyPassword = view.findViewById(R.id.et_key_password);
        EditText etConfigName = view.findViewById(R.id.et_config_name);
        TextView tvLoadResult = view.findViewById(R.id.tv_load_result);
        Spinner spinnerAlias = view.findViewById(R.id.spinner_alias);

        pendingImportFile = null;
        pendingImportName = null;

        view.findViewById(R.id.btn_pick_file).setOnClickListener(v ->
                pickKeystoreLauncher.launch(new String[]{"*/*"}));

        final KeystoreHelper.Loaded[] loadedHolder = new KeystoreHelper.Loaded[1];
        view.findViewById(R.id.btn_load_aliases).setOnClickListener(v -> {
            if (pendingImportFile == null) {
                tvLoadResult.setText(getString(R.string.import_load_failed, getString(R.string.btn_pick_keystore)));
                return;
            }
            try {
                KeystoreHelper.Loaded loaded = KeystoreHelper.load(
                        pendingImportFile, etStorePassword.getText().toString().toCharArray());
                List<String> aliases = loaded.keyAliases();
                if (aliases.isEmpty()) {
                    tvLoadResult.setText(R.string.import_no_key_alias);
                    return;
                }
                loadedHolder[0] = loaded;
                ArrayAdapter<String> aliasAdapter = new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_item, aliases);
                aliasAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerAlias.setAdapter(aliasAdapter);
                tvLoadResult.setText(getString(R.string.import_load_success, loaded.type));
            } catch (Exception e) {
                loadedHolder[0] = null;
                tvLoadResult.setText(getString(R.string.import_load_failed, briefMessage(e)));
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_import_title)
                .setView(view)
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_cancel, null)
                .create();
        dialog.show();
        // 手动接管确定按钮，校验失败时不关闭对话框
        dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            KeystoreHelper.Loaded loaded = loadedHolder[0];
            Object selectedAlias = spinnerAlias.getSelectedItem();
            String configName = etConfigName.getText().toString().trim();
            if (loaded == null || selectedAlias == null) {
                tvLoadResult.setText(getString(R.string.import_load_failed, getString(R.string.btn_load_aliases)));
                return;
            }
            if (configName.isEmpty()) {
                etConfigName.setError(getString(R.string.field_required));
                return;
            }
            String alias = selectedAlias.toString();
            String keyPassword = etKeyPassword.getText().toString();
            try {
                // 校验别名密码能取出私钥
                loaded.getSigner(alias, keyPassword.toCharArray());
                String fileName;
                try (InputStream in = new FileInputStream(pendingImportFile)) {
                    fileName = repository.importKeystoreFile(in, pendingImportName);
                }
                repository.add(KeystoreConfig.create(configName, fileName, loaded.type,
                        etStorePassword.getText().toString(), alias, keyPassword));
                Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show();
                refresh();
                dialog.dismiss();
            } catch (Exception e) {
                tvLoadResult.setText(getString(R.string.import_verify_failed, briefMessage(e)));
            }
        });
    }

    private void onKeystoreFilePicked(Uri uri) {
        if (uri == null) return;
        try {
            String name = queryDisplayName(uri);
            File temp = new File(getCacheDir(), "import_" + System.currentTimeMillis());
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temp)) {
                if (in == null) throw new java.io.IOException("openInputStream failed");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            pendingImportFile = temp;
            pendingImportName = name;
            if (tvImportFileName != null) tvImportFileName.setText(name);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_load_failed, briefMessage(e)),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ─────────────────────────── 生成 ───────────────────────────

    private void showGenerateDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_generate_key, null);
        EditText etConfigName = view.findViewById(R.id.et_gen_config_name);
        EditText etAlias = view.findViewById(R.id.et_gen_alias);
        EditText etPassword = view.findViewById(R.id.et_gen_password);
        EditText etCn = view.findViewById(R.id.et_gen_cn);
        EditText etO = view.findViewById(R.id.et_gen_o);
        EditText etOu = view.findViewById(R.id.et_gen_ou);
        EditText etValidity = view.findViewById(R.id.et_gen_validity);
        Spinner spinnerAlgorithm = view.findViewById(R.id.spinner_algorithm);

        String[] algorithms = {KeyGenHelper.ALG_RSA_2048, KeyGenHelper.ALG_RSA_4096, KeyGenHelper.ALG_EC_P256};
        ArrayAdapter<String> algAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, algorithms);
        algAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAlgorithm.setAdapter(algAdapter);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_generate_title)
                .setView(view)
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_cancel, null)
                .create();
        dialog.show();
        dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String configName = etConfigName.getText().toString().trim();
            String alias = etAlias.getText().toString().trim();
            String password = etPassword.getText().toString();
            String cn = etCn.getText().toString().trim();
            if (requireField(etConfigName, configName) | requireField(etAlias, alias)
                    | requireField(etPassword, password) | requireField(etCn, cn)) {
                return;
            }
            int validity;
            try {
                validity = Integer.parseInt(etValidity.getText().toString().trim());
                if (validity <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                etValidity.setError(getString(R.string.field_required));
                return;
            }
            String algorithm = (String) spinnerAlgorithm.getSelectedItem();
            String o = etO.getText().toString().trim();
            String ou = etOu.getText().toString().trim();

            Toast.makeText(this, R.string.generating, Toast.LENGTH_SHORT).show();
            dialog.getButton(Dialog.BUTTON_POSITIVE).setEnabled(false);
            EXECUTOR.execute(() -> {
                try {
                    String fileName = KeyGenHelper.generate(repository.getKeystoreDir(),
                            alias, password.toCharArray(), algorithm, cn, o, ou, validity);
                    repository.add(KeystoreConfig.create(configName, fileName,
                            KeystoreHelper.TYPE_PKCS12, password, alias, password));
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.generate_success, Toast.LENGTH_SHORT).show();
                        refresh();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        dialog.getButton(Dialog.BUTTON_POSITIVE).setEnabled(true);
                        Toast.makeText(this, getString(R.string.generate_failed, briefMessage(e)),
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }

    private boolean requireField(EditText field, String value) {
        if (value.isEmpty()) {
            field.setError(getString(R.string.field_required));
            return true;
        }
        return false;
    }

    // ─────────────────────────── 导出 / 删除 ───────────────────────────

    private void onExportTargetCreated(Uri uri) {
        if (uri == null || pendingExportConfig == null) return;
        try (InputStream in = new FileInputStream(repository.getKeystoreFile(pendingExportConfig));
             OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new java.io.IOException("openOutputStream failed");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.export_failed, briefMessage(e)),
                    Toast.LENGTH_LONG).show();
        } finally {
            pendingExportConfig = null;
        }
    }

    private void confirmDelete(KeystoreConfig config) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_msg, config.name))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    repository.delete(config);
                    refresh();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private static String briefMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return "keystore";
    }

    // ─────────────────────────── 列表 ───────────────────────────

    private class ConfigAdapter extends RecyclerView.Adapter<ConfigHolder> {

        @NonNull
        @Override
        public ConfigHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ConfigHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_keystore, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ConfigHolder holder, int position) {
            KeystoreConfig config = configs.get(position);
            holder.tvName.setText(config.name);
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(config.createdAt));
            holder.tvDetail.setText(config.type + " | " + config.keyAlias + " | " + date);
            holder.btnExport.setOnClickListener(v -> {
                pendingExportConfig = config;
                exportLauncher.launch(config.fileName);
            });
            holder.btnDelete.setOnClickListener(v -> confirmDelete(config));
        }

        @Override
        public int getItemCount() {
            return configs.size();
        }
    }

    private static class ConfigHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvDetail;
        final Button btnExport;
        final Button btnDelete;

        ConfigHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_name);
            tvDetail = itemView.findViewById(R.id.tv_detail);
            btnExport = itemView.findViewById(R.id.btn_export);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
