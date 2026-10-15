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
public class MainActivity extends Activity implements AuthCoordinator.Listener, FeatureRenderer.Opener {
    private int INK, MUTED, ACCENT, BG; private ConsoleTheme theme;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AuthSession auth; private AuthCoordinator coordinator; private MobileApi api; private LedgerActions ledger;
    private String ledgerCursor=null; private final Deque<String> ledgerPages=new ArrayDeque<>();
    private FrameLayout root,leading; private LinearLayout content,trailing; private GlassPane nav; private TextView heading,session;
    private static final List<String> TABS=List.of("总览","洞察","资产","工具","设置");
    /** Tab whose lens was last drawn, so the lens can glide from it; -1 before the first frame. */
    private int shownTab=-1; private int enterFrom=0; private View refreshIcon;
    /** Pseudo feature id for 关于 God 工作台; it has no API path and lives in the 设置 tab's reading context. */
    private static final String ABOUT="about";
    /** Release-note cards the reader expanded; process memory only, like the other reading context. */
    private final Set<String> expandedNotes=new HashSet<>();
    private String group="总览",selectedId=null,project=null;
    private volatile int generation=0; private boolean resumed=false;
    private final Map<String,JSONObject> snapshots=new HashMap<>();
    private final NavigationMemory navigation=new NavigationMemory();
    private ScrollView scroll; private String displayedRoute; private boolean viewportReady;
    private void saveViewport(){if(viewportReady&&scroll!=null)navigation.rememberOffset(displayedRoute,scroll.getScrollY());}
    private String routeKey(){return group+"/"+selectedId+"/"+project+"/"+ledgerPages.size();}
    private void restoreViewport(){
        final ScrollView target=scroll;final String route=routeKey();displayedRoute=route;
        if(target==null)return;final int wanted=navigation.offset(route);
        target.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){
                if(target.getViewTreeObserver().isAlive())target.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if(target!=scroll||!route.equals(routeKey())||!resumed)return;
                target.scrollTo(0,wanted);viewportReady=true;
            }
        });target.requestLayout();
    }
    private void switchTab(String next){
        if(group.equals(next))return;
        saveViewport();NavigationMemory.Destination target=navigation.switchTo(group,next,new NavigationMemory.Destination(selectedId,project,ledgerCursor,ledgerPages));
        group=next;selectedId=target.feature;project=target.project;ledgerCursor=target.cursor;ledgerPages.clear();ledgerPages.addAll(target.pages);enterFrom=2;build();
    }
    @Override public void onCreate(Bundle state) {
        PreviewMode.configure(this,getIntent());
        super.onCreate(state);
        theme=new ConsoleTheme(this);INK=theme.ink;MUTED=theme.muted;ACCENT=theme.accent;BG=theme.canvas;
        try { coordinator=AuthCoordinator.get(this);auth=coordinator.session(); api=new MobileApi(auth);ledger=new LedgerActions(this,new LedgerActions.Transport(){public JSONObject get(String path)throws Exception{return read(path);}public JSONObject post(String path,JSONObject body)throws Exception{if(PreviewMode.isBuild())throw new IllegalStateException("界面预览不执行写入");return api.post(path,body);}},()->{ledgerCursor=null;ledgerPages.clear();FeatureCatalog.Feature f=find("travel-ledger");if(f!=null)showFeature(f,true);}); }
        catch(Exception e) { new AlertDialog.Builder(this).setMessage("无法打开设备安全存储，请检查屏幕锁定设置").setCancelable(false).setPositiveButton("关闭",(d,w)->finish()).show(); return; }
        if(state!=null) { group=state.getString("group","总览"); selectedId=state.getString("feature"); project=state.getString("project");navigation.rememberOffset(routeKey(),state.getInt("scroll",0)); }
        build();
        // A recreated Activity (process death) or a Recents relaunch re-delivers the original launch
        // Intent. Its one-time OAuth callback was already consumed, so replaying it only produced a
        // spurious "登录未完成或已过期" error. Fresh callbacks always arrive as a new launch or onNewIntent.
        if(state==null&&!launchedFromHistory(getIntent()))handle(getIntent()); else getIntent().setData(null);
        if(Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
    }
    @Override protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state);state.putString("group",group);state.putString("feature",selectedId);state.putString("project",project);saveViewport();state.putInt("scroll",ledgerCursor==null?navigation.offset(routeKey()):0);}
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);if(!launchedFromHistory(intent))handle(intent);else intent.setData(null);}
    static boolean launchedFromHistory(Intent intent){return intent!=null&&(intent.getFlags()&Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)!=0;}
    private void handle(Intent intent) {
        if(PreviewMode.isBuild()||coordinator==null){intent.setData(null);return;}
        Uri uri=intent.getData(); if(uri==null)return;
        intent.setData(null); coordinator.callback(uri);
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(coordinator!=null){coordinator.attach(this);}}
    @Override protected void onPause(){resumed=false;if(coordinator!=null)coordinator.detach(this);super.onPause();}
    @Override public void onAuthChanged(){if(!resumed||isDestroyed())return;++generation;snapshots.clear();if(!loggedIn()){navigation.clear();selectedId=null;project=null;ledgerCursor=null;ledgerPages.clear();viewportReady=false;}build();}
    @Override public void onAuthorizationReady(String url){
        if(!resumed||isDestroyed())return;
        session.setText("请在浏览器完成 Passkey 登录");
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}
        catch(ActivityNotFoundException e){error("无法打开登录","手机需要可处理 HTTPS 登录的浏览器。");}
    }
    @Override public void onAuthError(String message){if(resumed&&!isDestroyed()){build();error("登录未完成",message);}}
    private void build() {
        saveViewport();viewportReady=false;++generation;dashboardResult=null;
        boolean logged=loggedIn();
        if(!logged){navigation.clear();project=null;ledgerCursor=null;ledgerPages.clear();}
        DeviceAuthorization device=coordinator.deviceAuthorization();
        if(!PreviewMode.isBuild()&&(logged||device!=null||coordinator.busy())) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=new FrameLayout(this);setContentView(root);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsController insetController=getWindow().getInsetsController();
        if(insetController!=null)insetController.setSystemBarsAppearance(theme.dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        // The stage holds the sky and the page. The floating dock blurs the stage, never itself.
        FrameLayout stage=new FrameLayout(this);stage.setBackground(theme.backdrop());root.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);stage.addView(body,new FrameLayout.LayoutParams(-1,-1));
        FeatureCatalog.Feature detail=selectedId==null?null:find(selectedId);boolean about=ABOUT.equals(selectedId);if(detail==null&&!about)selectedId=null;
        boolean nested=device!=null||detail!=null||about;
        // Opening 关于 reads this version's notes, so the red dots must already be gone in this frame's dock.
        if(about&&!versionName().isEmpty())prefs().edit().putString("notesSeen",versionName()).apply();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),dp(8),dp(16),dp(nested?8:6));header.setMinimumHeight(dp(64));
        leading=new FrameLayout(this);trailing=new LinearLayout(this);trailing.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);refreshIcon=null;
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);
        session=text(PreviewMode.isBuild()?"界面预览 · 合成数据":!nested&&group.equals("总览")?today():"God 工作台",12,MUTED,false);
        if(nested){
            // Detail pages: WeChat-style centred title between two round glass controls of equal width.
            heading=text("",17,INK,true);heading.setMaxLines(2);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.setGravity(Gravity.CENTER);heading.setAccessibilityHeading(true);
            session.setGravity(Gravity.CENTER);session.setSingleLine(true);session.setEllipsize(android.text.TextUtils.TruncateAt.END);titles.addView(heading);titles.addView(session);
            header.addView(leading,new LinearLayout.LayoutParams(dp(104),dp(48)));header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));header.addView(trailing,new LinearLayout.LayoutParams(dp(104),dp(48)));
        }else{
            heading=theme.title(ConsoleTheme.tabLabel(group));titles.addView(session);titles.addView(heading);
            header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));header.addView(trailing,new LinearLayout.LayoutParams(-2,dp(48)));
        }
        body.addView(header);
        scroll=new ScrollView(this){@Override protected float getBottomFadingEdgeStrength(){return 0f;}};
        scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        // Scroll-edge effect: content dissolves under the header instead of being cut at a hard line.
        scroll.setVerticalFadingEdgeEnabled(true);scroll.setFadingEdgeLength(dp(28));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(4),dp(16),dp(120));scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        int current=TABS.indexOf(group);
        // Content keeps running under the dock to the screen edge, then fades into the sky below the gesture bar.
        View fade=new View(this);fade.setBackground(theme.bottomFade());fade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);stage.addView(fade,new FrameLayout.LayoutParams(-1,dp(40),Gravity.BOTTOM));
        final int dockHeight=dp(getResources().getConfiguration().fontScale>=1.3f?72:64);
        nav=new GlassPane(theme,stage);FrameLayout.LayoutParams dock=new FrameLayout.LayoutParams(-1,dockHeight,Gravity.BOTTOM);dock.leftMargin=dock.rightMargin=dp(16);root.addView(nav,dock);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(6),dp(6),dp(6),dp(6));
        View lens=new View(this);lens.setBackground(theme.lens());lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        nav.lens(lens,TABS.size(),current,shownTab,dp(6));nav.addView(tabs,new FrameLayout.LayoutParams(-1,-1));shownTab=current;
        for(String label:TABS) {
            boolean selected=group.equals(label);String name=ConsoleTheme.tabLabel(label);LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(Gravity.CENTER);
            tab.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,0),-1));tab.setSelected(selected);tab.setFocusable(true);tab.setClickable(true);tab.setContentDescription(name+(selected?"，已选中":""));tab.setTooltipText(name);
            if(label.equals("设置")&&notesUnread()){
                FrameLayout badge=new FrameLayout(this);badge.addView(theme.tabIcon(ConsoleTheme.symbol(label),selected,24),new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
                badge.addView(theme.dot(theme.unread,8),new FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP|Gravity.END));tab.addView(badge,new LinearLayout.LayoutParams(dp(32),dp(26)));
                tab.setContentDescription(name+(selected?"，已选中":"")+"，有新版本说明");
            }else tab.addView(theme.tabIcon(ConsoleTheme.symbol(label),selected,24));
            TextView caption=text(name,11,selected?INK:MUTED,selected);caption.setPadding(0,dp(2),0,0);caption.setSingleLine(true);caption.setGravity(Gravity.CENTER);caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tab.addView(caption);
            tab.setOnClickListener(v->{if(!group.equals(label))v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);switchTab(label);});theme.press(tab);
            tabs.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
        }
        final LinearLayout page=content;final GlassPane bar=nav;
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            body.setPadding(bars.left,bars.top,bars.right,0);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)bar.getLayoutParams();lp.leftMargin=bars.left+dp(16);lp.rightMargin=bars.right+dp(16);lp.bottomMargin=bars.bottom+dp(10);bar.setLayoutParams(lp);
            fade.getLayoutParams().height=bars.bottom+dp(28);fade.requestLayout();
            page.setPadding(dp(16),dp(4),dp(16),bars.bottom+(bar.getVisibility()==View.GONE?dp(24):dockHeight+dp(10+24)));return insets;});root.requestApplyInsets();
        if(device!=null){nav.setVisibility(View.GONE);showDeviceAuthorization(device);}
        else if(about)about();
        else if(detail!=null)showFeature(detail,false);
        else if(group.equals("总览"))home(); else if(group.equals("设置"))settings(); else catalog(group);
        if(enterFrom!=0){theme.enter(scroll,enterFrom==1?28:enterFrom==-1?-28:0,enterFrom==2?12:0);enterFrom=0;}
    }
    private String today(){return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.SIMPLIFIED_CHINESE));}
    /** Round glass header control; the refresh symbol is remembered so a refresh can spin it as feedback. */
    private FrameLayout circle(String symbol,String label,Runnable action){FrameLayout c=theme.circle(symbol,label,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(48),dp(48));p.leftMargin=dp(4);c.setLayoutParams(p);if("refresh".equals(symbol))refreshIcon=c.getChildAt(0);return c;}
    private void spin(){if(refreshIcon!=null&&theme.effects){refreshIcon.setRotation(0);refreshIcon.animate().rotation(360).setDuration(700).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}}
    private void home() {
        if(!loggedIn()){
            LinearLayout welcome=card();welcome.setGravity(Gravity.CENTER_HORIZONTAL);welcome.setPadding(dp(22),dp(28),dp(22),dp(22));
            FrameLayout mark=new FrameLayout(this);mark.setBackground(theme.glass(-1));mark.addView(theme.tabIcon("grid",true,40),new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));welcome.addView(mark,new LinearLayout.LayoutParams(dp(80),dp(80)));
            TextView title=text("登录你的工作台",24,INK,true);title.setGravity(Gravity.CENTER);title.setPadding(0,dp(18),0,dp(8));welcome.addView(title);
            TextView hint=text("使用 Passkey，安全查看你的数据与资产。",15,MUTED,false);hint.setGravity(Gravity.CENTER);hint.setPadding(0,0,0,dp(22));welcome.addView(hint);
            Button passkey=button("使用 Passkey 登录",this::login);theme.button(passkey,true);welcome.addView(passkey,new LinearLayout.LayoutParams(-1,-2));
            Button device=button("通过已有设备授权",this::deviceLogin);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,-2);d.topMargin=dp(10);welcome.addView(device,d);
        }else{
            trailing.addView(circle("refresh","刷新概览",()->dashboard(true)));
            dashboardResult=new LinearLayout(this);dashboardResult.setOrientation(LinearLayout.VERTICAL);content.addView(dashboardResult,new LinearLayout.LayoutParams(-1,-2));dashboard(false);
        }
        section("常用");quick(List.of("travel-ledger","clipboard","openai","domains"));
        section("全部功能");LinearLayout all=groupCard();
        for(String label:List.of("洞察","资产","工具"))featureRow(label,label.equals("洞察")?"访问、行情与用量":label.equals("资产")?"基础设施、域名与运行状态":"账本、图片与内容管理",ConsoleTheme.symbol(label),all,()->switchTab(label));
        if(!loggedIn()||snapshots.containsKey("overview"))restoreViewport();
    }
    /** One-tap shortcuts in a single glass card, like WeChat's Discover entries: icon first, short name below. */
    private void quick(List<String> ids){
        LinearLayout grid=card();grid.setOrientation(LinearLayout.HORIZONTAL);grid.setPadding(dp(6),dp(14),dp(6),dp(12));
        for(String id:ids){FeatureCatalog.Feature f=find(id);if(f==null)continue;
            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER_HORIZONTAL);cell.setPadding(dp(2),dp(4),dp(2),dp(4));
            cell.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,16),16));cell.setFocusable(true);cell.setClickable(true);cell.setContentDescription(f.title+"，"+f.subtitle);cell.setOnClickListener(v->open(f));theme.press(cell);
            cell.addView(theme.tile(ConsoleTheme.symbol(f.id),48),new LinearLayout.LayoutParams(dp(48),dp(48)));
            TextView name=text(f.title,12,INK,false);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setPadding(0,dp(8),0,0);cell.addView(name);
            grid.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}
    }
    private LinearLayout dashboardResult;
    private void dashboard(boolean refresh){
        if(!resumed)return;
        final LinearLayout target=dashboardResult;if(target==null)return;JSONObject cached=snapshots.get("overview");
        if(cached!=null)FeatureRenderer.render(this,target,find("overview"),cached);
        else {target.removeAllViews();TextView loading=text("正在更新概览…",14,MUTED,false);loading.setPadding(0,dp(12),0,dp(16));target.addView(loading);}
        if(cached!=null&&!refresh)return;if(refresh)spin();int epoch=++generation;
        worker.execute(()->{if(epoch!=generation)return;try{JSONObject data=read("overview");runOnUiThread(()->{if(epoch!=generation||!resumed||isDestroyed())return;saveViewport();snapshots.put("overview",data);FeatureRenderer.render(this,target,find("overview"),data);restoreViewport();});}
        catch(Exception e){runOnUiThread(()->{if(epoch!=generation||!resumed||isDestroyed())return;
            // The refresh may have invalidated the stored session; show the sign-in card instead of a
            // signed-in home that can only ever fail (same handling as feature screens).
            if(!loggedIn()){navigation.clear();viewportReady=false;selectedId=null;project=null;ledgerCursor=null;ledgerPages.clear();snapshots.clear();if(ledger!=null)ledger.close();group="总览";build();error("请重新登录",safeError(e));return;}
            if(cached==null)target.removeAllViews();LinearLayout c=theme.card(target);c.addView(text("概览暂未更新",17,INK,true));c.addView(text(safeError(e),14,MUTED,false));c.addView(button("重试",()->dashboard(true)));restoreViewport();});}});
    }
    private void catalog(String category) {LinearLayout list=groupCard();for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals(category))featureRow(f,list);restoreViewport();}
    private void featureRow(FeatureCatalog.Feature f){featureRow(f,groupCard());}
    private void featureRow(FeatureCatalog.Feature f,LinearLayout list){featureRow(f.title,f.subtitle,ConsoleTheme.symbol(f.id),list,()->open(f));}
    private LinearLayout groupCard(){return groupCard(content);}
    private LinearLayout groupCard(LinearLayout parent){LinearLayout c=theme.card(parent);c.setPadding(dp(4),dp(4),dp(4),dp(4));return c;}
    /** Grouped-list cell: tinted icon tile, title, optional one-line subtitle, chevron when it navigates. A null action is a plain info row. */
    private LinearLayout featureRow(String title,String subtitle,String symbol,LinearLayout parent,Runnable action){
        if(parent.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(.5f));d.setMargins(dp(66),0,dp(14),0);parent.addView(divider,d);}
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setMinimumHeight(dp(64));
        row.setContentDescription(subtitle.isEmpty()?title:title+"，"+subtitle);
        if(action!=null){row.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,20),20));row.setFocusable(true);row.setOnClickListener(v->action.run());}
        row.addView(theme.tile(symbol,40),new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(14),0,dp(8),0);labels.addView(text(title,16,INK,false));
        if(!subtitle.isEmpty()){TextView detail=text(subtitle,12,MUTED,false);detail.setPadding(0,dp(2),0,0);labels.addView(detail);}
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){ImageView chevron=theme.icon("chevron",MUTED,14);chevron.setAlpha(.35f);row.addView(chevron);}
        parent.addView(row);return row;
    }
    private void open(FeatureCatalog.Feature f){++generation;selectedId=f.id;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    @Override public void openFeature(String id){FeatureCatalog.Feature f=find(id);if(f!=null&&loggedIn())open(f);}
    private void showFeature(FeatureCatalog.Feature f,boolean refresh) {
        saveViewport();viewportReady=false;displayedRoute=routeKey();content.removeAllViews();heading.setText(f.title);
        leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        trailing.removeAllViews();trailing.addView(circle("refresh","刷新",()->showFeature(f,true)));if(refresh)spin();
        if(!loggedIn()){LinearLayout c=card();c.addView(text(f.title,17,INK,true));TextView t=text("登录后查看此功能的真实数据。",15,MUTED,false);t.setPadding(0,dp(6),0,dp(18));c.addView(t);Button passkey=button("使用 Passkey 登录",this::login);theme.button(passkey,true);c.addView(passkey,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,-2);d.topMargin=dp(10);c.addView(button("通过已有设备授权",this::deviceLogin),d);restoreViewport();return;}
        String path=f.path;
        if(f.path.equals("travel-ledger/current/projects")&&project!=null)path="travel-ledger/current/snapshot?project="+Uri.encode(project)+"&limit=40"+(ledgerCursor==null?"":"&cursor="+Uri.encode(ledgerCursor));
        String key=path; JSONObject cached=key.startsWith("travel-ledger/")?null:snapshots.get(key);
        LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);content.addView(result);
        if(cached!=null){render(f,result,cached);TextView stamp=text(refresh?"正在更新，暂时保留上次结果":"上次读取结果 · 可刷新更新",12,MUTED,false);result.addView(stamp);restoreViewport();}
        else{TextView loading=text("正在读取…",15,MUTED,false);loading.setPadding(dp(4),dp(12),0,dp(12));result.addView(loading);}
        if(cached!=null&&!refresh)return;
        if(!resumed)return;
        int epoch=++generation;
        worker.execute(()->{if(epoch!=generation)return;try{JSONObject data=read(key);runOnUiThread(()->{if(epoch!=generation||isDestroyed())return;saveViewport();if(!key.startsWith("travel-ledger/"))snapshots.put(key,data);result.removeAllViews();session.setText(PreviewMode.isBuild()?"界面预览 · 合成数据":"God 工作台");render(f,result,data);restoreViewport();});}
        catch(Exception e){runOnUiThread(()->{if(epoch!=generation||isDestroyed())return;if(!loggedIn()){navigation.clear();viewportReady=false;ledgerCursor=null;ledgerPages.clear();snapshots.clear();ledger.close();selectedId=null;project=null;group="总览";build();error("请重新登录",safeError(e));return;}if(e instanceof MobileApi.ApiError&&((MobileApi.ApiError)e).status==401){snapshots.clear();result.removeAllViews();session.setText("会话需要重新验证");}else if(cached==null)result.removeAllViews();LinearLayout c=theme.card(result);TextView warning=text(safeError(e),15,theme.danger,false);warning.setPadding(0,0,0,dp(12));c.addView(warning);c.addView(button("重试",()->showFeature(f,true)),new LinearLayout.LayoutParams(-1,-2));restoreViewport();});}});
    }
    private void render(FeatureCatalog.Feature f,LinearLayout result,JSONObject data) {
        try{
            if(f.path.equals("travel-ledger/current/projects")&&project==null){
                if(data.optInt("schemaVersion",-1)!=1)throw new IllegalArgumentException();
                JSONArray projects=data.getJSONArray("projects");if(projects.length()>128)throw new IllegalArgumentException();
                LinearLayout list=projects.length()==0?null:groupCard(result);
                for(int i=0;i<projects.length();i++){JSONObject p=projects.getJSONObject(i);String id=p.getString("id");if(!id.matches("[a-f0-9]{32}|new-zealand-south-island-2026"))throw new IllegalArgumentException();
                    featureRow(p.optString("name",p.optString("title","账本")),"查看账目、成员与分摊","ledger",list,()->{project=id;ledgerCursor=null;ledgerPages.clear();showFeature(f,true);});}
                if(projects.length()==0)result.addView(text("暂无可访问的账本",15,MUTED,false));return;
            }
            FeatureRenderer.render(this,result,f,data);
            if(f.path.equals("travel-ledger/current/projects")&&project!=null) {
                JSONObject page=data.optJSONObject("page");
                LinearLayout pager=new LinearLayout(this);pager.setOrientation(LinearLayout.HORIZONTAL);
                if(!ledgerPages.isEmpty())pager.addView(button("上一页",()->{String previous=ledgerPages.pop();ledgerCursor=previous.isEmpty()?null:previous;showFeature(f,true);}),new LinearLayout.LayoutParams(0,-2,1));
                if(page!=null&&page.optBoolean("hasMore")){String next=page.optString("nextCursor");if(next.matches("[A-Za-z0-9_-]{1,1500}\\.[A-Za-z0-9_-]{43}")){LinearLayout.LayoutParams n=new LinearLayout.LayoutParams(0,-2,1);if(pager.getChildCount()>0)n.leftMargin=dp(10);pager.addView(button("下一页",()->{ledgerPages.push(ledgerCursor==null?"":ledgerCursor);ledgerCursor=next;showFeature(f,true);}),n);}}
                if(pager.getChildCount()>0){LinearLayout.LayoutParams g=new LinearLayout.LayoutParams(-1,-2);g.bottomMargin=dp(12);result.addView(pager,g);}
                // 记一笔 sits in the header so it stays one tap away above a long page of entries.
                View existing=trailing.findViewWithTag("ledger-add");if(existing!=null)trailing.removeView(existing);
                FrameLayout add=circle("plus","记一笔",()->ledger.showEntry(project,null));add.setTag("ledger-add");trailing.addView(add,0);
                JSONArray entries=data.optJSONArray("entries");
                List<String> entryIds=new ArrayList<>(),entryLabels=new ArrayList<>();
                if(entries!=null)for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);if(e==null)continue;entryIds.add(e.optString("id"));entryLabels.add(FeatureRenderer.ledgerEntryLabel(e));}
                LinearLayout manage=groupCard(result);
                if(!entryIds.isEmpty())featureRow("编辑本页账目","修改或删除本页的一笔账目","edit",manage,()->
                    show(new AlertDialog.Builder(this).setTitle("选择账目").setItems(entryLabels.toArray(new String[0]),(d,index)->ledger.showEntry(project,entryIds.get(index))).setNegativeButton("取消",null)));
                featureRow("人员管理","管理同行人与顺序","people",manage,()->ledger.showPeople(project));
                featureRow("最近删除","查看并恢复已删除的账目","trash",manage,()->ledger.showTrash(project));
                featureRow("查询上次提交结果","核实未确认的提交是否已生效","history",manage,()->ledger.recover());
                featureRow("切换账本","回到账本列表","swap",manage,()->{project=null;ledgerCursor=null;ledgerPages.clear();showFeature(f,true);});
            }
        }catch(Exception e){result.removeAllViews();result.addView(text("数据格式与当前版本不匹配，请刷新或更新应用。",15,theme.danger,false));}
    }
    private String versionName(){
        try{android.content.pm.PackageInfo info=Build.VERSION.SDK_INT>=33
                ?getPackageManager().getPackageInfo(getPackageName(),android.content.pm.PackageManager.PackageInfoFlags.of(0))
                :getPackageManager().getPackageInfo(getPackageName(),0);
            return info.versionName==null?"":info.versionName;}
        catch(Exception ignored){return "";}
    }
    private void settings(){
        profile();
        section("管理");LinearLayout management=groupCard();for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals("设置"))featureRow(f,management);
        section("关于");LinearLayout about=groupCard();
        featureRow("隐私与数据","无第三方统计 · 不保存网页 Cookie","shield",about,()->show(new AlertDialog.Builder(this).setTitle("隐私与数据").setMessage("应用仅连接 Robby 的 God 私有接口。登录在系统浏览器完成，应用不接触密码或 Passkey 私钥。会话使用 Android Keystore 加密且不参加备份。业务数据仅在内存中展示，进入后台即清除；登录后禁止截屏与最近任务预览。退出会清除本机令牌。\n\n可查看原生数据，并在确认后管理账目和人员。").setPositiveButton("知道了",null)));
        String version=versionName();LinearLayout info=featureRow("God 工作台",version.isEmpty()?"Android":"Android "+version,"info",about,this::openAbout);
        if(notesUnread()){View red=theme.dot(theme.unread,8);LinearLayout.LayoutParams r=(LinearLayout.LayoutParams)red.getLayoutParams();r.rightMargin=dp(10);info.addView(red,info.getChildCount()-1,r);info.setContentDescription(info.getContentDescription()+"，有新版本说明");}
        restoreViewport();
    }
    /** Account card at the top of 我, in WeChat's profile position; opens the same sign-in / sign-out choices as before. */
    private void profile(){
        boolean logged=loggedIn();LinearLayout c=card();c.setOrientation(LinearLayout.HORIZONTAL);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(dp(16),dp(18),dp(14),dp(18));
        c.setBackground(theme.ripple(theme.card(),24));c.setFocusable(true);c.setClickable(true);c.setContentDescription(logged?"账号与退出登录":"登录 God 工作台");c.setOnClickListener(v->account());theme.press(c);
        FrameLayout avatar=new FrameLayout(this);avatar.setBackground(theme.glass(-1));avatar.addView(theme.icon("user",INK,28),new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));c.addView(avatar,new LinearLayout.LayoutParams(dp(60),dp(60)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(16),0,dp(8),0);labels.addView(text(logged?"已登录":"尚未登录",20,INK,true));
        TextView detail=text(logged?"Passkey · 设备加密存储":"使用 Passkey 或已有设备登录",13,MUTED,false);detail.setPadding(0,dp(4),0,0);labels.addView(detail);
        c.addView(labels,new LinearLayout.LayoutParams(0,-2,1));ImageView chevron=theme.icon("chevron",MUTED,14);chevron.setAlpha(.35f);c.addView(chevron);
    }
    private void openAbout(){++generation;selectedId=ABOUT;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    private android.content.SharedPreferences prefs(){return getSharedPreferences("god-ui",MODE_PRIVATE);}
    /** Red dot until the installed version's notes are opened once after an upgrade. Stores only a version string. */
    private boolean notesUnread(){
        boolean upgraded=false;
        try{android.content.pm.PackageInfo info=Build.VERSION.SDK_INT>=33?getPackageManager().getPackageInfo(getPackageName(),android.content.pm.PackageManager.PackageInfoFlags.of(0)):getPackageManager().getPackageInfo(getPackageName(),0);upgraded=info.lastUpdateTime>info.firstInstallTime;}
        catch(Exception ignored){}
        return ReleaseNotes.unread(prefs().getString("notesSeen",null),versionName(),upgraded);
    }
    /**
     * 关于 God 工作台, modelled on WeChat's 关于微信: brand and version first, one way to get a newer build,
     * then 更新记录 newest first. The installed version is expanded and marked; older ones show their one-line
     * theme until tapped, so the page scans like a list and reads like notes.
     */
    private void about(){
        heading.setText("关于");
        leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        String version=versionName();
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(0,dp(10),0,dp(22));
        FrameLayout mark=new FrameLayout(this);mark.setBackground(theme.glass(-1));mark.addView(theme.tabIcon("grid",true,38),new FrameLayout.LayoutParams(dp(38),dp(38),Gravity.CENTER));hero.addView(mark,new LinearLayout.LayoutParams(dp(76),dp(76)));
        TextView name=text("God 工作台",22,INK,true);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(14),0,dp(4));hero.addView(name);
        TextView build=text(version.isEmpty()?"Android":"Android "+version,14,MUTED,false);build.setGravity(Gravity.CENTER);hero.addView(build);content.addView(hero);
        LinearLayout links=groupCard();featureRow("获取新版本","GitHub Releases · 覆盖安装保留数据","download",links,this::openReleases);
        // The installed version reads as its own card; every other version is one row in a single grouped list,
        // like WeChat's 功能介绍, so ten releases scan as a list instead of a stack of cards.
        if(expandedNotes.isEmpty()&&!version.isEmpty())expandedNotes.add(version);
        java.time.LocalDate today=java.time.LocalDate.now();
        ReleaseNotes.Entry installed=ReleaseNotes.find(version);
        if(installed!=null){section("本次更新");noteRow(installed,true,today,card());}
        section(installed==null?"更新记录":"历史版本");LinearLayout history=card();history.setPadding(dp(6),dp(6),dp(6),dp(6));
        if(theme.effects)history.setLayoutTransition(new android.animation.LayoutTransition());
        for(ReleaseNotes.Entry e:ReleaseNotes.ALL)if(e!=installed)noteRow(e,false,today,history);
        TextView foot=text("安装包校验值和验证说明见 GitHub Releases。",12,theme.faint,false);foot.setGravity(Gravity.CENTER);foot.setPadding(dp(12),dp(8),dp(12),dp(4));content.addView(foot);
        restoreViewport();
    }
    /** One release inside {@code c}: version, theme and date; tapping toggles its numbered notes in place. */
    private void noteRow(ReleaseNotes.Entry e,boolean current,java.time.LocalDate today,LinearLayout c){
        if(current)c.setPadding(dp(6),dp(6),dp(6),dp(6));
        if(c.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(.5f));d.setMargins(dp(12),0,dp(12),0);c.addView(divider,d);}
        boolean open=expandedNotes.contains(e.version);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(12),dp(10),dp(10),dp(10));head.setMinimumHeight(dp(56));
        head.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,18),18));head.setFocusable(true);head.setClickable(true);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.addView(text(e.version,17,INK,true));
        if(current){TextView badge=text("当前版本",11,theme.accent,true);badge.setPadding(dp(8),dp(2),dp(8),dp(2));badge.setBackground(theme.shape((theme.accent&0x00ffffff)|0x1f000000,99));LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(-2,-2);b.leftMargin=dp(8);line.addView(badge,b);}
        titles.addView(line);TextView summary=text(e.headline,14,MUTED,false);summary.setPadding(0,dp(3),0,0);titles.addView(summary);
        head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        TextView date=text(ReleaseNotes.dateLabel(e.date,today),12,theme.faint,false);date.setPadding(dp(8),0,dp(6),0);head.addView(date);
        ImageView chevron=theme.icon("chevron",MUTED,14);chevron.setAlpha(.45f);chevron.setRotation(open?-90:90);head.addView(chevron);
        c.addView(head);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),0,dp(14),dp(12));body.setVisibility(open?View.VISIBLE:View.GONE);
        for(int i=0;i<e.items.size();i++){
            LinearLayout item=new LinearLayout(this);item.setPadding(0,dp(6),0,dp(6));
            TextView n=text((i+1)+".",15,MUTED,false);n.setMinWidth(dp(24));n.setPadding(0,0,dp(6),0);item.addView(n);
            TextView t=text(e.items.get(i),15,INK,false);t.setLineSpacing(dp(4),1);item.addView(t,new LinearLayout.LayoutParams(0,-2,1));body.addView(item);
        }
        c.addView(body);
        head.setContentDescription(e.version+(current?"，当前版本":"")+"，"+e.headline+"，"+ReleaseNotes.dateLabel(e.date,today));head.setStateDescription(open?"已展开":"已收起");
        if(current&&theme.effects)c.setLayoutTransition(new android.animation.LayoutTransition());
        head.setOnClickListener(v->{
            boolean show=body.getVisibility()!=View.VISIBLE;
            if(show)expandedNotes.add(e.version);else expandedNotes.remove(e.version);
            body.setVisibility(show?View.VISIBLE:View.GONE);head.setStateDescription(show?"已展开":"已收起");
            if(theme.effects)chevron.animate().rotation(show?-90:90).setDuration(200).start();else chevron.setRotation(show?-90:90);
        });
    }
    private void openReleases(){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/robbywang25/robby-console-android/releases")).addCategory(Intent.CATEGORY_BROWSABLE));}
        catch(ActivityNotFoundException e){error("无法打开浏览器","手机需要可以打开 HTTPS 链接的浏览器。");}
    }
    @Override public void onDeviceAuthorization(DeviceAuthorization info){if(resumed&&!isDestroyed())build();}
    private void showDeviceAuthorization(DeviceAuthorization info){
        heading.setText("授权此设备");session.setText("等待本人确认");
        leading.removeAllViews();leading.addView(theme.circle("back","取消验证",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        LinearLayout c=step(1,"打开安全验证页");
        TextView url=text(info.verificationUri.toString(),15,ACCENT,false);url.setPadding(0,dp(12),0,dp(12));c.addView(url);
        c.addView(text("使用已经能登录 God 的电脑或其他设备。",14,MUTED,false));
        c=step(2,"输入这组一次性代码");
        TextView code=text(info.userCode,30,INK,true);code.setLetterSpacing(0.12f);code.setPadding(0,dp(18),0,dp(18));code.setGravity(Gravity.CENTER);code.setBackground(theme.shape(theme.soft,18));
        LinearLayout.LayoutParams box=new LinearLayout.LayoutParams(-1,-2);box.topMargin=dp(14);box.bottomMargin=dp(14);c.addView(code,box);
        c.addView(text("核对应用为 God 工作台 Android，再确认授权。不要把代码发给其他人。",14,MUTED,false));
        c=step(3,"完成后自动进入工作台");
        TextView note=text("代码仅在本次验证有效；请保持两台设备联网。",14,MUTED,false);note.setPadding(0,dp(10),0,0);c.addView(note);
        LinearLayout.LayoutParams cancel=new LinearLayout.LayoutParams(-1,-2);cancel.topMargin=dp(4);content.addView(button("取消验证",()->{coordinator.cancelDeviceLogin();build();}),cancel);restoreViewport();
    }
    private LinearLayout step(int n,String title){
        LinearLayout c=card();LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge=text(String.valueOf(n),14,Color.WHITE,true);badge.setGravity(Gravity.CENTER);badge.setBackground(theme.shape(theme.accentStrong,99));badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);top.addView(badge,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView h=text(title,18,INK,true);h.setAccessibilityHeading(true);h.setContentDescription(n+"  "+title);h.setPadding(dp(12),0,0,0);top.addView(h,new LinearLayout.LayoutParams(0,-2,1));c.addView(top);return c;
    }
    private void deviceLogin(){if(PreviewMode.isBuild()){error("界面预览","此预览不连接真实账号。");return;}if(coordinator.busy())return;coordinator.loginWithDevice();}
    private void login(){if(PreviewMode.isBuild()){error("界面预览","此预览不连接真实账号。");return;}if(coordinator.busy())return;session.setText("正在准备安全登录…");coordinator.login();}
    private void account(){if(PreviewMode.isBuild()){error("界面预览","仅包含合成展示数据，不连接账号或服务。");return;}if(!loggedIn()){show(new AlertDialog.Builder(this).setTitle("登录 God 工作台").setItems(new String[]{"通过已登录设备授权","使用 Passkey 登录"},(d,w)->{if(w==0)deviceLogin();else login();}).setNegativeButton("取消",null));return;}show(new AlertDialog.Builder(this).setTitle("God 账号").setMessage("会话保存在此设备的加密存储中。").setNegativeButton("取消",null).setPositiveButton("退出登录",(d,w)->{++generation;snapshots.clear();navigation.clear();viewportReady=false;selectedId=null;project=null;content.removeAllViews();ledger.close();coordinator.logout();group="总览";build();}));}
    private void goBack(){if(coordinator==null){finish();return;}if(coordinator.deviceAuthorization()!=null){coordinator.cancelDeviceLogin();build();return;}if(selectedId!=null){saveViewport();++generation;if(project!=null){project=null;ledgerCursor=null;ledgerPages.clear();showFeature(find(selectedId),true);}else{selectedId=null;enterFrom=-1;build();}}else if(!group.equals("总览")){switchTab("总览");}else moveTaskToBack(true);}
    // API 33+ uses the registered OnBackInvokedDispatcher; this is the API 30-32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    @Override protected void onStop(){saveViewport();viewportReady=false;super.onStop();dashboardResult=null;if(ledger!=null)ledger.close();++generation;snapshots.clear();if(auth!=null&&loggedIn()&&content!=null){content.removeAllViews();content.addView(text("God 工作台",24,INK,true));}}
    @Override protected void onDestroy(){++generation;if(ledger!=null)ledger.dispose();worker.shutdownNow();super.onDestroy();}
    private FeatureCatalog.Feature find(String id){return FeatureCatalog.all().stream().filter(f->f.id.equals(id)).findFirst().orElse(null);}
    private void section(String title){TextView t=text(title,15,INK,true);t.setAccessibilityHeading(true);t.setPadding(dp(6),dp(18),0,dp(10));content.addView(t);}
    private LinearLayout card(){return theme.card(content);}
    private TextView text(String s,int size,int color,boolean bold){TextView t=theme.text(s,size,bold);t.setTextColor(color);return t;}
    private Button button(String label,Runnable action){return theme.button(label,action);}
    private GradientDrawable round(int color,int radius){return theme.shape(color,radius);}
    private boolean loggedIn(){return PreviewMode.isBuild()?PreviewMode.loggedIn():auth.hasSession();}
    private JSONObject read(String path)throws Exception{return PreviewMode.isBuild()?PreviewMode.data(path):api.get(path);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String safeError(Exception e){String m=e.getMessage();return m!=null&&m.matches("[\\p{IsHan}，。、；：！？ ·…A-Za-z0-9-]{1,100}")?m:"连接未完成，请检查网络后重试";}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void error(String title,String message){show(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null));}
    private void show(AlertDialog.Builder builder){AlertDialog d=builder.create();d.show();theme.dialog(d);}
}
