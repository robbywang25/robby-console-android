# 付费实验（测试）

独立Android技术实验，不是God付费版。包名 `wang.robby.console.android.billinglab`，不依赖`:app`、不连接God API、不共享God会话。不会发起真实购买。

## 已实现

- 内存模拟：非消耗型基础买断、Plus/Pro订阅、pending、未验单、active、取消、宽限、hold、退款、快进、恢复、账号切换。
- 23项纯Java回归：不提前授权、时间边界、档位组合、重复/旧事件、退款不能复活、收据绑定、恢复原子性和有界存储。
- Google Play Billing **9.1.0**真实依赖：仅连接服务并读取是否支持订阅，不调用`launchBillingFlow`、`queryPurchasesAsync`、acknowledge或consume。

`ScenarioEngine`的输入完全由本地模拟生成，不具备真实验单能力。它不是可直接复制到生产的授权后端。生产需要独立的持久化、身份绑定、商店API验证、通知验签、去重、对账及发货确认。

## 构建

```sh
./gradlew :billing-lab:testDebugUnitTest :billing-lab:lintRelease :billing-lab:assembleRelease
```

不提供签名目录时得到未签名Release。正式测试包沿用仓库外的签名目录：

```sh
ROBBY_ANDROID_SIGNING_DIR=/private/path ./gradlew :billing-lab:assembleRelease
adb install -r billing-lab/build/outputs/apk/release/billing-lab-release.apk
adb shell am start -n wang.robby.console.android.billinglab/wang.robby.console.billinglab.BillingLabActivity
```

Billing9.1.0的传递依赖在纯Java工程中带入不同年代的Kotlin stdlib/jdk7/jdk8；本模块用Kotlin BOM1.8.22与已解析stdlib对齐，避免重复类。未把SDK或BOM加入God的`:app`依赖。

## 推荐演练

1. 买断→pending或未验单，确认不获得权益；模拟确认后获得基础权益。
2. 选择Pro并模拟确认；取消续费后在有效期内保留Pro，快进后回落到已有买断权益。
3. 宽限期仍有效，hold不再提供订阅权益；退款不等待期满。
4. 清空本地视图，再从模拟商店恢复；切换B账号不能继承A权益。
5. 单独检查Play环境。连接成功只是SDK能力证据；没有商品、许可证测试账号和服务端验单时仍不能视为完成商店支付测试。

记录只在进程内存中，重启会重置；退款后的同一模拟购买不允许复活，需要重置后重新演练。

## 未完成的真实商店闭环

尚无Play Console测试产品/许可证测试账号、RevenueCat项目或验单服务器；未产生Play测试订单。下一阶段按研究文档配置独立测试App，再完成商店购买、服务端验证、RTDN、acknowledge和恢复购买。

详见 [Android调研与方案选择](../docs/ANDROID-BILLING-RESEARCH.md)。
