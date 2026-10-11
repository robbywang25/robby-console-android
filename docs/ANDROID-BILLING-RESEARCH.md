# Android 买断与分档付费技术储备

调研日期：2026-10-12。范围经确认：只研究和测试Android，为后续其他App积累能力；不让God工作台收费、不改变现有权限、不发起真实扣款。

## 推荐路线

以Google Play Billing的商品、购买状态和服务端验单为基础知识；若未来以尽快推出产品为目标，优先评估RevenueCat托管权益及Test Store。国内商店分发单独适配渠道IAP或已开通的微信/支付宝商户能力。产品权益层不依赖某个SDK，支付成功回调不能直接等同授权成功。

本仓库先提供独立 `billing-lab` 应用：本地状态机实验 + 官方Billing SDK环境检测。它不包含God账号或API，不是生产支付系统。

## GitHub方案对照

| 方案 | 买断/分档能力 | 主要收益 | 需要自己承担 | 本轮判断 |
| --- | --- | --- | --- | --- |
| Google Play Billing + Android Publisher API | 非消耗型一次性商品；订阅产品、base plan与offer | 官方购买链路，平台行为最可控 | 服务端验单、RTDN、订单幂等、权益及运营工具 | 基础技术路线 |
| RevenueCat Android SDK | 一次性买断、订阅、多档权益 | 托管验单/生命周期、恢复购买、Test Store | 自己的账号绑定、业务权限；托管服务成本与依赖 | 快速产品化首选候选 |
| Adapty Android SDK | 一次性、终身权益、订阅 | Paywall、实验、转化分析与托管验单 | 业务权限、渠道适配、托管依赖 | 当重点转为付费转化实验时比较 |
| 华为IAP | 消耗型、非消耗型、自动续订 | AppGallery渠道的原生支付 | 华为开发者配置、商品、服务端校验 | 对应渠道方案，不能替代所有Android商店 |
| 微信支付/支付宝 App支付 | 订单支付可以用于买断；自动续费要单独开通对应产品 | 国内商户和自分发场景 | 商户申请、服务端下单/验签、订阅/权益/退款系统 | 确定国内目标渠道后接入，不能把普通App支付当完整订阅平台 |

Stripe/Paddle/Polar更适合网页结账与相应收单模式，不作为本轮Android商店付费SDK的直接替代。Google Play分发数字内容时，应核对适用地区及已加入的计费项目；外部支付政策不是所有市场都相同。

## 代码与维护状态证据

- Google当前官方文档直接链接 [android/snippets/playbilling](https://github.com/android/snippets/tree/5e7c52581fa2ef5bfeea42d80c178c9996464615/playbilling)。文档及Google Maven验证Billing **9.1.0** 可用。本轮用9.1.0编译。旧 `android/play-billing-samples` 地址返回404，搜索中很多fork还停在Billing4/5/6，不作为最新实现基线。
- [RevenueCat/purchases-android](https://github.com/RevenueCat/purchases-android)：MIT，未归档；本次API回读最新release **10.26.0**（2026-10-08），仓库最近推送2026-10-11。SDK开源不等于托管服务可免费自建。
- [adaptyteam/AdaptySDK-Android](https://github.com/adaptyteam/AdaptySDK-Android)：MIT，未归档；本次最新release **4.2.2**（2026-10-08）。
- [HMS-Core/hms-iap-clientdemo-android-studio](https://github.com/HMS-Core/hms-iap-clientdemo-android-studio)：Apache-2.0，示例明确演示三种商品；仓库最近推送2023-05-25，不能据此判断当前SDK版本。
- [wechatpay-apiv3/wechatpay-java](https://github.com/wechatpay-apiv3/wechatpay-java)：官方服务端SDK，Apache-2.0，包含App下单签名和回调验签；不把商户私钥放进Android客户端。
- [alipay/alipay-sdk-java-all](https://github.com/alipay/alipay-sdk-java-all)：官方服务端SDK；与Android调起支付SDK及自己的权益系统职责不同。

以上是调研时快照，不永久锁定未来项目的SDK版本。

## 买断与分档如何建模

买断使用非消耗型一次性商品；不consume，否则会把“永久购买一次”变成可重复购买。基础买断权益与需要持续成本的云服务权益分开建模。实验中的“基础买断、Plus、Pro”是合成例子，不代表God定价或实际商品。

分档以独立能力集合表示，例如基础功能、同步、高阶功能；月付/年付是同档的周期选项，不是两个权限等级。档位升级/降级的生效时刻和差额交给商店规则，服务端根据最终有效购买更新权益，不在客户端计算应付差价。

## 生产实现必须具备

1. 价格从商店ProductDetails/Offer读取，不以本地固定数字发起购买。
2. 稳定的内部账号ID绑定购买；换邮箱、昵称或设备不自动合并权益。
3. 购买完成后将purchase token交给服务端验证，核对包名、商品、购买状态与账号绑定；不信任客户端布尔值。
4. PENDING、未验单状态不授予权益。交易确认与商品发放要幂等；非消耗型acknowledge，消耗型consume。
5. 处理RTDN/提供方Webhook时验证来源，持久化去重，以服务端查询的当前商店状态核实通知；允许重复和乱序投递，不把通知顺序当事实。
6. 取消续费一般保留已付费周期内权益；到期、账号保留期、退款/撤销分别处理。宽限期只在平台给定期间保留权益。
7. 恢复购买依赖商店查询/托管平台状态和账号绑定，不靠SharedPreferences里的“已付费”。本地权益只是可刷新缓存。
8. 沙箱和生产环境、商品与日志隔离；服务账号、商户密钥和购买token不放源码、聊天或普通日志。

[Google集成指南](https://developer.android.com/google/play/billing/integrate)、[订阅生命周期](https://developer.android.com/google/play/billing/subscriptions)、[服务端安全](https://developer.android.com/google/play/billing/security)。

## 测试层级不能混用

| 层级 | 能证明 | 不能证明 |
| --- | --- | --- |
| 本地ScenarioEngine | 合成状态与权益规则、重复事件、过期、退款、账号隔离 | 真实支付、验签、商店恢复、Play政策合规 |
| Billing SDK环境检测 | 当前设备与SDK能否建立服务连接 | 商品已配置、订单能支付、服务端验单正确 |
| RevenueCat Test Store | 测试产品、付费页面、CustomerInfo/权益集成 | Google特定生命周期及平台边界行为 |
| Play许可证测试 + Play Billing Lab | 真实商店API的测试购买、续订、pending、退款等链路 | 生产运营和全部地区/支付方式的最终表现 |

RevenueCat [Test Store](https://www.revenuecat.com/docs/test-and-launch/sandbox/test-store) 需要RevenueCat项目与对应测试API key，但无需先准备Play Console。上线前仍要测试平台沙箱。

Google [许可证测试](https://developer.android.com/google/play/billing/test) 要求测试账号属于许可证测试名单，包名匹配Play中配置的App。满足这些条件可以测试侧载调试包；“通过GitHub下载APK”本身不能替代这些条件。内部测试轨道中的普通账号仍可能产生真实扣费，必须核对测试账号。

## 本轮落地与尚未配置

独立包：`wang.robby.console.android.billinglab`。模块不被`:app`依赖，God正式APK不引入支付SDK或收费入口。

本地模拟提供买断、Plus/Pro、pending、未验单、确认、取消、宽限、保留期、退款、快进、恢复与账号切换。记录仅在进程内存里，退出后不保证保留。`ScenarioEngine`明确标注LOCAL SIMULATOR，缺少生产持久化、验单、RTDN、ack事务及防篡改能力，不得原样充当生产授权器。

官方SDK探针只连接服务及查询订阅能力，代码没有launchBillingFlow、真实购买token查询或发货操作。未配置Play商品、许可证测试账号、RevenueCat项目、商户账户或验单服务器。因此本轮不声称完成任何商店测试订单或真实扣款。

后续技术路线：先选未来App主要发行渠道；若选Google Play，建独立测试App与一次性/Plus/Pro测试产品，完成许可证测试及服务端验单；若选RevenueCat，先Test Store，再切Play沙箱。国内支付在确认商户和目标渠道后独立接入。

[Google支付政策](https://support.google.com/googleplay/android-developer/answer/9858738)需在实际发行前按目标地区重新核对。

## 本轮实际证据

- Play Billing9.1.0从Google Maven成功解析并编译；传递依赖的Kotlin旧jdk7/jdk8重复类通过同版本BOM对齐解决。
- Find真实SDK检测返回连接成功、订阅支持；没有调用购买、查询购买token或发货API。
- 本地实验23项状态机/控制器测试通过；God原有54项测试保持通过。Find演练pending不授权、买断基础权益、Pro取消保留、到期回落已通过。
- Grok只读复核指出hold/cancel/grace时间边界及缓存丢失后的汇总问题；主代理修正并新增9项控制器回归，包括反复切换cancel/grace不延长宽限期。
- 仍未配置任何商店商品、许可证测试账号、RevenueCat测试项目或真实验单服务器，不宣称通过商店测试购买。
