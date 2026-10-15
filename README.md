# God · Android

God 私人工作台的原生 Android 客户端，桌面上的应用名是 **God**，也称 **Robby Console**。使用原生界面查看访问统计、资产、服务状态、旅行账本与常用工具，通过独立 OIDC 客户端和 Passkey 登录已有的 God 服务。

> 当前为早期版本。源码开放不代表私人服务开放注册；默认服务仅允许已有授权账号访问。应用不包含账户、服务密钥或演示余额。功能覆盖和已知差距见 [功能对应表](docs/FEATURE-PARITY.md)。

## 下载与安装

在 [GitHub Releases](https://github.com/robbywang25/robby-console-android/releases) 下载 `robby-console-android-<版本>.apk`。在 Android 上打开 APK，按系统提示允许该下载来源安装应用。以后下载同一项目的新版 APK 即可覆盖升级，保留登录和应用数据。

- Android 11（API 30）及以上；目标 Android 16（API 36）。
- Release APK 使用独立持久签名；每个 Release 附 SHA-256 校验文件。
- 不需要 root、Google 账号或应用商店；Passkey 可用性取决于浏览器、设备与已登记凭据。
- **没有本机 Passkey：**选择「通过已有设备授权」，在已经能登录 God 的电脑或另一台手机打开验证页，输入 Find 显示的一次性代码，并亲自核对应用名和确认。代码不需要发给维护者。
- **已有本机 Passkey：**也可直接使用 Passkey 登录。登录页在应用内打开（Chrome 的 Auth Tab），验证完成后直接回到应用；浏览器不支持时会退回 Custom Tab 或系统浏览器，再通过 HTTPS App Link 返回。
- 如果登录后没有返回，请在系统「应用 → God 工作台 → 默认打开」允许打开支持的链接，再重试登录。

## 功能

应用名称为 God，图标是黑底满版的光环人像自适应图标；「我」页可以设置只保存在本机的个人头像。界面采用 Liquid Glass 风格：浅色和深色各有一套天空渐变背景，内容放在半透明卡片上，底部是悬浮的玻璃 Dock（图标 + 文字），选中胶囊会随 Tab 切换滑动。Android 12 及以上会实时模糊 Dock 下方的内容。App 及总览使用 God 网页的光环 Logo。总览只有「看板」和「常用」：看板可以自由组合服务状态、提醒、实例、域名、OpenAI、访问、OKX、剪贴板、行情和 Token 额度卡片，点「编辑」开关和排序；大字体下自动调整数值排版。五个导航区：总览、洞察、资产、工具、我。「我 → 关于」里可以查看每个版本的更新记录，升级后有红点提示；有新版本时可以在 App 内一键更新，安装包会先核对 SHA-256 和签名，再由系统确认安装。原生入口覆盖访问统计、Visit Observer、PostHog、市场、OKX 只读概览、OpenAI 用量、Token 额度（tokens.robby.wang 的多账号剩余额度）、基础设施、域名、服务健康、工程记录、云剪贴板元数据、多账本、图片库存、微信卡片、公众号、目录、Prompt 与凭据配置状态。

空值显示「未知」，数据过期、部分可用、未配置和需要重新认证分别呈现。财务金额保留服务端十进制值；人民币换算是参考值。UI 不直接展示后台 JSON 或凭据。

详细的功能完成度、尚未迁移的写操作、合同版本及验证边界见 [FEATURE-PARITY.md](docs/FEATURE-PARITY.md)。

## 隐私与认证

- 原生 UI，无 WebView、广告或第三方统计 SDK。
- Authorization Code + PKCE S256，以及 RFC 8628 设备授权；独立 public client，无 client secret。
- 设备授权仅由用户主动发起，轮询有间隔和截止时间；短码与 device code 只在内存中，取消和退出阻止迟到授权复活。
- 固定 HTTPS API，拒绝重定向；服务端继续执行账户、scope、资源与 owner 校验。
- 应用内更新只访问 GitHub Release，可跟随的跳转仅限 GitHub 附件域名；安装包要先通过 SHA-256 和签名校验，再由系统安装器请你确认。为此需要「安装未知应用」权限。
- 登录页由 Chrome 在应用内渲染（Auth Tab），应用读不到页面内容，不读取密码、Passkey 私钥或浏览器 Cookie；回调地址由 Chrome 按 god.robby.wang 的 Digital Asset Links 校验后交还应用。
- 会话使用 Android Keystore AES-GCM 加密，禁止云备份和设备迁移。
- 业务内容仅在内存展示；进入后台清除。登录后屏蔽截屏和最近任务预览。
- 退出立即清除本机凭据，后台尝试服务端令牌吊销。

完整说明见 [PRIVACY.md](PRIVACY.md)，安全报告方式见 [SECURITY.md](SECURITY.md)。

## 本地构建

需要 JDK 17、Android SDK Platform 36、Build Tools 36.0.0。

```sh
# local.properties 只写本机 SDK 路径，不提交
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleDebug
```

构建依赖使用 Google Maven、Maven Central 和官方 Gradle 分发。Gradle wrapper 包含下载校验。

Debug 包是独立的离线界面预览，只使用合成数据，不连接真实账号。真实服务联调使用签名 Release；预览入口及主题检查命令见 [原生界面说明](docs/NATIVE-APPEARANCE.md)。

正式签名材料放在仓库外的私有目录：`release.jks`（PKCS12）、`store-password`（单行密码）。alias 为 `robby-console-android`，两个文件不得提交。指定该目录后构建：

```sh
ROBBY_ANDROID_SIGNING_DIR=/absolute/private/signing ./gradlew assembleRelease
```

未提供签名目录时只能构建未签名 Release；它不能替换官方安装包。自行更换签名、服务域名或客户端 ID 时，需要配置自己的 OIDC public client 和 `assetlinks.json`，并重新验证权限与登录链路。

## 项目结构

- `app/`：原生 Activity、功能卡片、固定 API 传输、认证与测试。
- `docs/FEATURE-PARITY.md`：与既有工作台能力的对应关系和限制。
- `docs/NATIVE-APPEARANCE.md`：原生视觉组件、平台降级和独立界面预览。
- `docs/PRODUCT-STYLE.md`：当前产品风格、交互原则和迭代验收标准。
- `.github/workflows/`：编译、静态检查和单元测试。

服务端运维、私人数据、签名密钥和内部系统配置不在此仓库。正式 APK 由维护者签名后上传 GitHub Release。

## 许可

[MIT License](LICENSE)。

## 付费技术实验

独立的 [`billing-lab`](billing-lab/README.md) 用于未来Android产品的买断和分档付费技术储备。它不是God收费功能，不改变工作台权限；本地模拟与官方SDK环境检测明确分开。[方案调研](docs/ANDROID-BILLING-RESEARCH.md)。
