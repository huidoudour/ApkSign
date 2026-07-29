package me.huidoudour.apksign.keystore;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 一条已保存的签名密钥配置。
 * keystore 文件本体存放在应用私有目录 (filesDir/keystores/) 内，这里只记录文件名。
 */
public class KeystoreConfig {

    public String id;
    public String name;          // 配置显示名
    public String fileName;      // 私有目录内的 keystore 文件名
    public String type;          // PKCS12 / BKS / JKS
    public String storePassword; // 密钥库密码
    public String keyAlias;      // 密钥别名
    public String keyPassword;   // 别名密码
    public long createdAt;

    public static KeystoreConfig create(String name, String fileName, String type,
                                        String storePassword, String keyAlias, String keyPassword) {
        KeystoreConfig c = new KeystoreConfig();
        c.id = UUID.randomUUID().toString();
        c.name = name;
        c.fileName = fileName;
        c.type = type;
        c.storePassword = storePassword;
        c.keyAlias = keyAlias;
        c.keyPassword = keyPassword;
        c.createdAt = System.currentTimeMillis();
        return c;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("fileName", fileName);
        o.put("type", type);
        o.put("storePassword", storePassword);
        o.put("keyAlias", keyAlias);
        o.put("keyPassword", keyPassword);
        o.put("createdAt", createdAt);
        return o;
    }

    public static KeystoreConfig fromJson(JSONObject o) throws JSONException {
        KeystoreConfig c = new KeystoreConfig();
        c.id = o.getString("id");
        c.name = o.getString("name");
        c.fileName = o.getString("fileName");
        c.type = o.getString("type");
        c.storePassword = o.getString("storePassword");
        c.keyAlias = o.getString("keyAlias");
        c.keyPassword = o.getString("keyPassword");
        c.createdAt = o.optLong("createdAt");
        return c;
    }
}
