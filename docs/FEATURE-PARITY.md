# Android 原生功能对应表

本表描述 Android 原生客户端已实现的展示能力。入口覆盖不代表完整操作对齐，也不代表所有设备与线上场景均已验收。

所有路径相对于 `https://god.robby.wang/api/mobile/v1/`。入口不构造未验证的网页地址，`webPath` 当前为空。Network、OIDC、Activity、分页和真机安装由应用其他组件负责。

| Android / iOS 功能 | GET 路径 | 当前原生展示 | 尚未对齐的 iOS 能力 |
| --- | --- | --- | --- |
| 工作台 | `overview` | 在线、已知与全部服务计数，实例、域名、提醒 | 提醒关闭/稍后、常用入口排序与使用记录同步 |
| Visit Observer | `analytics/overview` | 聚合访问和事件、站点健康、各来源状态 | 时间/站点筛选、路径聚合与趋势图 |
| Umami 访问统计 | `umami/overview` | 各站浏览量、访客、访问、跳出、路径与来源 | 趋势图、完整控制台链接 |
| PostHog 产品分析 | `posthog/overview` | 事件计数、漏斗步骤、转化率、Web Vitals、未配置/陈旧状态 | 趋势图、完整控制台链接 |
| 访问洞察报告 | `visit-insight-reports` | 报告归档、标题、状态、聚合计数 | 报告详情与聚合证据、核验链接 |
| 市场看板 | `market` | 关注、板块、基准、AI 产业链报价与来源状态 | 7 日图、交易时钟、Kimi 缓存简报 |
| OKX 账户 | `okx/overview` | 权益、盈亏、保证金、资产、持仓 | 资金账户明细及更细风险展示；无交易操作 |
| Token 额度 | `tokens/quotas`（`god.mobile.tokens.read`，0.10.1 起申请） | 已更新/需处理/即将重置计数；按平台分组的账号：状态、邮箱或名称、套餐、额度窗口（剩余与进度条、重置倒计时、即将重置提醒）、点数、重置卡、近 1 小时剩余曲线；未接入账号单行显示 | 桌面小组件 |
| 重置日历 | `tokens/quotas`（窗口的 `resetsAt` 与 `resetHistory`） | 按月网格，每天按服务商颜色的圆点；所选日期的时间、账号、平台、额度项与「已过期 / 待核实」；未提供日期的账号与额度计数；时间按本机时区 | 网页的时区切换 |
| 28 天挑战 | `tokens/tibo`、后台 `tokens/tibo/alerts` | Tibo 美西时钟、官方重置 / 发卡预告倒计时、最近确认、28 格进展标记、所选日的每日摘要或原帖记录、同步状态；重置提醒（闹钟式响铃与全屏页） | 0.15.0 起；Day 1–4 的人工审阅详情（每日摘要已覆盖） |
| 微信消息 | `wechat/digest`（`god.mobile.wechat.read`） | 今天 / 近 7 天 / 近 30 天的消息数、发出和收到、会话数；日报；近 30 天每日柱、24 小时分布、类型占比、最活跃会话、链接域名。总览卡片默认不在看板上 | iOS 暂无 |
| OpenAI 用量 | `openai/usage` | 请求/Token/成本与模型用量；费用来源不可用单独显示 | 时间趋势、项目/批次/服务层级与洞察；API Key 分组不展示 |
| 基础设施 | `overview` → `infrastructure` | 实例规格、CPU/内存/磁盘指标、月价参考、到期 | 本地阈值提醒、安全防火墙端口列表 |
| 域名资产 | `domains/overview` | 注册、DNS、到期、自动续费、年续费参考、备案匹配与来源可用性 | 官方备案核验链接 |
| 运行状态 | `overview` → `status` | 结论与检测时间、公开/私有/上游计数、按网页规则命名的站点状态、DNS、HTTP、响应头耗时、上游组件；停留时 60 秒自动刷新 | 打开站点和官方状态页链接（移动端合同不返回 URL）、更细筛选 |
| 站点维护状态 | `site-maintenance/overview` | 标记一致性、站点状态、影响、更新 | 公开维护页链接；无生产切换 |
| 源码与工程 | `engineering` | 仓库索引、日志与服务端截断提示 | 时间/仓库筛选、提交跳转 |
| 个人中心 | `account/status`、`account/devices`（`god.mobile.read`）；写入 `account/email/send`、`email/verify`、`social/unbind`、`devices/revoke-others`（`god.mobile.account.write`，0.25.0 起申请） | 名字、两步验证与 Passkey 列表、Apple/微信绑定（可解除）、联系邮箱绑定、手机号（不支持）、在线 God 登录（本机在前）与一键下线其他设备 | 添加 Passkey、绑定 Apple/微信在网页完成；手机号绑定未开放 |
| 云剪贴板 | `clipboard` | 元数据：可见性、创建时间、到期时间 | 显式 reveal、复制、创建、改可见性、可恢复删除；不请求/显示内容 |
| 旅行记账 | `travel-ledger/current/projects`；选中后 `travel-ledger/current/snapshot?project=…&limit=40` | 项目列表；snapshot 的支出/退款/净额、多币种汇总、同行人分摊、当前页账目与分页状态 | Activity 负责项目选择和分页；账目和人员写入由 LedgerActions 提供（见下）；退款操作、导出尚未实现 |
| 图片工坊 | `image-workshop/overview` | 库存、派生图尺寸/大小、更新时间、处理服务状态 | 图片预览、本地处理、分享、三文件上传 |
| 微信卡片 | `wechat/cards` | 标题、描述、定制状态、更新时间 | 打开/分享、二维码、编辑和重置；公开二维码中心与 Artifact 目录入口尚未添加 |
| 公众号发布 | `wechat/blog-drafts/overview` | 配置、草稿/菜单/已发布计数、文章与草稿历史 | 文章安全预览、公开文章跳转 |
| 小程序目录 | `miniprogram/catalog-sync/status` | 版本、来源更新、资料配置、目录项目状态 | 可见性明细、公开链接、强制同步与操作查询 |
| Prompt 配置 | `prompts/asset-daily-briefing` | 任务、模型、来源、版本、更新时间 | 模板正文、diff、保存新版本与冲突处理 |
| DeepSeek 模型（Android 新增） | `deepseek/models` | 官方 GET /models 返回的全部模型与每个字段（含未知字段）、deepseek.robby.wang 登记的价格与能力（核对日期、有效期、来源）、本站状态、接口完整返回 | iOS 暂无；聊天仍在 deepseek.robby.wang 网页 |
| 微信登录审计 | `wechat/audit` | 管理员绑定、活跃会话、成功/拒绝计数、脱敏事件 | 细筛选；绑定挑战仍属于网页管理边界 |
| 凭据配置 | `secrets/status` | 固定服务标签、已配置/未配置、更新时间 | 不显示凭据值；写入/轮换仍属于网页管理边界 |

## 版本和数据边界

- `overview` API 版本为 `2026-08-01`，内嵌 `status.schemaVersion` 必须为 `2`。
- `openai/usage` 为 `apiVersion=2026-08-01`、`schemaVersion=4`。
- PostHog 为 `apiVersion=2026-07-01`、`schemaVersion=1`。
- Observer、Umami、行情、工程、剪贴板、凭据状态为 `apiVersion=2026-07-01`。
- 公众号发布、小程序目录和 Prompt 元数据已支持线上 `schemaVersion=2`，保留字段相同的 v1 兼容；未知后继版本拒绝展示。其他上述独立 portal 视图与 current ledger 为 `schemaVersion=1`。
- `FeatureRenderer.isCompatible` 检查这些版本和必要容器，拒绝不兼容响应。它不是完整移植 iOS 严格解码器：尚未实现每个字段的 exact-key、全部一致性、来源与计数数学关系验证。
- 微信消息（`wechat/digest`）整份校验：未知字段、长度、计数关系和隐私痕迹任一不符，整份当作不可用，不补数字。会话名只出现在功能页和总览卡片上，不进日志、通知和桌面小组件。
- 原生视图只读取显式列出的域字段；不递归遍历 JSON，不显示任意对象，也不把后台错误正文作为 UI 内容。
- 缺失、`null`、类型不匹配的数值显示“未知”；不会转换为零。`stale`、`partial`、不可用、未配置、需要认证保持不同状态。快照生成时间与来源状态独立呈现，当前账本 30 秒、其他概览 10 分钟后标记快照陈旧，不把 HTTP 成功当成来源实时。
- OKX 金额与账本金额保留服务端十进制字符串，展示层不重新计算结算或汇率。人民币合计仅为参考，成员差额不表示实际还款。
- 剪贴板正文、Prompt 模板、凭据值、API Key 分组、对象 key、请求指纹、原始访问标识不在展示 allowlist 内。
- DeepSeek 模型是唯一按原样列出未知字段和完整 JSON 的页面：内容是官方公开的模型元数据，God 网关已限制大小与层级并拒绝任何像凭据的字符串，页面只当纯文本显示、不生成链接。DeepSeek API Key 只在 deepseek.robby.wang 服务端，手机和 God 网关都拿不到。
- 每个数组最多渲染 100 项。当前账本的每页契约上限即 100；完整列表、筛选、自动续页须由 Activity 显式控制，不能声称当前页等于全部账本。
- 普通数据行最小高度 44 dp、交互控件至少 48 dp；卡片、Activity 与账本表单共用浅色/深色主题，并支持系统字体缩放和长数值堆叠。没有 WebView、网络调用或第三方渲染依赖。

## 验证

2026-10-11：使用 OpenJDK 17 的 `javac`、Android API 36 `android.jar` 单独编译展示类与账本操作类成功。此检查证明 Java/API 编译兼容；不证明完整 APK、线上 API、登录或真机显示已通过。测试目录包含合成数据的离线 instrumentation 测试，覆盖金额与身份绑定、准备结果、回执、隐私、版本和显示边界；实际运行结果以构建/发布说明为准。

集成验收重点：不兼容版本应显示明确错误；OpenAI 空成本、来源未配置、空数组、陈旧行情、未知探测状态和账本下一页不能误报零或全部完成。真实响应不得输出到日志。登录用户验收前不把“编译通过”记为“业务已可用”。

## 原生账本写入

`LedgerActions` 通过独立原生表单提供账目新增、编辑、可恢复删除、恢复及最近删除分页；人员新增、改名、颜色、停用/启用、未使用人员删除和排序。输入来自服务器提供的币种/类别/人员目录。用户输入金额按币种小数位校验，付款当地时间有明确时区，夏令时跳跃或重复时刻拒绝静默推断。

所需权限为 `god.mobile.travel.entries.write`、`god.mobile.travel.people.write`。接口均位于 `travel-ledger/entry-writes/` 或 `travel-ledger/people-writes/`：GET `context` / `receipt`，POST `prepare` / `commit` / `resolve`；账目另有 GET `trash`。Controller 使用可注入 Transport，不持有凭据、不直接访问网络。

操作顺序为读取上下文、准备、展示修改前后内容、用户点击确认、提交、核对回执、重新读取。上下文验证账号身份、账本、版本、新鲜度与隐私；准备结果验证原请求身份、金额、参与人、SHA-256 和人员名单变化。提交前把最小恢复凭证写入 Android Keystore AES-GCM 加密的单槽文件（不备份，不含金额、姓名、正文）。账目和人员共用恢复锁。超时或未知结果不能启动新提交，只查询 receipt/resolve；不会自动重发 commit。回执和读回必须与原账号/账本/请求绑定，核实后才清除凭证。

修改前后预览和用户确认已实现；退款管理、财务导出、按原请求主动重试、离线编辑不在当前写入范围。测试只用合成数据，不以真实账务做自动化测试。
