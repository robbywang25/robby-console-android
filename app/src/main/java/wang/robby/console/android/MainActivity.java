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
import wang.robby.console.android.auth.DeviceAuthorization;

/** Native client. No embedded web content, demo balances, or service credentials. */
public class MainActivity extends Activity implements AuthCoordinator.Listener {
    private int INK, MUTED, ACCENT, BG; private ConsoleTheme theme;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AuthSession auth; private AuthCoordinator coordinator; private MobileApi api; private LedgerActions ledger;
    private String ledgerCursor=null; private final Deque<String> ledgerPages=new ArrayDeque<>();
    private FrameLayout root; private LinearLayout content,nav,toolbar; private TextView heading,session;
    private String group="总览",selectedId=null,project=null;
    private volatile int generation=0; private boolean resumed=false;
    private final Map<String,JSONObject> snapshots=new HashMap<>();
    @Override public void onCreate(Bundle state) {
        PreviewMode.configure(this,getIntent());
        super.onCreate(state);
        theme=new ConsoleTheme(this);INK=theme.ink;MUTED=theme.muted;ACCENT=theme.accentStrong;BG=theme.canvas;
        try { coordinator=AuthCoordinator.get(this);auth=coordinator.session(); api=new MobileApi(auth);ledger=new LedgerActions(this,new LedgerActions.Transport(){public JSONObject get(String path)throws Exception{return read(path);}public JSONObject post(String path,JSONObject body)throws Exception{if(PreviewMode.isBuild())throw new IllegalStateException("界面预览不执行写入");return api.post(path,body);}},()->{ledgerCursor=null;ledgerPages.clear();FeatureCatalog.Feature f=find("travel-ledger");if(f!=null)showFeature(f,true);}); }
        catch(Exception e) { new AlertDialog.Builder(this).setMessage("无法打开设备安全存储，请检查屏幕锁定设置").setPositiveButton("关闭",(d,w)->finish()).show(); return; }
        if(state!=null) { group=state.getString("group","总览"); selectedId=state.getString("feature"); project=state.getString("project"); }
        build(); handle(getIntent());
        if(Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
    }
    @Override protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state);state.putString("group",group);state.putString("feature",selectedId);state.putString("project",project);}
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent) {
        if(PreviewMode.isBuild()){intent.setData(null);return;}
        Uri uri=intent.getData(); if(uri==null)return;
        intent.setData(null); coordinator.callback(uri);
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(coordinator!=null){coordinator.attach(this);}}
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
        ++generation;dashboardResult=null;
        boolean logged=loggedIn();
        DeviceAuthorization device=coordinator.deviceAuthorization();
        if(!PreviewMode.isBuild()&&(logged||device!=null||coordinator.busy())) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=new FrameLayout(this);root.setBackground(theme.backdrop());setContentView(root);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsController insetController=getWindow().getInsetsController();
        if(insetController!=null)insetController.setSystemBarsAppearance(theme.dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setBackground(theme.backdrop());root.addView(body,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(this);header.setPadding(dp(18),dp(12),dp(16),dp(12));header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);
        session=text(PreviewMode.isBuild()?"界面预览 · 合成数据":"God 工作台",12,MUTED,false);titles.addView(session);
        heading=text(selectedId==null?group:"God",26,INK,true);titles.addView(heading);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout account=new FrameLayout(this);account.setBackground(theme.ripple(theme.glass(false)));account.setContentDescription(logged?"账号与退出登录":"登录 God 工作台");account.setFocusable(true);account.setOnClickListener(v->account());
        ImageView avatar=theme.icon("user",ACCENT,24);FrameLayout.LayoutParams avatarParams=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER);account.addView(avatar,avatarParams);header.addView(account,new LinearLayout.LayoutParams(dp(48),dp(48)));body.addView(header);
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(20),0,dp(20),dp(8));body.addView(toolbar);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(14),dp(8),dp(14),dp(20));scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setBackgroundColor(theme.canvas);nav.setPadding(0,dp(8),0,dp(8));nav.setGravity(Gravity.CENTER_VERTICAL);
        for(String label:List.of("总览","洞察","资产","工具","设置")) {
            boolean selected=group.equals(label);LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(Gravity.CENTER);tab.setPadding(0,0,0,0);
            tab.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,0)));tab.setSelected(selected);tab.setFocusable(true);tab.setContentDescription(label+(selected?"，已选中":""));
            tab.addView(theme.tabIcon(ConsoleTheme.symbol(label),selected,24));tab.setTooltipText(label);
            tab.setOnClickListener(v->{++generation;group=label;selectedId=null;project=null;build();});LinearLayout.LayoutParams tabParams=new LinearLayout.LayoutParams(0,dp(56),1);nav.addView(tab,tabParams);
        }
        View divider=new View(this);divider.setBackgroundColor(theme.line);body.addView(divider,new LinearLayout.LayoutParams(-1,dp(.5f)));body.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());body.setPadding(bars.left,bars.top,bars.right,0);nav.setPadding(0,dp(8),0,bars.bottom+dp(8));return insets;});root.requestApplyInsets();
        if(device!=null){nav.setVisibility(View.GONE);showDeviceAuthorization(device);return;}
        if(selectedId!=null){FeatureCatalog.Feature f=find(selectedId);if(f!=null){showFeature(f,false);return;}selectedId=null;}
        if(group.equals("总览"))home(); else if(group.equals("设置"))settings(); else catalog(group);
    }
    private void home() {
        if(!loggedIn()){
            LinearLayout welcome=card();welcome.addView(theme.icon("key",ACCENT,38));
            TextView title=text("登录你的工作台",24,INK,true);title.setPadding(0,dp(20),0,dp(8));welcome.addView(title);
            TextView hint=text("使用 Passkey，安全查看你的数据与资产。",15,MUTED,false);hint.setPadding(0,0,0,dp(22));welcome.addView(hint);
            Button passkey=button("使用 Passkey 登录",this::login);theme.button(passkey,true);welcome.addView(passkey,new LinearLayout.LayoutParams(-1,-2));
            Button device=button("通过已有设备授权",this::deviceLogin);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,-2);d.topMargin=dp(10);welcome.addView(device,d);
        }else{
            LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("今日概览",17,INK,true);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));top.addView(button("刷新",()->dashboard(true)));content.addView(top);
            dashboardResult=new LinearLayout(this);dashboardResult.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=dp(14);content.addView(dashboardResult,gap);dashboard(false);
        }
        section("常用功能");LinearLayout frequent=groupCard();
        for(String id:List.of("travel-ledger","clipboard","openai","domains")){FeatureCatalog.Feature f=find(id);if(f!=null)featureRow(f,frequent);}
        section("全部功能");LinearLayout all=groupCard();
        for(String label:List.of("洞察","资产","工具"))featureRow(label,label.equals("洞察")?"访问、行情与用量":label.equals("资产")?"基础设施、域名与运行状态":"账本、图片与内容管理",ConsoleTheme.symbol(label),all,()->{group=label;selectedId=null;build();});
    }
    private LinearLayout dashboardResult;
    private void dashboard(boolean refresh){
        if(!resumed)return;
        final LinearLayout target=dashboardResult;if(target==null)return;JSONObject cached=snapshots.get("overview");
        if(cached!=null)FeatureRenderer.render(this,target,find("overview"),cached);
        else {target.removeAllViews();TextView loading=text("正在更新概览…",14,MUTED,false);loading.setPadding(0,dp(12),0,dp(16));target.addView(loading);}
        if(cached!=null&&!refresh)return;int epoch=++generation;
        worker.execute(()->{if(epoch!=generation)return;try{JSONObject data=read("overview");runOnUiThread(()->{if(epoch!=generation||!resumed||isDestroyed())return;snapshots.put("overview",data);FeatureRenderer.render(this,target,find("overview"),data);nav.invalidate();});}
        catch(Exception e){runOnUiThread(()->{if(epoch!=generation||!resumed||isDestroyed())return;if(cached==null)target.removeAllViews();LinearLayout c=theme.card(target);c.addView(text("概览暂未更新",17,INK,true));c.addView(text(safeError(e),14,MUTED,false));c.addView(button("重试",()->dashboard(true)));});}});
    }
    private void catalog(String category) {LinearLayout list=groupCard();for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals(category))featureRow(f,list);}
    private void featureRow(FeatureCatalog.Feature f){featureRow(f,groupCard());}
    private void featureRow(FeatureCatalog.Feature f,LinearLayout list){featureRow(f.title,f.subtitle,ConsoleTheme.symbol(f.id),list,()->open(f));}
    private LinearLayout groupCard(){LinearLayout c=card();c.setPadding(dp(4),dp(2),dp(4),dp(2));return c;}
    private void featureRow(String title,String subtitle,String symbol,LinearLayout parent,Runnable action){
        if(parent.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(.5f));d.setMargins(dp(63),0,dp(12),0);parent.addView(divider,d);}
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(10),dp(10));row.setMinimumHeight(dp(68));row.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,16)));row.setFocusable(true);row.setContentDescription(title+"，"+subtitle);row.setOnClickListener(v->action.run());
        FrameLayout iconBox=new FrameLayout(this);iconBox.setBackground(theme.shape(theme.soft,10));ImageView icon=theme.icon(symbol,ACCENT,22);iconBox.addView(icon,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));row.addView(iconBox,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(13),0,dp(8),0);labels.addView(text(title,16,INK,true));TextView detail=text(subtitle,12,MUTED,false);detail.setPadding(0,dp(3),0,0);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.addView(theme.icon("chevron",MUTED,14));parent.addView(row);
    }
    private void open(FeatureCatalog.Feature f){++generation;selectedId=f.id;project=null;ledgerCursor=null;ledgerPages.clear();build();}
    private void showFeature(FeatureCatalog.Feature f,boolean refresh) {
        content.removeAllViews();heading.setText(f.title);
        toolbar.removeAllViews();toolbar.addView(button("‹ 返回",this::goBack));toolbar.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));toolbar.addView(button("刷新",()->showFeature(f,true)));
        if(!loggedIn()){section(f.title);TextView t=text("登录后查看此功能的真实数据。",15,MUTED,false);t.setPadding(0,dp(20),0,dp(20));content.addView(t);content.addView(button("通过已有设备授权",this::deviceLogin));content.addView(button("使用 Passkey 登录",this::login));return;}
        String path=f.path;
        if(f.path.equals("travel-ledger/current/projects")&&project!=null)path="travel-ledger/current/snapshot?project="+Uri.encode(project)+"&limit=40"+(ledgerCursor==null?"":"&cursor="+Uri.encode(ledgerCursor));
        String key=path; JSONObject cached=key.startsWith("travel-ledger/")?null:snapshots.get(key);
        LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);content.addView(result);
        if(cached!=null){render(f,result,cached);TextView stamp=text(refresh?"正在更新，暂时保留上次结果":"上次读取结果 · 可刷新更新",12,MUTED,false);result.addView(stamp);}
        else result.addView(text("正在读取…",15,MUTED,false));
        if(cached!=null&&!refresh)return;
        if(!resumed)return;
        int epoch=++generation;
        worker.execute(()->{if(epoch!=generation)return;try{JSONObject data=read(key);runOnUiThread(()->{if(epoch!=generation||isDestroyed())return;if(!key.startsWith("travel-ledger/"))snapshots.put(key,data);result.removeAllViews();session.setText(PreviewMode.isBuild()?"界面预览 · 合成数据":"God 工作台");render(f,result,data);});}
        catch(Exception e){runOnUiThread(()->{if(epoch!=generation||isDestroyed())return;if(!loggedIn()){snapshots.clear();ledger.close();selectedId=null;project=null;group="总览";build();error("请重新登录",safeError(e));return;}if(e instanceof MobileApi.ApiError&&((MobileApi.ApiError)e).status==401){snapshots.clear();result.removeAllViews();session.setText("会话需要重新验证");}else if(cached==null)result.removeAllViews();TextView warning=text(safeError(e),15,theme.danger,false);warning.setPadding(0,dp(18),0,dp(12));result.addView(warning);result.addView(button("重试",()->showFeature(f,true)));});}});
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
                addAction(result,"记一笔",()->ledger.showEntry(project,null));
                JSONArray entries=data.optJSONArray("entries");
                if(entries!=null&&entries.length()>0)result.addView(button("编辑本页账目",()->{
                    String[] labels=new String[entries.length()];for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);labels[i]=e.optString("merchant",e.optString("category"))+" · "+e.optString("amount")+" "+e.optString("currency");}
                    new AlertDialog.Builder(this).setTitle("选择账目").setItems(labels,(d,index)->ledger.showEntry(project,entries.optJSONObject(index).optString("id"))).setNegativeButton("取消",null).show();
                }));
                addAction(result,"人员管理",()->ledger.showPeople(project));
                addAction(result,"最近删除",()->ledger.showTrash(project));
                addAction(result,"查询上次提交结果",()->ledger.recover());
                result.addView(button("切换账本",()->{project=null;ledgerCursor=null;ledgerPages.clear();showFeature(f,true);}));
            }
        }catch(Exception e){result.removeAllViews();result.addView(text("数据格式与当前版本不匹配，请刷新或更新应用。",15,theme.danger,false));}
    }
    private void settings(){
        section("账号");row(loggedIn()?"已登录":"尚未登录","Passkey · 设备加密存储",this::account);
        section("管理");LinearLayout management=groupCard();for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals("设置"))featureRow(f,management);
        section("关于");row("隐私与数据","无第三方统计 · 不保存网页 Cookie",()->new AlertDialog.Builder(this).setTitle("隐私与数据").setMessage("应用仅连接 Robby 的 God 私有接口。登录在系统浏览器完成，应用不接触密码或 Passkey 私钥。会话使用 Android Keystore 加密且不参加备份。业务数据仅在内存中展示，进入后台即清除；登录后禁止截屏与最近任务预览。退出会清除本机令牌。\n\n可查看原生数据，并在确认后管理账目和人员。").setPositiveButton("知道了",null).show());
        row("God 工作台","Android 0.3.4",()->{});
    }
    @Override public void onDeviceAuthorization(DeviceAuthorization info){if(resumed&&!isDestroyed())build();}
    private void showDeviceAuthorization(DeviceAuthorization info){
        heading.setText("授权此设备");session.setText("等待本人确认");
        LinearLayout c=card();c.addView(text("1  打开安全验证页",19,INK,true));
        TextView url=text(info.verificationUri.toString(),15,ACCENT,false);url.setPadding(0,dp(14),0,dp(22));c.addView(url);
        c.addView(text("使用已经能登录 God 的电脑或其他设备。",14,MUTED,false));
        c=card();c.addView(text("2  输入这组一次性代码",19,INK,true));
        TextView code=text(info.userCode,30,INK,true);code.setLetterSpacing(0.12f);code.setPadding(0,dp(22),0,dp(22));code.setGravity(Gravity.CENTER);c.addView(code);
        c.addView(text("核对应用为 God 工作台 Android，再确认授权。不要把代码发给其他人。",14,MUTED,false));
        c=card();c.addView(text("3  完成后自动进入工作台",19,INK,true));
        c.addView(text("代码仅在本次验证有效；请保持两台设备联网。",14,MUTED,false));
        content.addView(button("取消验证",()->{coordinator.cancelDeviceLogin();build();}));
    }
    private void deviceLogin(){if(PreviewMode.isBuild()){error("界面预览","此预览不连接真实账号。");return;}if(coordinator.busy())return;coordinator.loginWithDevice();}
    private void login(){if(PreviewMode.isBuild()){error("界面预览","此预览不连接真实账号。");return;}if(coordinator.busy())return;session.setText("正在准备安全登录…");coordinator.login();}
    private void account(){if(PreviewMode.isBuild()){error("界面预览","仅包含合成展示数据，不连接账号或服务。");return;}if(!loggedIn()){new AlertDialog.Builder(this).setTitle("登录 God 工作台").setItems(new String[]{"通过已登录设备授权","使用 Passkey 登录"},(d,w)->{if(w==0)deviceLogin();else login();}).setNegativeButton("取消",null).show();return;}new AlertDialog.Builder(this).setTitle("God 账号").setMessage("会话保存在此设备的加密存储中。").setNegativeButton("取消",null).setPositiveButton("退出登录",(d,w)->{++generation;snapshots.clear();selectedId=null;project=null;content.removeAllViews();ledger.close();coordinator.logout();group="总览";build();}).show();}
    private void goBack(){if(coordinator.deviceAuthorization()!=null){coordinator.cancelDeviceLogin();build();return;}if(selectedId!=null){++generation;selectedId=null;project=null;build();}else if(!group.equals("总览")){group="总览";build();}else moveTaskToBack(true);}
    // API 33+ uses the registered OnBackInvokedDispatcher; this is the API 30-32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    @Override protected void onStop(){super.onStop();dashboardResult=null;if(ledger!=null)ledger.close();++generation;snapshots.clear();if(auth!=null&&loggedIn()&&content!=null){content.removeAllViews();content.addView(text("God 工作台",24,INK,true));}}
    @Override protected void onDestroy(){++generation;if(ledger!=null)ledger.dispose();worker.shutdownNow();super.onDestroy();}
    private FeatureCatalog.Feature find(String id){return FeatureCatalog.all().stream().filter(f->f.id.equals(id)).findFirst().orElse(null);}
    private void section(String title){TextView t=text(title,17,INK,true);t.setAccessibilityHeading(true);t.setPadding(dp(2),dp(20),0,dp(12));content.addView(t);}
    private void addAction(LinearLayout target,String label,Runnable action){Button b=button(label,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);target.addView(b,p);}
    private LinearLayout card(){return theme.card(content);}
    private void row(String title,String subtitle,Runnable action){featureRow(title,subtitle,title.equals("隐私与数据")?"shield":title.equals("God 工作台")?"grid":"user",groupCard(),action);}
    private TextView text(String s,int size,int color,boolean bold){TextView t=theme.text(s,size,bold);t.setTextColor(color);return t;}
    private Button button(String label,Runnable action){return theme.button(label,action);}
    private GradientDrawable round(int color,int radius){return theme.shape(color,radius);}
    private boolean loggedIn(){return PreviewMode.isBuild()?PreviewMode.loggedIn():auth.hasSession();}
    private JSONObject read(String path)throws Exception{return PreviewMode.isBuild()?PreviewMode.data(path):api.get(path);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String safeError(Exception e){String m=e.getMessage();return m!=null&&m.matches("[\\p{IsHan}，。、；：！？ ·…A-Za-z0-9-]{1,100}")?m:"连接未完成，请检查网络后重试";}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void error(String title,String message){AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null).create();d.show();theme.dialog(d);}
}
