package com.hanabi.feeddog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.SparseBooleanArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_RULES = 1001;
    private static final int REQUEST_EXPORT_RULES = 1002;
    private static final int COLOR_DIALOG_PRIMARY = 0xFF1D4ED8;
    private static final int COLOR_DIALOG_SECONDARY = 0xFF64748B;
    private static final int COLOR_INPUT_TEXT = 0xFF1F2937;
    private static final int COLOR_INPUT_HINT = 0xFF94A3B8;

    private SharedPreferences prefs;
    private RuleListAdapter adapter;
    private JSONArray rulesArray = new JSONArray();

    private ListView listRules;
    private LinearLayout layoutMultiActions;
    private Button btnDeleteSelected;
    private Button btnSelectAll;
    private Button btnInverseSelect;
    private MenuItem menuMultiToggleItem;
    private boolean isMultiSelectMode = false;
    private final SparseBooleanArray selectedItems = new SparseBooleanArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 现代 Android 建议不使用 MODE_WORLD_READABLE，这里我们使用私有结合文件权限来让模块读取
        prefs = getSharedPreferences("feeddog_rules", MODE_PRIVATE);

        // 利用 root 自动授予写入系统设置的权限（Magisk 会弹一次授权提示）
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pm grant com.hanabi.feeddog android.permission.WRITE_SECURE_SETTINGS"}).waitFor();
            } catch (Exception ignored) { }
        }).start();

        layoutMultiActions = findViewById(R.id.layout_multi_actions);
        btnDeleteSelected = findViewById(R.id.btn_delete_selected);
        btnSelectAll = findViewById(R.id.btn_select_all);
        btnInverseSelect = findViewById(R.id.btn_inverse_select);
        listRules = findViewById(R.id.list_rules);

        adapter = new RuleListAdapter();
        listRules.setAdapter(adapter);
        listRules.setChoiceMode(ListView.CHOICE_MODE_NONE);

        loadRules();
        updateMultiSelectControls();

        listRules.setOnItemClickListener((parent, view, position, id) -> {
            if (isMultiSelectMode) {
                selectedItems.put(position, !selectedItems.get(position, false));
                adapter.notifyDataSetChanged();
                updateMultiSelectControls();
                return;
            }

            try {
                JSONObject rule = rulesArray.getJSONObject(position);
                boolean enabled = rule.optBoolean("enabled", true);
                rule.put("enabled", !enabled);
                persistRules();
                refreshRuleDisplays();
                Toast.makeText(this, !enabled ? "规则已启用" : "规则已禁用", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "状态切换失败", Toast.LENGTH_SHORT).show();
            }
        });

        listRules.setOnItemLongClickListener((parent, view, position, id) -> {
            if (isMultiSelectMode) {
                return true;
            }
            showRuleActionsDialog(position);
            return true;
        });

        btnDeleteSelected.setOnClickListener(v -> {
            int selectedCount = getSelectedCount();
            if (selectedCount == 0) {
                Toast.makeText(this, "请先选择要删除的规则", Toast.LENGTH_SHORT).show();
                return;
            }

            showStyledDialog(newDialogBuilder()
                    .setTitle("批量删除")
                    .setMessage("确定删除选中的 " + selectedCount + " 条规则吗？")
                    .setPositiveButton("删除", (dialog, which) -> deleteSelectedRules())
                    .setNegativeButton("取消", null));
        });

        btnSelectAll.setOnClickListener(v -> selectAllRules());
        btnInverseSelect.setOnClickListener(v -> inverseSelectRules());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_actions, menu);
        menuMultiToggleItem = menu.findItem(R.id.action_multi_toggle);
        updateMultiToggleMenuItem();
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateMultiToggleMenuItem();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_add_rule) {
            showAddRuleDialog();
            return true;
        }

        if (itemId == R.id.action_multi_toggle) {
            if (isMultiSelectMode) {
                exitMultiSelectMode();
            } else {
                enterMultiSelectMode();
            }
            return true;
        }

        if (itemId == R.id.action_import_rules) {
            startImportRules();
            return true;
        }

        if (itemId == R.id.action_export_rules) {
            startExportRules();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showAddRuleDialog() {
        LinearLayout container = createDialogFormContainer();
        EditText editPkg = addDialogInput(container, "包名", "", true);
        EditText editAct = addDialogInput(container, "Activity 全类名", "", false);
        EditText editVid = addDialogInput(container, "信息流 View ID (控件的 ID 名称)，如 fcn 或 recycler_view", "", false);
        EditText editNote = addDialogInput(container, "备注（可选）", "", false);

        showStyledDialog(newDialogBuilder()
                .setTitle("添加规则")
                .setView(container)
                .setPositiveButton("添加", (dialog, which) -> {
                    String pkg = editPkg.getText().toString().trim();
                    String act = editAct.getText().toString().trim();
                    String vid = editVid.getText().toString().trim();
                    String note = editNote.getText().toString().trim();

                    if (pkg.isEmpty() || act.isEmpty() || vid.isEmpty()) {
                        Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    try {
                        rulesArray.put(createRule(pkg, act, vid, note, true));
                        persistRules();
                        refreshRuleDisplays();
                        exitMultiSelectMode();
                        Toast.makeText(this, "规则已添加，请重启目标App生效", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "添加失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null));
    }

    private void enterMultiSelectMode() {
        isMultiSelectMode = true;
        selectedItems.clear();
        adapter.notifyDataSetChanged();
        updateMultiSelectControls();
        Toast.makeText(this, "已进入多选模式", Toast.LENGTH_SHORT).show();
    }

    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        selectedItems.clear();
        adapter.notifyDataSetChanged();
        updateMultiSelectControls();
    }

    private int getSelectedCount() {
        int count = 0;
        for (int i = 0; i < selectedItems.size(); i++) {
            if (selectedItems.valueAt(i)) {
                count++;
            }
        }
        return count;
    }

    private void updateMultiSelectControls() {
        if (!isMultiSelectMode) {
            layoutMultiActions.setVisibility(View.GONE);
            btnDeleteSelected.setText("删除");
            setDeleteButtonEnabled(false);
            updateMultiToggleMenuItem();
            return;
        }

        layoutMultiActions.setVisibility(View.VISIBLE);

        int selectedCount = getSelectedCount();
        if (selectedCount > 0) {
            btnDeleteSelected.setText("删除(" + selectedCount + ")");
            setDeleteButtonEnabled(true);
        } else {
            btnDeleteSelected.setText("删除");
            setDeleteButtonEnabled(false);
        }
        updateMultiToggleMenuItem();
    }

    private void setDeleteButtonEnabled(boolean enabled) {
        btnDeleteSelected.setEnabled(enabled);
        btnDeleteSelected.setAlpha(enabled ? 1f : 0.6f);
    }

    private void updateMultiToggleMenuItem() {
        if (menuMultiToggleItem == null) {
            invalidateOptionsMenu();
            return;
        }

        if (isMultiSelectMode) {
            menuMultiToggleItem.setIcon(R.drawable.ic_close_flat);
            menuMultiToggleItem.setTitle("取消多选");
        } else {
            menuMultiToggleItem.setIcon(R.drawable.ic_multi_flat);
            menuMultiToggleItem.setTitle("开启多选");
        }
    }

    private void selectAllRules() {
        if (!isMultiSelectMode) {
            return;
        }

        selectedItems.clear();
        for (int i = 0; i < rulesArray.length(); i++) {
            selectedItems.put(i, true);
        }
        adapter.notifyDataSetChanged();
        updateMultiSelectControls();
    }

    private void inverseSelectRules() {
        if (!isMultiSelectMode) {
            return;
        }

        for (int i = 0; i < rulesArray.length(); i++) {
            selectedItems.put(i, !selectedItems.get(i, false));
        }
        adapter.notifyDataSetChanged();
        updateMultiSelectControls();
    }

    private void deleteSelectedRules() {
        int removedCount = 0;
        for (int i = rulesArray.length() - 1; i >= 0; i--) {
            if (selectedItems.get(i, false)) {
                removeRuleAt(i);
                removedCount++;
            }
        }

        persistRules();
        refreshRuleDisplays();
        exitMultiSelectMode();
        Toast.makeText(this, "已删除 " + removedCount + " 条规则", Toast.LENGTH_SHORT).show();
    }

    private void showRuleActionsDialog(int position) {
        CharSequence[] options = new CharSequence[]{"编辑", "删除"};
        newDialogBuilder()
                .setTitle("规则操作")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showEditRuleDialog(position);
                    } else if (which == 1) {
                        confirmAndDeleteRule(position);
                    }
                })
                .show();
    }

    private void showEditRuleDialog(int position) {
        try {
            JSONObject rule = rulesArray.getJSONObject(position);

            LinearLayout container = createDialogFormContainer();
            EditText editPkg = addDialogInput(container, "填写包名", rule.optString("pkg", ""), true);
            EditText editAct = addDialogInput(container, "填写Activity全类名", rule.optString("act", ""), false);
            EditText editVid = addDialogInput(container, "如 fcn 或 recycler_view", rule.optString("vid", ""), false);
            EditText editNote = addDialogInput(container, "备注（可选）", rule.optString("note", ""), false);

            showStyledDialog(newDialogBuilder()
                    .setTitle("编辑规则")
                    .setView(container)
                    .setPositiveButton("保存", (dialog, which) -> {
                        String pkg = editPkg.getText().toString().trim();
                        String act = editAct.getText().toString().trim();
                        String vid = editVid.getText().toString().trim();
                        String note = editNote.getText().toString().trim();

                        if (pkg.isEmpty() || act.isEmpty() || vid.isEmpty()) {
                            Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        try {
                            rule.put("pkg", pkg);
                            rule.put("act", act);
                            rule.put("vid", vid);
                            rule.put("note", note);
                            if (!rule.has("enabled")) {
                                rule.put("enabled", true);
                            }
                            persistRules();
                            refreshRuleDisplays();
                            Toast.makeText(this, "规则已更新", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(this, "更新失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null));
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "读取规则失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmAndDeleteRule(int position) {
        showStyledDialog(newDialogBuilder()
                .setTitle("删除规则")
                .setMessage("确定删除这条规则吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    removeRuleAt(position);
                    persistRules();
                    refreshRuleDisplays();
                    Toast.makeText(this, "规则已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null));
    }

    private AlertDialog.Builder newDialogBuilder() {
        return new AlertDialog.Builder(this, R.style.FeedDogDialogTheme);
    }

    private AlertDialog showStyledDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setTextColor(COLOR_DIALOG_PRIMARY);
                positive.setAllCaps(false);
            }

            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) {
                negative.setTextColor(COLOR_DIALOG_SECONDARY);
                negative.setAllCaps(false);
            }
        });
        dialog.show();
        return dialog;
    }

    private LinearLayout createDialogFormContainer() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(18), dp(10), dp(18), dp(2));
        return container;
    }

    private EditText addDialogInput(LinearLayout container, String hint, String text, boolean isFirst) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(text);
        input.setSingleLine(true);
        input.setTextSize(15f);
        input.setTextColor(COLOR_INPUT_TEXT);
        input.setHintTextColor(COLOR_INPUT_HINT);
        input.setBackgroundResource(R.drawable.dialog_input_bg);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = isFirst ? dp(8) : dp(10);
        input.setLayoutParams(params);
        container.addView(input);
        return input;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void removeRuleAt(int position) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            rulesArray.remove(position);
            return;
        }

        JSONArray newArray = new JSONArray();
        try {
            for (int i = 0; i < rulesArray.length(); i++) {
                if (i != position) {
                    newArray.put(rulesArray.get(i));
                }
            }
        } catch (Exception ignored) { }
        rulesArray = newArray;
    }

    private void loadRules() {
        try {
            String json = prefs.getString("rules", "[]");
            rulesArray = new JSONArray(json);
            initializeDefaultRulesOnce();
            refreshRuleDisplays();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "加载规则失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeDefaultRulesOnce() {
        boolean initialized = prefs.getBoolean("rules_initialized", false);
        if (initialized) {
            return;
        }

        try {
            if (rulesArray.length() == 0) {
                rulesArray = loadDefaultRulesFromConfig();
                persistRules();
            }
            prefs.edit().putBoolean("rules_initialized", true).commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private JSONArray loadDefaultRulesFromConfig() {
        try (InputStream in = getAssets().open("default_rules.json")) {
            String content = readUtf8(in);
            return sanitizeRules(new JSONArray(content));
        } catch (Exception e) {
            e.printStackTrace();
            return new JSONArray();
        }
    }

    private JSONObject createRule(String pkg, String act, String vid, String note, boolean enabled) throws Exception {
        JSONObject rule = new JSONObject();
        rule.put("pkg", pkg);
        rule.put("act", act);
        rule.put("vid", vid);
        rule.put("note", note);
        rule.put("enabled", enabled);
        return rule;
    }

    private void refreshRuleDisplays() {
        adapter.notifyDataSetChanged();
    }

    private void persistRules() {
        prefs.edit().putString("rules", rulesArray.toString()).commit();

        // 同步写入 Settings.Global 作为跨进程传输通道（不受包可见性限制）
        try {
            Settings.Global.putString(getContentResolver(), "feeddog_rules", rulesArray.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 设置目录和首选项文件全局可读，让 Xposed 模块可以读取
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, "feeddog_rules.xml");

            if (dataDir.exists()) {
                dataDir.setExecutable(true, false);
                dataDir.setReadable(true, false);
            }
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startImportRules() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_RULES);
    }

    private void startExportRules() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault());
        String filename = "feeddog_rules_" + sdf.format(new java.util.Date()) + ".json";
        
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        startActivityForResult(intent, REQUEST_EXPORT_RULES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_RULES) {
            importRulesFromUri(uri);
        } else if (requestCode == REQUEST_EXPORT_RULES) {
            exportRulesToUri(uri);
        }
    }

    private void importRulesFromUri(Uri uri) {
        try {
            String content = readUtf8(uri);
            JSONArray importedArray = new JSONArray(content);
            rulesArray = sanitizeRules(importedArray);
            persistRules();
            refreshRuleDisplays();
            exitMultiSelectMode();
            Toast.makeText(this, "已导入 " + rulesArray.length() + " 条规则", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导入失败，请确认文件为有效JSON数组", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportRulesToUri(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IllegalStateException("无法打开导出文件");
            }
            JSONArray clean = sanitizeRules(rulesArray);
            out.write(clean.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Toast.makeText(this, "规则导出成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private JSONArray sanitizeRules(JSONArray source) throws Exception {
        JSONArray clean = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject obj = source.optJSONObject(i);
            if (obj == null) {
                continue;
            }

            String pkg = obj.optString("pkg", "").trim();
            String act = obj.optString("act", "").trim();
            String vid = obj.optString("vid", "").trim();
            String note = obj.optString("note", "").trim();
            if (pkg.isEmpty() || act.isEmpty() || vid.isEmpty()) {
                continue;
            }

            boolean enabled = obj.optBoolean("enabled", true);
            clean.put(createRule(pkg, act, vid, note, enabled));
        }
        return clean;
    }

    private String readUtf8(Uri uri) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("无法读取导入文件");
            }
            builder.append(readUtf8(in));
        }
        return builder.toString();
    }

    private String readUtf8(InputStream in) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private class RuleListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rulesArray.length();
        }

        @Override
        public Object getItem(int position) {
            return rulesArray.optJSONObject(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_rule, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            JSONObject rule = rulesArray.optJSONObject(position);
            if (rule == null) {
                holder.title.setText("无效规则");
                holder.detail.setVisibility(View.GONE);
                holder.checkbox.setVisibility(View.GONE);
                holder.container.setBackgroundResource(R.drawable.rule_item_disabled);
                return convertView;
            }

            String note = rule.optString("note", "").trim();
            String pkg = rule.optString("pkg", "");
            String act = rule.optString("act", "");
            String vid = rule.optString("vid", "");
            boolean enabled = rule.optBoolean("enabled", true);

            if (!note.isEmpty()) {
                holder.title.setText(note);
                holder.detail.setVisibility(View.GONE);
            } else {
                holder.title.setText("包名: " + pkg);
                holder.detail.setText("Activity: " + act + "\nViewID: " + vid);
                holder.detail.setVisibility(View.VISIBLE);
            }

            boolean selected = selectedItems.get(position, false);
            holder.checkbox.setVisibility(isMultiSelectMode ? View.VISIBLE : View.GONE);
            holder.checkbox.setChecked(selected);

            if (isMultiSelectMode && selected) {
                holder.container.setBackgroundResource(R.drawable.rule_item_selected);
                holder.title.setAlpha(1f);
                holder.detail.setAlpha(1f);
            } else if (enabled) {
                holder.container.setBackgroundResource(R.drawable.rule_item_enabled);
                holder.title.setAlpha(1f);
                holder.detail.setAlpha(0.95f);
            } else {
                holder.container.setBackgroundResource(R.drawable.rule_item_disabled);
                holder.title.setAlpha(0.55f);
                holder.detail.setAlpha(0.5f);
            }

            return convertView;
        }
    }

    private static class ViewHolder {
        private final View container;
        private final TextView title;
        private final TextView detail;
        private final CheckBox checkbox;

        private ViewHolder(View itemView) {
            container = itemView.findViewById(R.id.rule_item_container);
            title = itemView.findViewById(R.id.text_rule_title);
            detail = itemView.findViewById(R.id.text_rule_detail);
            checkbox = itemView.findViewById(R.id.checkbox_rule_select);
        }
    }
}