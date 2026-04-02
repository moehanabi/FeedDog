package com.hanabi.feeddog;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import org.json.JSONArray;
import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final Uri RULES_URI = Uri.parse("content://com.hanabi.feeddog.rules/rules");

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.hanabi.feeddog")) {
            return;
        }

        final String currentPkg = lpparam.packageName;

        // 1. Hook onResume()
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    applyRules((Activity) param.thisObject, currentPkg);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("FeedDog: onResume Hook Exception - " + t.getMessage());
        }

        // 2. Hook onCreate() 以捕捉早期渲染 (解决小红书初次加载时有短暂显示的bug)
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    applyRules((Activity) param.thisObject, currentPkg);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("FeedDog: onCreate Hook Exception - " + t.getMessage());
        }
    }

    // 通过 ContentProvider 读取规则并应用到当前 Activity
    private void applyRules(Activity activity, String currentPkg) {
        try {
            JSONArray rules = queryRules(activity);
            String activityName = activity.getClass().getName();

            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                if (!rule.optBoolean("enabled", true)) {
                    continue;
                }
                if (currentPkg.equals(rule.optString("pkg"))
                        && activityName.equals(rule.optString("act"))) {
                    String viewId = rule.optString("vid", "");
                    if (!viewId.isEmpty()) {
                        hideViewAggressively(activity, viewId, currentPkg);
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("FeedDog: applyRules Exception - " + t.getMessage());
        }
    }

    // 跨进程读取规则，优先 ContentProvider，回退 Settings.Global
    private JSONArray queryRules(Activity activity) {
        // 1. 优先通过 ContentProvider（对声明了足够 queries 的 App 有效）
        try {
            Cursor cursor = activity.getContentResolver().query(RULES_URI, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        return new JSONArray(cursor.getString(0));
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) { }

        // 2. 回退到 Settings.Global（系统级 ContentProvider，不受包可见性限制）
        try {
            String json = Settings.Global.getString(activity.getContentResolver(), "feeddog_rules");
            if (json != null) {
                return new JSONArray(json);
            }
        } catch (Throwable ignored) { }

        return new JSONArray();
    }

    // 辅助方法：结合基础和连续的 OnGlobalLayout 监听隐藏，以对付复杂的动态加载信息流
    private void hideViewAggressively(Activity activity, String viewIdName, String packageName) {
        if (activity == null) return;

        int viewId = activity.getResources().getIdentifier(viewIdName, "id", packageName);
        if (viewId == 0) {
            return;
        }

        // 1. 尝试直接主动隐藏，使用 INVISIBLE 代替 GONE 防止父布局塌陷导致露出底部黑色背景
        View targetView = activity.findViewById(viewId);
        if (targetView != null) {
            targetView.setVisibility(View.INVISIBLE);
        }

        // 2. 提供连续检查：挂载全局视图树渲染监听器（解决App布局被异步延迟渲染出来的问题）
        try {
            View decorView = activity.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    View v = activity.findViewById(viewId);
                    if (v != null && v.getVisibility() != View.INVISIBLE) {
                        v.setVisibility(View.INVISIBLE);
                    }
                }
            });
        } catch (Throwable ignored) { }
    }
}
