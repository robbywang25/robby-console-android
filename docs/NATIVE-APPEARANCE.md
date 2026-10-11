# 原生界面

当前版本按用户最新要求采用白色/黑色中性主题和固定底栏。浅色白底黑图标，深色黑底白图标；内容卡片与输入使用中性灰区分层级。下方v0.3.1和v0.3.3记录保留为历史，不是当前默认样式。Android 使用平台原生 View 和矢量资源；当前底栏不使用模糊或悬浮表面。

## 组件

- `ConsoleTheme` 统一浅色/深色配色、14 dp 数据卡片、12 dp 按钮、10 dp 输入框、图标和对话框。导航铺满底部，内容层不加装饰阴影。
- 底栏位于纵向布局内，滚动区域在它上方结束，底栏本身包含系统手势安全区；选中状态只切换图标填充。
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
adb shell 'am start -n wang.robby.console.android.preview/wang.robby.console.android.PreviewActivity\$Dark'
adb shell 'am start -n wang.robby.console.android.preview/wang.robby.console.android.PreviewActivity\$Large'
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

## v0.3.4：固定底栏、黑白主题与 God Logo

底栏位于原生纵向布局的底部，横向铺满可用宽度，与系统手势安全区衔接。滚动区域在底栏上方结束；没有悬浮外边距、阴影、圆角背景、模糊或选中胶囊。Tab仍不显示文字，保留56dp触控高度、无障碍名称和长按提示。所有Tab保持同一黑/白颜色，仅切换outline/solid矢量资产表示选中。

`artwork/god-halo-source.svg` 来自 God 网页 `src/god-halo.svg`，原光环和人物路径用于 Android 启动图标及总览Tab的黑白适配。洞察柱状图复用Ledger SVG的三柱轮廓；资产、工具、设置保持各自语义并以本项目的独立矢量资源表达，不把五个功能都替换成同一个Logo。图标在 `res/drawable/tab_*` 中，后续可逐个替换，不再依赖Canvas手绘Tab。

此轮不修改认证、接口、会话或业务写入。

## v0.3.5：腾讯蓝强调与紧凑底栏

白/黑背景和黑白图标保持。主按钮使用腾讯TDesign标准蓝 `#0052D9` 与白字；浅色链接/焦点同色，深色链接/焦点使用TDesign对应 `#4582E6`。Tab和功能符号仍黑白，选中只填充图标，不染蓝、不加背景。

Tab触控高度48dp，上下留白各2dp，底栏主体52dp，比v0.3.4减少20dp；系统手势安全区另计。控件触控高度不低于48dp。
