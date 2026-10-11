package wang.robby.console.billinglab;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.android.billingclient.api.*;
import java.util.function.Consumer;

/** Connects only. No purchase launch, purchase-token query, acknowledgement or entitlement grant. */
final class PlayEnvironmentProbe {
 private BillingClient client; private int epoch;private final Handler main=new Handler(Looper.getMainLooper());
 void check(Context context,Consumer<String> result){
  close();int current=epoch;
  try {
  client=BillingClient.newBuilder(context.getApplicationContext())
   .setListener((response,purchases)->{})
   .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
   .enableAutoServiceReconnection().build();
  main.postDelayed(()->{if(current==epoch){close();result.accept("检测超时，可重试；没有发起购买。");}},15000);
  client.startConnection(new BillingClientStateListener(){
   public void onBillingSetupFinished(BillingResult response){main.post(()->{
    if(current!=epoch)return;
    if(response.getResponseCode()==BillingClient.BillingResponseCode.OK){
     boolean subs=client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).getResponseCode()==BillingClient.BillingResponseCode.OK;
     close();result.accept("官方 SDK 连接成功 · 订阅支持："+(subs?"是":"否")+"\n尚未配置商店商品、许可证测试账号或服务器验单。");
    }else{int code=response.getResponseCode();close();result.accept("Google Play Billing 暂不可用（代码 "+code+"）。请检查Play商店及账号环境；这不是支付失败。");}
   });}
   public void onBillingServiceDisconnected(){main.post(()->{if(current==epoch){close();result.accept("Play服务已断开，可重新检测。");}});}
  });
  } catch(RuntimeException ignored){close();result.accept("SDK初始化未完成，请检查Play环境；没有发起购买。");}
 }
 void close(){epoch++;main.removeCallbacksAndMessages(null);if(client!=null){client.endConnection();client=null;}}
}
