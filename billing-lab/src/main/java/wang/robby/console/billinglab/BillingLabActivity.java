package wang.robby.console.billinglab;
import android.app.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Independent learning app. All commercial outcomes are explicitly simulated in memory. */
public final class BillingLabActivity extends Activity {
 private final ScenarioSession session=new ScenarioSession();
 private final PlayEnvironmentProbe probe=new PlayEnvironmentProbe();
 private String owner="A",notice="选择一种购买模型，再模拟事件。",probeStatus="尚未检测。此功能只连接SDK，不发起购买。";
 private ScenarioEngine.Product product=ScenarioEngine.Product.LIFETIME;
 private boolean dark,probing;
 private LinearLayout content;private int ink,muted,blue;
 @Override public void onCreate(Bundle state){super.onCreate(state);dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;ink=dark?Color.WHITE:Color.BLACK;muted=dark?0xffaaaaaa:0xff666666;blue=dark?0xff4582e6:0xff0052d9;render();}
 private void render(){
  ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(dark?Color.BLACK:Color.WHITE);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(16),dp(20),dp(24));scroll.addView(content);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return i;});scroll.requestApplyInsets();
  text(content,"付费实验",26,true);text(content,"仅技术测试 · 不会扣款 · 不改变 God 权限",13,false);
  LinearLayout current=card("本地模拟权益");text(current,"测试账号 "+owner+" · 第 "+(session.now()/ScenarioSession.DAY)+" 天",13,false);text(current,tierLabel(session.tier(owner)),28,true);text(current,notice,14,false);
  button(current,"切换测试账号",()->{owner=owner.equals("A")?"B":"A";notice="切换账号不会转移购买。";render();});
  LinearLayout sdk=card("Google Play SDK · 9.1.0");text(sdk,probeStatus,14,false);Button check=button(sdk,probing?"正在检测…":"检查 Play 环境",()->{probing=true;probeStatus="正在连接官方SDK…";render();probe.check(this,result->{if(isDestroyed())return;probing=false;probeStatus=result;render();});});check.setEnabled(!probing);
  LinearLayout models=card("购买模型");
  RadioGroup group=new RadioGroup(this);for(ScenarioEngine.Product p:ScenarioEngine.Product.values()){
   RadioButton b=new RadioButton(this);b.setId(View.generateViewId());b.setText(productLabel(p));b.setTextColor(ink);b.setMinHeight(dp(48));b.setChecked(product==p);b.setOnClickListener(v->{product=p;render();});group.addView(b);
  }models.addView(group);text(models,"没有商店报价。买断仅授予基础权益；订阅档位按有效期授予。",13,false);
  button(models,"模拟待支付",()->event(ScenarioEngine.State.PENDING));button(models,"模拟未验单回调",()->event(ScenarioEngine.State.UNVERIFIED));button(models,"模拟验单通过",()->event(ScenarioEngine.State.ACTIVE));
  if(product!=ScenarioEngine.Product.LIFETIME){button(models,"模拟取消续费",()->event(ScenarioEngine.State.CANCELED));button(models,"模拟宽限期",()->event(ScenarioEngine.State.GRACE));button(models,"模拟账号保留期",()->event(ScenarioEngine.State.HOLD));}
  button(models,"模拟退款撤权",()->event(ScenarioEngine.State.REFUNDED));
  LinearLayout recovery=card("时间与恢复");button(recovery,"快进 1 天",()->{session.advanceDays(1);notice="模拟时钟前进一天。";render();});button(recovery,"快进 31 天",()->{session.advanceDays(31);notice="模拟时钟前进，系统时间不变。";render();});button(recovery,"清空本地权益视图",()->{session.clearView();notice="仅清空视图，模拟商店记录保留。";render();});button(recovery,"从模拟商店恢复",()->{session.restore();notice="已恢复模拟记录；未访问真实商店订单。";render();});button(recovery,"重置全部模拟",()->{session.reset();notice="模拟已重置。";render();});
  text(content,"本地状态机、SDK环境检测、商店沙箱订单是不同验证层级。本实验未实现真实收款或服务端验单，不能作为生产授权器。重启应用会清空模拟。",12,false);
 }
 private void event(ScenarioEngine.State state){
  ScenarioSession.Outcome result=session.event(owner,product,state);
  notice=switch(result){case APPLIED->"模拟事件："+state.name();case PAYMENT_REQUIRED->"请先模拟验单通过，再测试续费状态。";case HOLD_REQUIRES_PAYMENT->"取消续费不会恢复扣款失败后的权益，请模拟成功付款。";case IGNORED->"已忽略重复或不适用事件；退款后需要重置再演练。";};render();
 }
 private String tierLabel(ScenarioEngine.Tier t){return switch(t){case FREE->"无付费权益";case BASIC->"基础版 · 买断";case PLUS->"Plus 权益";case PRO->"Pro 权益";};}
 private String productLabel(ScenarioEngine.Product p){return switch(p){case LIFETIME->"基础版买断（模拟）";case PLUS->"Plus 月订阅（模拟）";case PRO->"Pro 月订阅（模拟）";};}
 private LinearLayout card(String title){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(dark?0xff111111:0xfff7f7f7);bg.setCornerRadius(dp(14));c.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(16);content.addView(c,lp);text(c,title,17,true);return c;}
 private void text(LinearLayout parent,String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(bold?ink:muted);t.setPadding(0,dp(5),0,dp(5));if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);parent.addView(t);}
 private Button button(LinearLayout parent,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(blue);b.setMinHeight(dp(48));b.setTextSize(14);GradientDrawable bg=new GradientDrawable();bg.setColor(dark?0xff202020:0xffeeeeee);bg.setCornerRadius(dp(10));b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dark?0x22ffffff:0x11000000),bg,null));b.setStateListAnimator(null);b.setElevation(0);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);parent.addView(b,lp);return b;}
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 @Override protected void onResume(){super.onResume();if(content!=null)render();}
 @Override protected void onStop(){super.onStop();probe.close();if(probing){probing=false;probeStatus="检测已随后台暂停，可重试。";}}
 @Override protected void onDestroy(){probe.close();super.onDestroy();}
}
