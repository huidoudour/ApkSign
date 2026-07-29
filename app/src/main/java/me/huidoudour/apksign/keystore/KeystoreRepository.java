package me.huidoudour.apksign.keystore;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 密钥配置持久化仓库：
 * - 配置列表保存为 filesDir/keystore_configs.json
 * - keystore 文件本体保存在 filesDir/keystores/ 下
 */
public class KeystoreRepository {

    private static final String CONFIG_FILE = "keystore_configs.json";
    private static final String KEYSTORE_DIR = "keystores";

    private final File configFile;
    private final File keystoreDir;

    public KeystoreRepository(Context context) {
        configFile = new File(context.getFilesDir(), CONFIG_FILE);
        keystoreDir = new File(context.getFilesDir(), KEYSTORE_DIR);
        if (!keystoreDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            keystoreDir.mkdirs();
        }
    }

    public File getKeystoreDir() {
        return keystoreDir;
    }

    public File getKeystoreFile(KeystoreConfig config) {
        return new File(keystoreDir, config.fileName);
    }

    public synchronized List<KeystoreConfig> loadAll() {
        List<KeystoreConfig> list = new ArrayList<>();
        if (!configFile.exists()) return list;
        try (FileInputStream in = new FileInputStream(configFile)) {
            byte[] buf = new byte[(int) configFile.length()];
            int read = in.read(buf);
            JSONArray arr = new JSONArray(new String(buf, 0, Math.max(read, 0), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                list.add(KeystoreConfig.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public synchronized void add(KeystoreConfig config) {
        List<KeystoreConfig> list = loadAll();
        list.add(config);
        saveAll(list);
    }

    /** 删除配置及其私有目录内的 keystore 文件 */
    public synchronized void delete(KeystoreConfig config) {
        List<KeystoreConfig> list = loadAll();
        List<KeystoreConfig> kept = new ArrayList<>();
        boolean fileStillUsed = false;
        for (KeystoreConfig c : list) {
            if (c.id.equals(config.id)) continue;
            kept.add(c);
            if (c.fileName.equals(config.fileName)) fileStillUsed = true;
        }
        saveAll(kept);
        if (!fileStillUsed) {
            File f = new File(keystoreDir, config.fileName);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    public KeystoreConfig findById(String id) {
        for (KeystoreConfig c : loadAll()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    /** 将外部 keystore 流导入到私有目录，返回私有目录内的文件名 */
    public String importKeystoreFile(InputStream source, String displayName) throws IOException {
        String safeName = System.currentTimeMillis() + "_" +
                (displayName == null ? "keystore" : displayName.replaceAll("[^A-Za-z0-9._-]", "_"));
        File dest = new File(keystoreDir, safeName);
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = source.read(buf)) > 0) out.write(buf, 0, n);
        }
        return safeName;
    }

    private void saveAll(List<KeystoreConfig> list) {
        try {
            JSONArray arr = new JSONArray();
            for (KeystoreConfig c : list) {
                JSONObject o = c.toJson();
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(configFile)) {
                out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
