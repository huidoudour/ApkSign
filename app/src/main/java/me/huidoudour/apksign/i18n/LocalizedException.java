package me.huidoudour.apksign.i18n;

import android.content.Context;

import androidx.annotation.StringRes;

import java.io.IOException;

/**
 * 携带字符串资源 id 的异常：底层（密钥库解析、签名任务）只抛出资源与参数，
 * 由界面层按当前语言解析文案，避免把中文文案硬编码在 Java 代码里。
 */
public class LocalizedException extends IOException {

    @StringRes
    private final int messageRes;
    private final Object[] formatArgs;

    public LocalizedException(@StringRes int messageRes, Object... formatArgs) {
        this(null, messageRes, formatArgs);
    }

    public LocalizedException(Throwable cause, @StringRes int messageRes, Object... formatArgs) {
        super("resource:" + messageRes, cause);
        this.messageRes = messageRes;
        this.formatArgs = formatArgs != null ? formatArgs : new Object[0];
    }

    /** 按当前语言解析出可展示的文案 */
    public String resolveMessage(Context context) {
        return context.getString(messageRes, formatArgs);
    }

    /** 统一的异常文案入口：本类型走资源，其余回退到原始 message */
    public static String describe(Context context, Throwable error) {
        if (error instanceof LocalizedException) {
            return ((LocalizedException) error).resolveMessage(context);
        }
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }
}
