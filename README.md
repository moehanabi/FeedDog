# FeedDog

FeedDog 是一个 LSPosed/Xposed 模块，用于按规则隐藏目标 App 中的信息流视图。

模块通过 `包名 + Activity + ViewID` 进行匹配，在目标页面渲染时自动将对应视图隐藏。

## 运行与使用

1. 安装 APK。
2. 在 LSPosed 中启用模块并勾选目标应用作用域。
3. 打开模块 App 配置规则。
4. 重启目标 App 验证效果。

## 环境要求

- Android minSdk 24+
- compileSdk / targetSdk 34
- Xposed API（编译时）：`de.robv.android.xposed:api:82`

## 说明

- 如果目标 App UI 结构变化，请更新对应规则中的 `Activity` 或 `ViewID`。

## 权限相关

### WRITE_SECURE_SETTINGS 用途

- 用于将规则同步到 `Settings.Global`，作为跨进程读取通道。
- 首次启动时，应用会尝试通过 `su` 自动授予该权限。

### 权限持续性

- `WRITE_SECURE_SETTINGS` 属于系统级权限，授予后通常会持续生效（除非你手动撤销、清除数据、重装应用，或系统策略变更）。
- 模块在启动时会再次尝试授权，这是幂等操作：
	- 已授权时不会产生副作用
	- 未授权时会请求 root 执行授权

### 授权状态存放位置

- 授权状态由系统 `PackageManager` 维护，不在应用私有目录中：
	- 常见存放位置：`/data/system/packages.xml`
	- 部分 ROM/版本还会在 `/data/system/users/0/` 下维护用户态权限记录

### 撤销 WRITE_SECURE_SETTINGS

```sh
su -c pm revoke com.hanabi.feeddog android.permission.WRITE_SECURE_SETTINGS
```

## 配置文件位置

- 默认初始化规则（随 APK 打包）：
	- `app/src/main/assets/default_rules.json`
- 运行时规则（应用实际读写）：
	- SharedPreferences 名称：`feeddog_rules`
	- 键：`rules`
	- 物理文件（设备端）：`/data/data/com.hanabi.feeddog/shared_prefs/feeddog_rules.xml`
- 跨进程同步通道：
	- `Settings.Global` 键：`feeddog_rules`
	- 常见系统存放文件：`/data/system/users/0/settings_global.xml`

## 规则清理

### 删除系统侧规则（Settings.Global）

```sh
su -c settings delete global feeddog_rules
```

### 删除应用侧规则（SharedPreferences）

方式 1（推荐）：在应用中删除规则或清空规则列表。

方式 2（命令行，需 root）：

```sh
su -c rm /data/data/com.hanabi.feeddog/shared_prefs/feeddog_rules.xml
```

也可直接清空应用数据（会一并清除本地配置）：

```sh
su -c pm clear com.hanabi.feeddog
```
