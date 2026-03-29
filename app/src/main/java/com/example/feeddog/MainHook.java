package com.example.feeddog;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import org.json.JSONArray;
import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private XSharedPreferences prefs;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.example.feeddog")) {
            return;
        }
        
        // 尝试利用 XSharedPreferences 读取配置
        if (prefs == null) {
            prefs = new XSharedPreferences("com.example.feeddog", "feeddog_rules");
            prefs.makeWorldReadable();
        } else {
            prefs.reload();
        }
        
        String rulesString = prefs.getString("rules", "[]");
        JSONArray rules = new JSONArray(rulesString);
        
        // 如果获取不到规则，做一下基础兜底（硬编码）防止读取失败
        if (rules.length() == 0) {
            rules = new JSONArray();
            rules.put(new JSONObject().put("pkg", "com.xingin.xhs").put("act", "com.xingin.xhs.index.v2.IndexActivityV2").put("vid", "fcn"));
            rules.put(new JSONObject().put("pkg", "tv.danmaku.bili").put("act", "tv.danmaku.bili.MainActivityV2").put("vid", "recycler_view"));
            rules.put(new JSONObject().put("pkg", "com.bilibili.app.in").put("act", "tv.danmaku.bili.MainActivityV2").put("vid", "recycler_view"));
        }

        // 遍历所有的规则
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String pkgName = rule.getString("pkg");
            String actName = rule.getString("act");
            String vidName = rule.getString("vid");

            // 只有包名匹配才执行对应的 Hook
            if (lpparam.packageName.equals(pkgName)) {
                XposedBridge.log("FeedDog: matched rule for " + pkgName + " - " + actName);
                
                // 1. Hook onResume()
                try {
                    XposedHelpers.findAndHookMethod(actName, lpparam.classLoader, "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            hideViewAggressively((Activity) param.thisObject, vidName, lpparam.packageName);
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log("FeedDog: onResume Hook Exception - " + t.getMessage());
                }

                // 2. Hook onCreate() 以捕捉早期渲染 (解决小红书初次加载时有短暂显示的bug)
                try {
                    XposedHelpers.findAndHookMethod(actName, lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            hideViewAggressively((Activity) param.thisObject, vidName, lpparam.packageName);
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log("FeedDog: onCreate Hook Exception - " + t.getMessage());
                }
            }
        }
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