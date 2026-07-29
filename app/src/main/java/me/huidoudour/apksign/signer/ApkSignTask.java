package me.huidoudour.apksign.signer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.huidoudour.apksign.keystore.KeystoreConfig;
import me.huidoudour.apksign.keystore.KeystoreHelper;
import me.huidoudour.apksign.keystore.KeystoreRepository;

/**
 * 后台执行 APK 签名：
 * 输入 Uri → cache 临时文件 → apksig 签名 → 写出到输出 Uri 或目标文件。
 */
public class ApkSignTask {

    public interface Callback {
        void onProgress(String message);

        void onSuccess(long elapsedMs);

        void onError(Throwable error);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * @param outputUri  输出目标（MediaStore/SAF），与 outputFile 二选一
     * @param outputFile 输出目标（直接文件路径，需已持有存储权限）
     */
    public static void run(Context context, Uri apkUri, KeystoreConfig config,
                           boolean v1, boolean v2, boolean v3,
                           Uri outputUri, File outputFile, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            long start = System.currentTimeMillis();
            File inFile = new File(app.getCacheDir(), "sign_in_" + start + ".apk");
            File outFile = new File(app.getCacheDir(), "sign_out_" + start + ".apk");
            try {
                // 1. 拷贝输入 APK 到临时文件
                post(callback, "正在读取 APK...");
                try (InputStream in = app.getContentResolver().openInputStream(apkUri);
                     OutputStream out = new java.io.FileOutputStream(inFile)) {
                    if (in == null) throw new java.io.IOException("无法读取所选 APK");
                    copy(in, out);
                }

                // 2. 加载密钥
                post(callback, "正在加载密钥库...");
                KeystoreRepository repo = new KeystoreRepository(app);
                File ksFile = repo.getKeystoreFile(config);
                if (!ksFile.exists()) throw new java.io.IOException("密钥库文件丢失，请重新导入");
                KeystoreHelper.Loaded loaded =
                        KeystoreHelper.load(ksFile, config.storePassword.toCharArray());
                KeystoreHelper.SignerIdentity identity =
                        loaded.getSigner(config.keyAlias, config.keyPassword.toCharArray());

                // 3. 签名（apksig 会自动处理 zip 对齐与摘要计算）
                post(callback, "正在签名 (v1=" + v1 + ", v2=" + v2 + ", v3=" + v3 + ")...");
                ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                        config.keyAlias, identity.privateKey, identity.certificates).build();
                ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                        .setInputApk(inFile)
                        .setOutputApk(outFile)
                        .setV1SigningEnabled(v1)
                        .setV2SigningEnabled(v2)
                        .setV3SigningEnabled(v3)
                        .setV4SigningEnabled(false)
                        .build();
                signer.sign();

                // 4. 写出结果
                post(callback, "正在保存输出文件...");
                try (InputStream in = new FileInputStream(outFile);
                     OutputStream out = outputFile != null
                             ? new java.io.FileOutputStream(outputFile)
                             : app.getContentResolver().openOutputStream(outputUri, "wt")) {
                    if (out == null) throw new java.io.IOException("无法写入输出文件");
                    copy(in, out);
                }

                long elapsed = System.currentTimeMillis() - start;
                MAIN.post(() -> callback.onSuccess(elapsed));
            } catch (Throwable t) {
                MAIN.post(() -> callback.onError(t));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                inFile.delete();
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
        });
    }

    private static void post(Callback callback, String message) {
        MAIN.post(() -> callback.onProgress(message));
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
