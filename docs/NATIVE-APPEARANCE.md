# 原生界面

Android 与 iOS 采用同一视觉原则：蓝色强调、清晰的数据表面，以及位于内容上方的玻璃质感导航与操作控件。Android 使用平台原生 View、Canvas、RenderNode 和 RenderEffect；不依赖 Apple 的 Liquid Glass API。

## 组件

- `ConsoleTheme` 统一浅色/深色配色、卡片、按钮、输入框、图标和对话框。
- `GlassBar` 是悬浮五栏导航；Android 12 及以上在内存中采样本应用的内容层并模糊，再绘制半透明表面。只处理导航区域，不模糊正文或金额，不截图到文件。
- Android 11、关闭动画、省电模式及支持检测的高对比文字模式使用实色或描边控件。其余数据卡片始终不透明。
- 总览直接加载已有 `overview` 数据，展示服务、实例、域名和提醒；不使用演示余额填补失败或空值。
- 功能列表有统一矢量图标与按压反馈。详情页返回和刷新固定在顶部。常用功能按明确 ID 选择。
- 大字体下指标卡改为单列，长数值和字段上下排列。控件至少 48 dp，数据行允许增长，不截断金额。
- 账本输入、人员操作和确认窗口共用主题；准备、确认、提交、回执与恢复流程保持原有约束。

主题跟随设备浅色/深色设置。业务内容继续在后台清除，正式登录界面继续使用 `FLAG_SECURE`。

## 独立视觉预览

Debug 构建是独立的 `wang.robby.console.android.preview` 应用，显示“界面预览 · 合成数据”，数据来源只在 `src/debug` 中。登录和写入被禁用，预览不接收正式 App Link。Release 的 `PreviewMode` 固定关闭，APK 不包含预览 Activity 或合成数据。

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n wang.robby.console.android.preview/wang.robby.console.android.PreviewActivity
adb shell am start -n 'wang.robby.console.android.preview/wang.robby.console.android.PreviewActivity$Dark'
adb shell am start -n 'wang.robby.console.android.preview/wang.robby.console.android.PreviewActivity$Large'
```

这些入口只覆盖预览应用的显示配置，不修改设备的全局主题或字体。可检查首页、五栏导航、功能列表、返回、滚动、设置和已有示例详情。未提供合成数据的功能显示预览限制，不请求真实服务。

视觉验收不能替代真实登录或业务数据验收；两者分别记录在发布说明中。
