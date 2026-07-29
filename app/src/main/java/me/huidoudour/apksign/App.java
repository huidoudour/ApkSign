package me.huidoudour.apksign;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

public class App extends Application {

    static {
        // Android 内置的是阉割版 BC（缺少大量算法与 keystore 实现），
        // 移除后注册完整版，保证 JKS 解析、证书生成等功能可用
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Android 12+ 启用系统动态取色（Monet），低版本自动回退到主题内的蓝色配色
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
