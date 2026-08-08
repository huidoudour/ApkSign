package me.huidoudour.apksign.keystore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 统一的密钥库加载入口，屏蔽 PKCS12 / BKS / JKS 三种格式差异。
 */
public class KeystoreHelper {

    public static final String TYPE_PKCS12 = "PKCS12";
    public static final String TYPE_BKS = "BKS";
    public static final String TYPE_JKS = "JKS";

    /** 签名所需的私钥 + 证书链 */
    public static class SignerIdentity {
        public final PrivateKey privateKey;
        public final List<X509Certificate> certificates;

        public SignerIdentity(PrivateKey privateKey, List<X509Certificate> certificates) {
            this.privateKey = privateKey;
            this.certificates = certificates;
        }
    }

    /** 已加载的密钥库（keyStore 与 jks 二选一非空） */
    public static class Loaded {
        public final String type;
        public final KeyStore keyStore;
        public final JksKeyStoreReader jks;

        Loaded(String type, KeyStore keyStore, JksKeyStoreReader jks) {
            this.type = type;
            this.keyStore = keyStore;
            this.jks = jks;
        }

        /** 所有私钥条目的别名 */
        public List<String> keyAliases() throws Exception {
            if (jks != null) return jks.keyAliases();
            List<String> list = new ArrayList<>();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) list.add(alias);
            }
            return list;
        }

        public SignerIdentity getSigner(String alias, char[] keyPassword) throws Exception {
            if (jks != null) {
                PrivateKey key = jks.getPrivateKey(alias, keyPassword);
                List<X509Certificate> chain = jks.getCertificateChain(alias);
                if (chain == null || chain.isEmpty()) {
                    throw new IOException("条目缺少证书链: " + alias);
                }
                return new SignerIdentity(key, chain);
            }
            PrivateKey key = (PrivateKey) keyStore.getKey(alias, keyPassword);
            if (key == null) throw new IOException("别名不存在或不是私钥条目: " + alias);
            Certificate[] certs = keyStore.getCertificateChain(alias);
            if (certs == null || certs.length == 0) {
                throw new IOException("条目缺少证书链: " + alias);
            }
            List<X509Certificate> chain = new ArrayList<>(certs.length);
            for (Certificate c : certs) chain.add((X509Certificate) c);
            return new SignerIdentity(key, chain);
        }
    }

    /**
     * 按文件头特征与逐一尝试的方式加载密钥库。
     * 密码错误、格式不支持时抛出带可读信息的异常。
     */
    public static Loaded load(File file, char[] storePassword) throws Exception {
        byte[] data = readAll(file);
        if (data.length < 4) throw new IOException("文件太小，不是有效的密钥库");

        int magic = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (magic == 0xFEEDFEED || magic == 0xCECECECE) {
            return new Loaded(TYPE_JKS, null, JksKeyStoreReader.load(data, storePassword));
        }

        Exception lastError = null;

        // PKCS12：Android 内置支持，优先使用系统 provider（避免 bcprov-jdk18on 的 intValueExact 兼容问题）
        try {
            KeyStore ks = loadPkcs12(file, storePassword);
            if (ks != null) return new Loaded(TYPE_PKCS12, ks, null);
        } catch (IOException e) {
            // PKCS12 抛出的 IO 异常（密码错误 / BC 不兼容）是明确的，直接向上报告
            throw e;
        } catch (Exception e) {
            // 非 IO 异常（如 NoSuchAlgorithmException），可能是格式不对，继续尝试 BKS
            lastError = e;
        }

        // BKS：需要 Bouncy Castle，显式指定 provider
        try {
            KeyStore ks = KeyStore.getInstance(TYPE_BKS, "BC");
            try (InputStream in = new FileInputStream(file)) {
                ks.load(in, storePassword);
            }
            return new Loaded(TYPE_BKS, ks, null);
        } catch (Exception | NoSuchMethodError e) {
            lastError = (e instanceof Exception) ? (Exception) e : new Exception(e);
        }

        throw new IOException("无法加载密钥库（格式不支持或密码错误）: "
                + (lastError != null ? lastError.getMessage() : ""), lastError);
    }

    /**
     * 加载 PKCS12 密钥库。
     * 使用 jdk15to18 版 BC（编译目标 Java 1.5，无 intValueExact 兼容问题），
     * 保留 Android 内置 BC 反射回退。
     */
    private static KeyStore loadPkcs12(File file, char[] password) throws Exception {
        // 1. 外部 BC provider（jdk15to18，全面兼容 API 29）
        try {
            KeyStore ks = KeyStore.getInstance(TYPE_PKCS12, "BC");
            try (InputStream in = new FileInputStream(file)) {
                ks.load(in, password);
            }
            return ks;
        } catch (NoSuchMethodError e) {
            // jdk15to18 不应触发此路径，但保留回退以防万一
        }

        // 2. 反射加载 Android 内置 BC provider
        try {
            Class<?> c = Class.forName("com.android.org.bouncycastle.jce.provider.BouncyCastleProvider");
            Provider androidBc = (Provider) c.getDeclaredConstructor().newInstance();
            KeyStore ks = KeyStore.getInstance(TYPE_PKCS12, androidBc);
            try (InputStream in = new FileInputStream(file)) {
                ks.load(in, password);
            }
            return ks;
        } catch (IOException e) {
            throw e; // 密码错误直接抛出
        } catch (Exception e) {
            // Android 内置 BC 也不可用
        }

        // 3. 系统默认 provider（最后尝试）
        KeyStore ks = KeyStore.getInstance(TYPE_PKCS12);
        try (InputStream in = new FileInputStream(file)) {
            ks.load(in, password);
        }
        return ks;
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return data;
    }
}
