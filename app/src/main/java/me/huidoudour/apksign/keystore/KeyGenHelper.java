package me.huidoudour.apksign.keystore;

import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;

/**
 * 应用内生成自签名密钥：密钥对 + 自签名 X.509 证书，保存为 PKCS12 格式。
 */
public class KeyGenHelper {

    public static final String ALG_RSA_2048 = "RSA 2048";
    public static final String ALG_RSA_4096 = "RSA 4096";
    public static final String ALG_EC_P256 = "EC P-256";

    /**
     * 生成密钥并落盘到私有目录，返回私有目录内的文件名。
     *
     * @param algorithm     ALG_* 常量之一
     * @param cn            证书主体 CN（必填）
     * @param o             组织 O（可空）
     * @param ou            组织单元 OU（可空）
     * @param validityYears 证书有效期（年）
     */
    public static String generate(File keystoreDir, String alias, char[] password,
                                  String algorithm, String cn, String o, String ou,
                                  int validityYears) throws Exception {
        // 1. 密钥对
        KeyPairGenerator kpg;
        String sigAlg;
        switch (algorithm) {
            case ALG_EC_P256:
                kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec("secp256r1"));
                sigAlg = "SHA256withECDSA";
                break;
            case ALG_RSA_4096:
                kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(4096);
                sigAlg = "SHA256withRSA";
                break;
            case ALG_RSA_2048:
            default:
                kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                sigAlg = "SHA256withRSA";
                break;
        }
        KeyPair keyPair = kpg.generateKeyPair();

        // 2. 自签名证书
        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, cn);
        if (o != null && !o.isEmpty()) nameBuilder.addRDN(BCStyle.O, o);
        if (ou != null && !ou.isEmpty()) nameBuilder.addRDN(BCStyle.OU, ou);
        org.bouncycastle.asn1.x500.X500Name subject = nameBuilder.build();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 3600 * 1000);
        Date notAfter = new Date(now + validityYears * 365L * 24 * 3600 * 1000);
        BigInteger serial = new BigInteger(64, new SecureRandom()).abs().setBit(0);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder(sigAlg).build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        // 3. 存为 PKCS12
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, keyPair.getPrivate(), password, new Certificate[]{cert});
        String fileName = System.currentTimeMillis() + "_" +
                alias.replaceAll("[^A-Za-z0-9._-]", "_") + ".p12";
        File dest = new File(keystoreDir, fileName);
        try (FileOutputStream out = new FileOutputStream(dest)) {
            ks.store(out, password);
        }
        return fileName;
    }
}
