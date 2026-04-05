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
                    
                    boolean useInvisible = rule.optBoolean("useInvisible", true);
                    
                    String viewId = rule.optString("vid", "");
                    if (!viewId.isEmpty()) {
                        hideViewAggressively(activity, viewId, currentPkg, useInvisible);
                    }
                    
                    String textToHide = rule.optString("text", "");
                    if (!textToHide.isEmpty()) {
                        hideViewByTextAggressively(activity, textToHide, useInvisible);
                    }
                    
                    String classToHide = rule.optString("cls", "");
                    if (!classToHide.isEmpty()) {
                        hideViewByClassAggressively(activity, classToHide, useInvisible);
                    }
                    // 注：去掉了此处的 return; 以支持同一页面的多条规则同时生效
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
    private void hideViewAggressively(Activity activity, String viewIdName, String packageName, final boolean useInvisible) {
        if (activity == null) return;

        final int viewId = activity.getResources().getIdentifier(viewIdName, "id", packageName);
        if (viewId == 0) {
            return;
        }

        try {
            final View decorView = activity.getWindow().getDecorView();
            // 第一遍主动遍历隐藏
            traverseAndHideById(decorView, viewId, useInvisible);

            // 挂载全局视图树渲染监听器持续捕捉新加载出来的元素
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    traverseAndHideById(decorView, viewId, useInvisible);
                }
            });
        } catch (Throwable ignored) { }
    }

    // 递归遍历整个视图树，把*所有*符合 ID 的控件都找出来干掉（而不是只能找到第一个）
    private void traverseAndHideById(View view, int targetId, boolean useInvisible) {
        if (view == null) return;

        int targetVisibility = useInvisible ? View.INVISIBLE : View.GONE;
        if (view.getId() == targetId && view.getVisibility() != targetVisibility) {
            view.setVisibility(targetVisibility);
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup viewGroup = (android.view.ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                traverseAndHideById(viewGroup.getChildAt(i), targetId, useInvisible);
            }
        }
    }

    // 辅助方法：结合全局视图树渲染监听器，遍历视图树根据文本内容隐藏
    private void hideViewByTextAggressively(final Activity activity, final String targetText, final boolean useInvisible) {
        if (activity == null || targetText == null || targetText.isEmpty()) return;

        try {
            final View decorView = activity.getWindow().getDecorView();
            // 这里使用 OnGlobalLayoutListener 持续监听视图树变化
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    traverseAndHideByText(decorView, targetText, useInvisible);
                }
            });
        } catch (Throwable ignored) { }
    }

    // 递归遍历视图树，寻找文本包含 targetText 的 TextView 或 Button 并隐藏
    private void traverseAndHideByText(View view, String targetText, boolean useInvisible) {
        if (view == null) return;

        int targetVisibility = useInvisible ? View.INVISIBLE : View.GONE;

        // 检查当前 View 是否是 TextView 或其子类（Button也是TextView的子类）
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.toString().contains(targetText) && view.getVisibility() != targetVisibility) {
                view.setVisibility(targetVisibility); 
            }
        }

        // 如果是 ViewGroup，递归遍历其所有子 View
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup viewGroup = (android.view.ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                traverseAndHideByText(viewGroup.getChildAt(i), targetText, useInvisible);
            }
        }
    }

    // 辅助方法：结合全局视图树渲染监听器，遍历视图树根据类名隐藏
    private void hideViewByClassAggressively(final Activity activity, final String targetClassName, final boolean useInvisible) {
        if (activity == null || targetClassName == null || targetClassName.isEmpty()) return;

        try {
            final View decorView = activity.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    traverseAndHideByClass(decorView, targetClassName, useInvisible);
                }
            });
        } catch (Throwable ignored) { }
    }

    // 递归遍历视图树，寻找类名为 targetClassName 的节点并隐藏
    private void traverseAndHideByClass(View view, String targetClassName, boolean useInvisible) {
        if (view == null) return;

        int targetVisibility = useInvisible ? View.INVISIBLE : View.GONE;

        // 检查当前 View 的类名是否匹配
        if (view.getClass().getName().equals(targetClassName) && view.getVisibility() != targetVisibility) {
            view.setVisibility(targetVisibility);
        }

        // 如果是 ViewGroup，递归遍历其所有子 View
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup viewGroup = (android.view.ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                traverseAndHideByClass(viewGroup.getChildAt(i), targetClassName, useInvisible);
            }
        }
    }
}
