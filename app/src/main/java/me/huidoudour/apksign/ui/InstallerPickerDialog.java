package me.huidoudour.apksign.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import me.huidoudour.apksign.R;

/**
 * 安装器选择对话框：列出系统中能安装 APK 的应用，选择后用其打开签名产物。
 * 列表项 UI 全部用 Java 代码构建，不使用 XML 布局。
 */
public class InstallerPickerDialog {

    private static final String APK_MIME = "application/vnd.android.package-archive";

    public static void show(Activity activity, Uri apkUri) {
        Intent probe = new Intent(Intent.ACTION_VIEW);
        probe.setDataAndType(apkUri, APK_MIME);

        PackageManager pm = activity.getPackageManager();
        List<ResolveInfo> resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);

        // 排除自己，避免出现"用本应用安装"的死循环入口
        List<ResolveInfo> installers = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (!activity.getPackageName().equals(info.activityInfo.packageName)) {
                installers.add(info);
            }
        }
        if (installers.isEmpty()) {
            Toast.makeText(activity, R.string.installer_none_found, Toast.LENGTH_LONG).show();
            return;
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.installer_dialog_title)
                .setAdapter(new InstallerAdapter(activity, installers), (dialog, which) -> {
                    ResolveInfo picked = installers.get(which);
                    Intent install = new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(apkUri, APK_MIME);
                    install.setClassName(picked.activityInfo.packageName, picked.activityInfo.name);
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        activity.startActivity(install);
                    } catch (Exception e) {
                        Toast.makeText(activity, R.string.installer_launch_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /** 列表项：图标 + 应用名 + 包名，视图纯代码构建 */
    private static class InstallerAdapter extends ArrayAdapter<ResolveInfo> {

        InstallerAdapter(Activity activity, List<ResolveInfo> installers) {
            super(activity, 0, installers);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            ItemViews item;
            if (convertView == null) {
                item = createItemViews(parent);
                convertView = item.root;
                convertView.setTag(item);
            } else {
                item = (ItemViews) convertView.getTag();
            }

            ResolveInfo info = getItem(position);
            PackageManager pm = getContext().getPackageManager();
            if (info != null) {
                item.icon.setImageDrawable(info.loadIcon(pm));
                item.label.setText(info.loadLabel(pm));
                item.packageName.setText(info.activityInfo.packageName);
            }
            return convertView;
        }

        private ItemViews createItemViews(ViewGroup parent) {
            Resources res = parent.getResources();
            int dp8 = dp(res, 8);
            int dp16 = dp(res, 16);

            LinearLayout root = new LinearLayout(getContext());
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp16, dp8 + dp8 / 2, dp16, dp8 + dp8 / 2);
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(getContext());
            root.addView(icon, new LinearLayout.LayoutParams(dp(res, 40), dp(res, 40)));

            LinearLayout textColumn = new LinearLayout(getContext());
            textColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp16);
            root.addView(textColumn, textParams);

            TextView label = new TextView(getContext());
            label.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            label.setSingleLine(true);
            textColumn.addView(label);

            TextView packageName = new TextView(getContext());
            packageName.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            packageName.setSingleLine(true);
            textColumn.addView(packageName);

            return new ItemViews(root, icon, label, packageName);
        }

        private static int dp(Resources res, int value) {
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, value, res.getDisplayMetrics());
        }
    }

    private static class ItemViews {
        final View root;
        final ImageView icon;
        final TextView label;
        final TextView packageName;

        ItemViews(View root, ImageView icon, TextView label, TextView packageName) {
            this.root = root;
            this.icon = icon;
            this.label = label;
            this.packageName = packageName;
        }
    }
}
