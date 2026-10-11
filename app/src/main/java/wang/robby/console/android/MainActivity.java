package wang.robby.console.android;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import wang.robby.console.android.auth.AuthSession;
import wang.robby.console.android.auth.AuthCoordinator;

/** Native client. No embedded web content, demo balances, or service credentials. */
public final class MainActivity extends Activity implements AuthCoordinator.Listener {
    private static final int INK=0xff172642, MUTED=0xff66738a, BLUE=0xff315ad9, BG=0xfff5f6fa;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AuthSession auth; private AuthCoordinator coordinator; private MobileApi api; private LedgerActions ledger;
    private String ledgerCursor=null; private final Deque<String> ledgerPages=new ArrayDeque<>();
    private LinearLayout root,content,nav; private TextView heading,session;
    private String group="总览",selectedId=null,project=null;
    private int generation=0; private boolean resumed=false;
    private final Map<String,JSONObject> snapshots=new HashMap<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { coordinator=AuthCoordinator.get(this);auth=coordinator.session(); api=new MobileApi(auth);ledger=new LedgerActions(this,new LedgerActions.Transport(){public JSONObject get(String path)throws Exception{return api.get(path);}public JSONObject post(String path,JSONObject body)throws Exception{return api.post(path,body);}},()->{ledgerCursor=null;ledgerPages.clear();FeatureCatalog.Feature f=find("travel-ledger");if(f!=null)showFeature(f,true);}); }
        catch(Exception e) { new AlertDialog.Builder(this).setMessage("无法打开设备安全存储，请检查屏幕锁定设置").setPositiveButton("关闭",(d,w)->finish()).show(); return; }
        if(state!=null) { group=state.getString("group","总览"); selectedId=state.getString("feature"); project=state.getString("project"); }
        build(); handle(getIntent());
        if(Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
    }
    @Override protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state);state.putString("group",group);state.putString("feature",selectedId);state.putString("project",project);}
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent) {
        Uri uri=intent.getData(); if(uri==null)return;
        intent.setData(null); coordinator.callback(uri);
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(coordinator!=null){coordinator.attach(this);build();}}
    @Override protected void onPause(){resumed=false;if(coordinator!=null)coordinator.detach(this);super.onPause();}
    @Override public void onAuthChanged(){if(!resumed||isDestroyed())return;++generation;snapshots.clear();build();}
    @Override public void onAuthorizationReady(String url){
        if(!resumed||isDestroyed())return;
        session.setText("请在浏览器完成 Passkey 登录");
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}
        catch(ActivityNotFoundException e){error("无法打开登录","手机需要可处理 HTTPS 登录的浏览器。");}
    }
    @Override public void onAuthError(String message){if(resumed&&!isDestroyed()){build();error("登录未完成",message);}}
    private void build() {
        boolean logged=auth.hasSession();
        if(logged) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setPadding(dp(22),dp(16),dp(20),dp(12));header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);
        heading=text("God 工作台",26,INK,true);titles.addView(heading);
        session=text(logged?"已登录 · 私人工作台":"Robby Console · Android",12,MUTED,false);titles.addView(session);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button account=button(logged?"账号":"登录",()->account());account.setContentDescription(logged?"账号与退出登录":"登录 God 工作台");header.addView(account);root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(20));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setPadding(dp(6),dp(5),dp(6),dp(5));nav.setBackgroundColor(Color.WHITE);
        for(String label:List.of("总览","洞察","资产","工具","设置")) {Button b=button(label,()->{++generation;group=label;selectedId=null;project=null;build();});b.setTextSize(13);b.setPadding(0,dp(12),0,dp(12));b.setTextColor(group.equals(label)?BLUE:MUTED);b.setBackground(round(group.equals(label)?0xffeaf0ff:Color.WHITE,10));nav.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));}root.addView(nav);
        if(selectedId!=null){FeatureCatalog.Feature f=find(selectedId);if(f!=null){showFeature(f,false);return;}selectedId=null;}
        if(group.equals("总览"))home(); else if(group.equals("设置"))settings(); else catalog(group);
    }
    private void home() {
        LinearLayout hero=card();hero.setPadding(dp(22),dp(22),dp(22),dp(22));hero.setBackground(round(INK,22));
        hero.addView(text("你的私人工作台",24,Color.WHITE,true));TextView sub=text("数据、资产与常用工具，随身查看。",14,0xffc5d0e6,false);sub.setPadding(0,dp(10),0,dp(18));hero.addView(sub);
        Button b=button(auth.hasSession()?"刷新工作台":"使用 Passkey 登录",()->{if(auth.hasSession()){FeatureCatalog.Feature f=find("overview");if(f==null)f=FeatureCatalog.all().get(0);open(f);}else login();});b.setBackground(round(BLUE,12));b.setTextColor(Color.WHITE);hero.addView(b);
        if(!auth.hasSession()){TextView hint=text("登录后读取真实数据",12,0xffb7c5df,false);hint.setPadding(0,dp(12),0,0);hero.addView(hint);}
        section("常用功能");
        for(String needle:List.of("旅行","剪贴板","OpenAI","域名")){FeatureCatalog.Feature f=FeatureCatalog.all().stream().filter(x->x.title.contains(needle)).findFirst().orElse(null);if(f!=null)featureRow(f);}
        section("全部功能");
        for(String label:List.of("洞察","资产","工具")) row(label, label.equals("洞察")?"访问、行情与用量":label.equals("资产")?"基础设施、域名与运行状态":"账本、图片与内容管理",()->{group=label;selectedId=null;build();});
    }
    private void catalog(String category) {section(category);for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals(category))featureRow(f);}
    private void featureRow(FeatureCatalog.Feature f){row(f.title,f.subtitle,()->open(f));}
    private void open(FeatureCatalog.Feature f){++generation;selectedId=f.id;project=null;ledgerCursor=null;ledgerPages.clear();build();}
    private void showFeature(FeatureCatalog.Feature f,boolean refresh) {
        content.removeAllViews();heading.setText(f.title);
        LinearLayout controls=new LinearLayout(this);controls.addView(button("‹ 返回",this::goBack));controls.addView(button("刷新",()->showFeature(f,true)));content.addView(controls);
        if(!auth.hasSession()){section(f.title);TextView t=text("登录后查看此功能的真实数据。",15,MUTED,false);t.setPadding(0,dp(20),0,dp(20));content.addView(t);content.addView(button("使用 Passkey 登录",this::login));return;}
        String path=f.path;
        if(f.path.equals("travel-ledger/current/projects")&&project!=null)path="travel-ledger/current/snapshot?project="+Uri.encode(project)+"&limit=40"+(ledgerCursor==null?"":"&cursor="+Uri.encode(ledgerCursor));
        String key=path; JSONObject cached=key.startsWith("travel-ledger/")?null:snapshots.get(key);
        LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);content.addView(result);
        if(cached!=null){render(f,result,cached);TextView stamp=text(refresh?"正在更新，暂时保留上次结果":"上次读取结果 · 可刷新更新",12,MUTED,false);result.addView(stamp);}
        else result.addView(text("正在读取…",15,MUTED,false));
        if(cached!=null&&!refresh)return;
        int epoch=++generation;
        worker.execute(()->{try{JSONObject data=api.get(key);runOnUiThread(()->{if(epoch!=generation||isDestroyed())return;if(!key.startsWith("travel-ledger/"))snapshots.put(key,data);result.removeAllViews();render(f,result,data);});}
        catch(Exception e){runOnUiThread(()->{if(epoch!=generation||isDestroyed())return;if(cached==null)result.removeAllViews();TextView warning=text(safeError(e),15,0xffa14530,false);warning.setPadding(0,dp(18),0,dp(12));result.addView(warning);result.addView(button("重试",()->showFeature(f,true)));});}});
    }
    private void render(FeatureCatalog.Feature f,LinearLayout result,JSONObject data) {
        try{
            if(f.path.equals("travel-ledger/current/projects")&&project==null){
                if(data.optInt("schemaVersion",-1)!=1)throw new IllegalArgumentException();
                JSONArray projects=data.getJSONArray("projects");if(projects.length()>128)throw new IllegalArgumentException();
                for(int i=0;i<projects.length();i++){JSONObject p=projects.getJSONObject(i);String id=p.getString("id");if(!id.matches("[a-f0-9]{32}|new-zealand-south-island-2026"))throw new IllegalArgumentException();
                    Button b=button(p.optString("name",p.optString("title","账本")),()->{project=id;ledgerCursor=null;ledgerPages.clear();showFeature(f,true);});result.addView(b);}
                if(projects.length()==0)result.addView(text("暂无可访问的账本",15,MUTED,false));return;
            }
            FeatureRenderer.render(this,result,f,data);
            if(f.path.equals("travel-ledger/current/projects")&&project!=null) {
                JSONObject page=data.optJSONObject("page");
                if(!ledgerPages.isEmpty())result.addView(button("上一页",()->{String previous=ledgerPages.pop();ledgerCursor=previous.isEmpty()?null:previous;showFeature(f,true);}));
                if(page!=null&&page.optBoolean("hasMore")){String next=page.optString("nextCursor");if(next.matches("[A-Za-z0-9_-]{1,1500}\\.[A-Za-z0-9_-]{43}"))result.addView(button("下一页",()->{ledgerPages.push(ledgerCursor==null?"":ledgerCursor);ledgerCursor=next;showFeature(f,true);}));}
                result.addView(button("记一笔",()->ledger.showEntry(project,null)));
                JSONArray entries=data.optJSONArray("entries");
                if(entries!=null&&entries.length()>0)result.addView(button("编辑本页账目",()->{
                    String[] labels=new String[entries.length()];for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);labels[i]=e.optString("merchant",e.optString("category"))+" · "+e.optString("amount")+" "+e.optString("currency");}
                    new AlertDialog.Builder(this).setTitle("选择账目").setItems(labels,(d,index)->ledger.showEntry(project,entries.optJSONObject(index).optString("id"))).setNegativeButton("取消",null).show();
                }));
                result.addView(button("人员管理",()->ledger.showPeople(project)));
                result.addView(button("最近删除",()->ledger.showTrash(project)));
                result.addView(button("查询上次提交结果",()->ledger.recover()));
                result.addView(button("切换账本",()->{project=null;ledgerCursor=null;ledgerPages.clear();showFeature(f,true);}));
            }
        }catch(Exception e){result.removeAllViews();result.addView(text("数据格式与当前版本不匹配，请刷新或更新应用。",15,0xffa14530,false));}
    }
    private void settings(){
        section("账号");row(auth.hasSession()?"已登录":"尚未登录","Passkey · 设备加密存储",this::account);
        section("管理");for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals("设置"))featureRow(f);
        section("关于");row("隐私与数据","无第三方统计 · 不保存网页 Cookie",()->new AlertDialog.Builder(this).setTitle("隐私与数据").setMessage("应用仅连接 Robby 的 God 私有接口。登录在系统浏览器完成，应用不接触密码或 Passkey 私钥。会话使用 Android Keystore 加密且不参加备份。业务数据仅在内存中展示，进入后台即清除；登录后禁止截屏与最近任务预览。退出会清除本机令牌。\n\n当前版本包含原生只读功能；写入功能正在按 iOS 合同逐项迁移。").setPositiveButton("知道了",null).show());
        row("God 工作台","Android 0.1.0 · 原生版本",()->{});
    }
    private void login(){if(coordinator.busy())return;session.setText("正在准备安全登录…");coordinator.login();}
    private void account(){if(!auth.hasSession()){login();return;}new AlertDialog.Builder(this).setTitle("God 账号").setMessage("会话保存在此设备的加密存储中。").setNegativeButton("取消",null).setPositiveButton("退出登录",(d,w)->{++generation;snapshots.clear();selectedId=null;project=null;content.removeAllViews();ledger.close();coordinator.logout();group="总览";build();}).show();}
    private void goBack(){if(selectedId!=null){++generation;selectedId=null;project=null;build();}else if(!group.equals("总览")){group="总览";build();}else moveTaskToBack(true);}
    // API 33+ uses the registered OnBackInvokedDispatcher; this is the API 30-32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    @Override protected void onStop(){super.onStop();if(ledger!=null)ledger.close();++generation;snapshots.clear();if(auth!=null&&auth.hasSession()&&content!=null){content.removeAllViews();content.addView(text("God 工作台",24,INK,true));}}
    @Override protected void onDestroy(){++generation;if(ledger!=null)ledger.dispose();worker.shutdownNow();super.onDestroy();}
    private FeatureCatalog.Feature find(String id){return FeatureCatalog.all().stream().filter(f->f.id.equals(id)).findFirst().orElse(null);}
    private void section(String title){TextView t=text(title,17,INK,true);t.setPadding(dp(2),dp(20),0,dp(12));content.addView(t);}
    private LinearLayout card(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(15),dp(16),dp(15));box.setBackground(round(Color.WHITE,18));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);content.addView(box,lp);return box;}
    private void row(String title,String subtitle,Runnable action){LinearLayout box=card();box.setMinimumHeight(dp(76));TextView t=text(title+"  ›",17,INK,true);box.addView(t);TextView sub=text(subtitle,13,MUTED,false);sub.setPadding(0,dp(6),0,0);box.addView(sub);box.setOnClickListener(v->action.run());box.setFocusable(true);box.setContentDescription(title+"，"+subtitle);}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(BLUE);b.setAllCaps(false);b.setMinHeight(dp(48));b.setTextSize(14);b.setElevation(0);b.setStateListAnimator(null);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22315ad9),round(0xffeaf0ff,12),null));b.setPadding(dp(16),dp(10),dp(16),dp(10));b.setOnClickListener(v->action.run());return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String safeError(Exception e){String m=e.getMessage();return m!=null&&m.matches("[\\p{IsHan}，。、；：！？ ·…A-Za-z0-9-]{1,100}")?m:"连接未完成，请检查网络后重试";}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void error(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();}
}
