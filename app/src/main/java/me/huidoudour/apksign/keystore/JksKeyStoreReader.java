package me.huidoudour.apksign.keystore;

import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java 实现的 JKS 密钥库读取器（只读）。
 * <p>
 * Android 平台没有内置 JKS KeyStore 实现，BouncyCastle 的 JKS 支持又只能读证书条目
 * （私钥条目直接抛 "BC JKS store is read-only and only supports certificate entries"），
 * 因此这里按公开的 JKS 文件格式与 Sun KeyProtector 算法自行解析。
 */
public class JksKeyStoreReader {

    private static final int MAGIC_JKS = 0xFEEDFEED;
    private static final int MAGIC_JCEKS = 0xCECECECE;
    /** Sun 专有 KeyProtector 算法 OID */
    private static final String SUN_KEY_PROTECTOR_OID = "1.3.6.1.4.1.42.2.17.1.1";
    private static final byte[] MIGHTY_APHRODITE = "Mighty Aphrodite".getBytes();

    private static class Entry {
        byte[] protectedKey;            // 私钥条目：EncryptedPrivateKeyInfo DER
        List<X509Certificate> chain;    // 证书链（私钥条目）或单证书（信任条目）
        boolean isKeyEntry;
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * 解析 JKS 文件并校验完整性摘要（同时验证了库密码）。
     */
    public static JksKeyStoreReader load(byte[] data, char[] storePassword) throws IOException {
        JksKeyStoreReader reader = new JksKeyStoreReader();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int magic = in.readInt();
        if (magic == MAGIC_JCEKS) {
            throw new IOException("暂不支持 JCEKS 格式，请先用 keytool 转换为 PKCS12");
        }
        if (magic != MAGIC_JKS) {
            throw new IOException("不是有效的 JKS 密钥库文件");
        }
        int version = in.readInt();
        if (version != 1 && version != 2) {
            throw new IOException("不支持的 JKS 版本: " + version);
        }
        CertificateFactory cf;
        try {
            cf = CertificateFactory.getInstance("X.509");
        } catch (Exception e) {
            throw new IOException(e);
        }

        int count = in.readInt();
        try {
            for (int i = 0; i < count; i++) {
                int tag = in.readInt();
                Entry entry = new Entry();
                String alias;
                if (tag == 1) { // 私钥条目
                    entry.isKeyEntry = true;
                    alias = in.readUTF();
                    in.readLong(); // timestamp
                    entry.protectedKey = new byte[in.readInt()];
                    in.readFully(entry.protectedKey);
                    int chainLen = in.readInt();
                    entry.chain = new ArrayList<>(chainLen);
                    for (int j = 0; j < chainLen; j++) {
                        entry.chain.add(readCert(in, cf, version));
                    }
                } else if (tag == 2) { // 信任证书条目
                    alias = in.readUTF();
                    in.readLong();
                    entry.chain = new ArrayList<>(1);
                    entry.chain.add(readCert(in, cf, version));
                } else {
                    throw new IOException("JKS 条目类型无法识别: " + tag);
                }
                reader.entries.put(alias, entry);
            }
        } catch (java.security.cert.CertificateException e) {
            throw new IOException("解析证书失败: " + e.getMessage(), e);
        }

        // 尾部 20 字节为 SHA1(password_utf16be + "Mighty Aphrodite" + 前面全部内容)
        if (storePassword != null) {
            byte[] actual = new byte[20];
            in.readFully(actual);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                md.update(passwordBytes(storePassword));
                md.update(MIGHTY_APHRODITE);
                md.update(data, 0, data.length - 20);
                if (!MessageDigest.isEqual(md.digest(), actual)) {
                    throw new IOException("密钥库密码错误或文件已损坏");
                }
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        }
        return reader;
    }

    private static X509Certificate readCert(DataInputStream in, CertificateFactory cf, int version)
            throws IOException, java.security.cert.CertificateException {
        if (version == 2) {
            in.readUTF(); // 证书类型，恒为 "X.509"
        }
        byte[] certData = new byte[in.readInt()];
        in.readFully(certData);
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certData));
    }

    public List<String> keyAliases() {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().isKeyEntry) list.add(e.getKey());
        }
        return list;
    }

    public List<X509Certificate> getCertificateChain(String alias) {
        Entry e = entries.get(alias);
        return e == null ? null : e.chain;
    }

    /**
     * 用 Sun KeyProtector 算法解密私钥（SHA-1 XOR 密钥流）。
     */
    public PrivateKey getPrivateKey(String alias, char[] keyPassword) throws Exception {
        Entry e = entries.get(alias);
        if (e == null || !e.isKeyEntry) {
            throw new UnrecoverableKeyException("别名不存在或不是私钥条目: " + alias);
        }
        EncryptedPrivateKeyInfo epki = EncryptedPrivateKeyInfo.getInstance(e.protectedKey);
        String algOid = epki.getEncryptionAlgorithm().getAlgorithm().getId();
        if (!SUN_KEY_PROTECTOR_OID.equals(algOid)) {
            throw new UnrecoverableKeyException("不支持的私钥保护算法: " + algOid);
        }
        byte[] data = epki.getEncryptedData();
        if (data.length < 40) throw new UnrecoverableKeyException("私钥数据损坏");

        byte[] passwd = passwordBytes(keyPassword);
        byte[] salt = new byte[20];
        System.arraycopy(data, 0, salt, 0, 20);
        int encLen = data.length - 40;
        byte[] plain = new byte[encLen];

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = salt;
        for (int pos = 0; pos < encLen; pos += 20) {
            md.update(passwd);
            md.update(digest);
            digest = md.digest();
            for (int i = 0; i < 20 && pos + i < encLen; i++) {
                plain[pos + i] = (byte) (data[20 + pos + i] ^ digest[i]);
            }
        }

        // 校验 SHA1(password + plainKey) == 尾部 20 字节
        md.reset();
        md.update(passwd);
        md.update(plain);
        byte[] check = md.digest();
        for (int i = 0; i < 20; i++) {
            if (check[i] != data[data.length - 20 + i]) {
                throw new UnrecoverableKeyException("别名密码错误");
            }
        }

        PrivateKeyInfo pki = PrivateKeyInfo.getInstance(plain);
        String keyAlg = keyAlgorithmName(pki.getPrivateKeyAlgorithm().getAlgorithm().getId());
        return KeyFactory.getInstance(keyAlg).generatePrivate(new PKCS8EncodedKeySpec(plain));
    }

    private static String keyAlgorithmName(String oid) {
        switch (oid) {
            case "1.2.840.113549.1.1.1":
                return "RSA";
            case "1.2.840.10045.2.1":
                return "EC";
            case "1.2.840.10040.4.1":
                return "DSA";
            default:
                return "RSA";
        }
    }

    /** JKS 口令按 UTF-16BE 编码参与摘要 */
    private static byte[] passwordBytes(char[] password) {
        byte[] bytes = new byte[password.length * 2];
        for (int i = 0; i < password.length; i++) {
            bytes[i * 2] = (byte) (password[i] >> 8);
            bytes[i * 2 + 1] = (byte) password[i];
        }
        return bytes;
    }
}
