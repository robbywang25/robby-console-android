# 原生界面

Android 沿用 iOS 的分层原则，配色和密度参考 ledger.robby.wang 的 Classic 主题：暖米色背景、奶白数据表面、棕金色强调，以及位于内容上方的玻璃质感导航。深色采用深蓝底与柔金色。Android 使用平台原生 View、Canvas、RenderNode 和 RenderEffect；不依赖 Apple 的 Liquid Glass API。

## 组件

- `ConsoleTheme` 统一浅色/深色配色、14 dp 数据卡片、12 dp 按钮、10 dp 输入框、图标和对话框。导航保留胶囊轮廓，内容层减少阴影。
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

## Ledger 视觉来源与本轮验证

2026-10-12 使用 Ego Lite 访问 ledger.robby.wang；当前浏览器只能看到访问申请页，未读取真实账务内容。配色与控件依据同站公开 `/travel-ledger/travel-ledger.css` 的 Classic 默认变量及底部导航规则，不能据此声称已核对某个账本选择的主题。

| 角色 | 浅色 | 深色 |
| --- | --- | --- |
| 背景 | `#F3EFE7` | `#0B111A` |
| 数据表面 | `#FFFDF9` | `#121C2A` |
| 正文 | `#231F18` | `#F4F6F8` |
| 次要文字 | `#71695F` | `#9EACBD` |
| 强调 | `#89661F` | `#D5B66F` |
| 主操作 | `#624613` | `#F2D48B` |

卡片、按钮、表单与导航共享变量；账目金额突出显示，人民币合计仍标注参考值，不改变金额精度、汇率或写入流程。功能列表只在行间保留分隔线。原生五栏导航与已有玻璃降级机制保留。

v0.3.1：Release/Debug 编译、49 项 JVM 测试、lintRelease 通过。Find 未连接，浅色/深色/大字体真机视觉检查、覆盖安装尚未完成。本轮未执行真实业务写入，也没有修复或掩盖此前记录的总览401与刷新失败。

## v0.3.3：图标导航

按用户要求取消 Tab 可见文字，保留五个图标、无障碍名称、选中态和长按提示。以 Ledger 已有 dock 的样式值为准，不引入额外渐变：82% panel-strong、24 dp 模糊、32 dp 外圆角、1 dp 混合边框、轻阴影和顶部高光；选中项只覆盖10%强调色。图标24 dp，触控高度56 dp，间隔4 dp，外侧12 dp、底部安全区外10 dp。Android与网页的模糊、阴影由各自平台渲染，参数对齐不等同跨平台逐像素相同。高对比/省电/低版本降级保留。
