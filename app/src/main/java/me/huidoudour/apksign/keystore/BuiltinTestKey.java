package me.huidoudour.apksign.keystore;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * 内置公开测试密钥（AOSP testkey）：
 * assets 中存放公开的 PKCS#8 私钥与 X.509 证书（来自 AOSP build/target/product/security），
 * 首次使用时合成 PKCS12 密钥库存入私有目录，任何用户都可开箱即用。
 * 该密钥材料完全公开，仅供测试签名，不适用于正式发布。
 */
public final class BuiltinTestKey {

    /** 内置配置的固定 ID（用于识别与防误删保护） */
    public static final String CONFIG_ID = "builtin-testkey";
    /** 私有目录内的密钥库文件名 */
    public static final String FILE_NAME = "builtin_testkey.p12";
    /** 密钥别名与密码（沿用 Android 公开测试密钥惯例） */
    public static final String ALIAS = "testkey";
    public static final String PASSWORD = "android";

    private static final String ASSET_PK8 = "testkey.pk8";
    private static final String ASSET_PEM = "testkey.x509.pem";

    private BuiltinTestKey() {
    }

    /** 确保私有目录内的内置密钥库文件存在，缺失时用 assets 材料重新合成 */
    public static void ensureFile(Context context, File dest) throws Exception {
        if (dest.exists() && dest.length() > 0) return;
        byte[] p12 = buildPkcs12(readAsset(context, ASSET_PK8),
                readAsset(context, ASSET_PEM), PASSWORD.toCharArray());
        try (OutputStream out = new FileOutputStream(dest)) {
            out.write(p12);
        }
    }

    /** 用 PKCS#8 私钥 + X.509 证书合成 PKCS12 密钥库字节 */
    public static byte[] buildPkcs12(byte[] pk8, byte[] pem, char[] password) throws Exception {
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(pk8));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem));
        KeyStore keyStore = KeyStore.getInstance(KeystoreHelper.TYPE_PKCS12);
        keyStore.load(null, null);
        keyStore.setKeyEntry(ALIAS, privateKey, password, new Certificate[]{certificate});
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        keyStore.store(buffer, password);
        return buffer.toByteArray();
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) buffer.write(buf, 0, n);
            return buffer.toByteArray();
        }
    }
}
