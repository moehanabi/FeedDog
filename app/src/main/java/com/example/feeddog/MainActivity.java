package com.example.feeddog;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private ArrayAdapter<String> adapter;
    private List<String> ruleDisplays;
    private JSONArray rulesArray;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 现代 Android 建议不使用 MODE_WORLD_READABLE，这里我们使用私有结合文件权限来让模块读取
        prefs = getSharedPreferences("feeddog_rules", MODE_PRIVATE);
        
        EditText editPkg = findViewById(R.id.edit_pkg);
        EditText editAct = findViewById(R.id.edit_act);
        EditText editVid = findViewById(R.id.edit_vid);
        Button btnAdd = findViewById(R.id.btn_add);
        ListView listRules = findViewById(R.id.list_rules);

        ruleDisplays = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, ruleDisplays);
        listRules.setAdapter(adapter);

        loadRules();

        btnAdd.setOnClickListener(v -> {
            String pkg = editPkg.getText().toString().trim();
            String act = editAct.getText().toString().trim();
            String vid = editVid.getText().toString().trim();

            if (pkg.isEmpty() || act.isEmpty() || vid.isEmpty()) {
                Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                JSONObject rule = new JSONObject();
                rule.put("pkg", pkg);
                rule.put("act", act);
                rule.put("vid", vid);
                rulesArray.put(rule);
                saveRules();
                
                editPkg.setText("");
                editAct.setText("");
                editVid.setText("");
                Toast.makeText(this, "规则已添加，请重启目标App生效", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        listRules.setOnItemLongClickListener((parent, view, position, id) -> {
            // Remove item from JSONArray
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                rulesArray.remove(position);
            } else {
                // 回退方案
                JSONArray newArray = new JSONArray();
                try {
                    for(int i = 0; i < rulesArray.length(); i++) {
                        if (i != position) newArray.put(rulesArray.get(i));
                    }
                } catch (Exception ignored) {}
                rulesArray = newArray;
            }
            saveRules();
            Toast.makeText(this, "规则已删除", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void loadRules() {
        try {
            String json = prefs.getString("rules", "[]");
            rulesArray = new JSONArray(json);
            
            // 为了兼顾OP的需求，如果没有规则就自动初始化这三个默认规则
            if (rulesArray.length() == 0) {
                rulesArray.put(new JSONObject().put("pkg", "com.xingin.xhs").put("act", "com.xingin.xhs.index.v2.IndexActivityV2").put("vid", "fcn"));
                rulesArray.put(new JSONObject().put("pkg", "tv.danmaku.bili").put("act", "tv.danmaku.bili.MainActivityV2").put("vid", "recycler_view"));
                rulesArray.put(new JSONObject().put("pkg", "com.bilibili.app.in").put("act", "tv.danmaku.bili.MainActivityV2").put("vid", "recycler_view")); // BILIBILI的新包名
                saveRules();
                return;
            }

            ruleDisplays.clear();
            for (int i = 0; i < rulesArray.length(); i++) {
                JSONObject obj = rulesArray.getJSONObject(i);
                ruleDisplays.add("包名: " + obj.getString("pkg") + "\nActivity: " + obj.getString("act") + "\nViewID: " + obj.getString("vid"));
            }
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveRules() {
        prefs.edit().putString("rules", rulesArray.toString()).apply();
        // 设置首选项文件全局可读，这样 Xposed 模块可以读取它
        try {
            File prefsFile = new File(getApplicationInfo().dataDir, "shared_prefs/feeddog_rules.xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
                prefsFile.setExecutable(true, false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        loadRules();
    }
}