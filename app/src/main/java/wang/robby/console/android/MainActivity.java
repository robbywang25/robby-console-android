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
import wang.robby.console.android.auth.NativePasskeyFlow;

/** Native client. No embedded web content, demo balances, or service credentials. */
public class MainActivity extends Activity implements AuthCoordinator.Listener, FeatureRenderer.Opener, TokensPages.Host {
    private int INK, MUTED, ACCENT, BG; private ConsoleTheme theme;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    /** 看板 reads run side by side (overview, Token 额度, …) instead of queueing behind one another. */
    private final ExecutorService boardReads=Executors.newFixedThreadPool(3);
    private AuthSession auth; private AuthCoordinator coordinator; private MobileApi api; private LedgerActions ledger;
    private String ledgerCursor=null; private final Deque<String> ledgerPages=new ArrayDeque<>();
    private FrameLayout root,leading; private LinearLayout content,trailing; private GlassPane nav; private TextView heading,session;
    private static final List<String> TABS=List.of("总览","功能","设置");
    /** Tab whose lens was last drawn, so the lens can glide from it; -1 before the first frame. */
    private int shownTab=-1; private int enterFrom=0; private View pullMark, pullGlyph;
    /** Pseudo feature id for 关于 God; it has no API path and lives in the 设置 tab's reading context. */
    private static final String ABOUT="about";
    /** Pseudo feature id for the 看板 editor, in the 总览 tab's reading context. */
    private static final String BOARD="board";
    /** Pseudo feature id for 我 → 已隐藏的功能. */
    private static final String HIDDEN_PAGE="hidden";
    /** Pseudo feature id for 我 → 个人中心 (the account card). */
    private static final String ACCOUNT_PAGE="account";
    /** Pseudo feature ids for 我's second-level pages, after iOS Settings: switches and menus live one tap in. */
    private static final String APPEARANCE_PAGE="appearance",PRIVACY_PAGE="privacy",NOTICES_PAGE=NoticePages.ROOT;
    /** 个人中心's last answers; process memory only and dropped in the background like every other page's data. */
    private AccountCenter.Status accountStatus; private AccountCenter.Devices accountDevices; private boolean devicesUnfolded;
    /** Back from the browser (adding a Passkey, binding Apple or WeChat): read the account again. */
    private boolean accountBrowserPending,accountNameRead; private TextView profileName,profileDetail;
    /** Release-note cards the reader expanded; process memory only, like the other reading context. */
    private final Set<String> expandedNotes=new HashSet<>();
    /** In-app update for the 关于 page. State is process memory only; network work runs off the UI thread. */
    private final ExecutorService updater=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean updateCancel=new java.util.concurrent.atomic.AtomicBoolean();
    private final UpdateReceiver.Listener updateListener=this::onUpdateStatus;
    private volatile AppUpdate.Release update; private volatile String updateState="idle",updateNote; private volatile int updatePercent=-1;
    private volatile boolean updateChecking; private long updateCheckedAt;
    private boolean awaitingInstallPermission; private LinearLayout updateBox;
    /** 运行状态 refreshes itself every 60 s while it is the visible page, like status.robby.wang; never in the background. */
    private final Handler ticker=new Handler(Looper.getMainLooper());
    private final Runnable statusTick=()->{if(this.resumed&&"status".equals(this.selectedId)&&loggedIn()){FeatureCatalog.Feature f=find("status");if(f!=null)showFeature(f,true);}};
    /**
     * 总览's Token 额度 cards re-read themselves while 总览 is on screen, at the pace the server asks for
     * (refreshAfterSeconds, kept within 30 s – 5 min, 60 s when absent); only those cards are refilled, in place.
     */
    private final Runnable tokenTick=this::refreshBoardTokens;
    private Map<String,List<LinearLayout>> boardShells=new HashMap<>();
    static final String TOKENS="tokens/quotas";
    private boolean onBoard(){return resumed&&board!=null&&selectedId==null&&"总览".equals(group)&&loggedIn()&&boardShells.containsKey(TOKENS);}
    private void scheduleTokenTick(JSONObject data){ticker.removeCallbacks(tokenTick);if(onBoard())ticker.postDelayed(tokenTick,TokensModel.refreshMs(data));}
    private void refreshBoardTokens(){
        if(!onBoard())return;final LinearLayout box=board;final List<LinearLayout> targets=boardShells.get(TOKENS);final int epoch=generation;
        boardReads.execute(()->{try{long started=SystemClock.elapsedRealtime();JSONObject data=read(TOKENS);android.util.Log.i("GodBoard","auto refresh "+TOKENS+" ms="+(SystemClock.elapsedRealtime()-started)+" next="+TokensModel.refreshMs(data)/1000+"s");
                runOnUiThread(()->{if(epoch!=generation||board!=box||!resumed||isDestroyed())return;saveViewport();snapshots.put(TOKENS,data);GodWidget.observe(this,TOKENS,data);fillBoard(targets,data);restoreViewport();scheduleTokenTick(data);});}
            catch(Exception e){android.util.Log.w("GodBoard","auto refresh failed "+(e instanceof MobileApi.ApiError?"http="+((MobileApi.ApiError)e).status:e.getClass().getSimpleName()));
                // Keep what is shown and try again at the usual pace.
                runOnUiThread(()->{if(epoch==generation&&board==box)scheduleTokenTick(snapshots.get(TOKENS));});}});
    }
    private String group="总览",selectedId=null,project=null;
    private volatile int generation=0; private boolean resumed=false;
    private final Map<String,JSONObject> snapshots=new HashMap<>();
    private final NavigationMemory navigation=new NavigationMemory();
    private PullScrollView scroll; private String displayedRoute; private boolean viewportReady;
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
        try { coordinator=AuthCoordinator.get(this);auth=coordinator.session(); api=new MobileApi(auth);ledger=new LedgerActions(this,new LedgerActions.Transport(){public JSONObject get(String path)throws Exception{return read(path);}public JSONObject post(String path,JSONObject body)throws Exception{if(PreviewMode.isBuild())throw new IllegalStateException("界面预览不执行写入");if(auth.isFederated())throw new IllegalStateException("用 Google 登录时只能查看，记账请改用 Passkey 登录");return api.post(path,body);}},()->{ledgerCursor=null;ledgerPages.clear();FeatureCatalog.Feature f=find("travel-ledger");if(f!=null)showFeature(f,true);}); }
        catch(Exception e) { new AlertDialog.Builder(this).setMessage("无法打开设备安全存储，请检查屏幕锁定设置").setCancelable(false).setPositiveButton("关闭",(d,w)->finish()).show(); return; }
        if(state!=null) { group=state.getString("group","总览");if(FeatureCatalog.SECTIONS.contains(group))group="功能"; selectedId=state.getString("feature"); project=state.getString("project");navigation.rememberOffset(routeKey(),state.getInt("scroll",0)); }
        build();
        // A recreated Activity (process death) or a Recents relaunch re-delivers the original launch
        // Intent. Its one-time OAuth callback was already consumed, so replaying it only produced a
        // spurious "登录未完成或已过期" error. Fresh callbacks always arrive as a new launch or onNewIntent.
        if(state==null&&!launchedFromHistory(getIntent()))handle(getIntent()); else getIntent().setData(null);
        openRequested(getIntent());
        UpdateReceiver.listener=updateListener;publishShortcuts();
        if(Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
    }
    @Override protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state);state.putString("group",group);state.putString("feature",selectedId);state.putString("project",project);saveViewport();state.putInt("scroll",ledgerCursor==null?navigation.offset(routeKey()):0);}
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);if(!launchedFromHistory(intent))handle(intent);else intent.setData(null);openRequested(intent);}
    /** The reminder, the alarm page and launcher shortcuts open one page of the fixed catalog; nothing else can be requested. */
    private void openRequested(Intent intent){
        // Opening God from a 持续提醒 counts as 知道了 for that reminder.
        String notice=intent==null?null:intent.getStringExtra(NoticeCenter.EXTRA_KEY);if(notice!=null){intent.removeExtra(NoticeCenter.EXTRA_KEY);if(!PreviewMode.isBuild())NoticeCenter.ack(this,notice);}
        String id=intent==null?null:intent.getStringExtra(TiboWatch.EXTRA_OPEN);if(id==null)return;intent.removeExtra(TiboWatch.EXTRA_OPEN);
        FeatureCatalog.Feature f=find(id);if(f==null||"overview".equals(id)||launchedFromHistory(intent)||!loggedIn())return;
        String tab=FeatureCatalog.SECTIONS.contains(f.group)?"功能":"设置";if(!group.equals(tab)){saveViewport();group=tab;}open(f);
    }
    /** Long-pressing the God icon offers these pages; they can also be dragged out as icons of their own. */
    private void publishShortcuts(){
        if(PreviewMode.isBuild())return;
        try{android.content.pm.ShortcutManager m=getSystemService(android.content.pm.ShortcutManager.class);if(m==null)return;List<android.content.pm.ShortcutInfo> list=new ArrayList<>();int rank=0;
            for(String[] s:new String[][]{{"tibo","28 天挑战","Tibo 28 天挑战","bell"},{"tokens","Token 额度","查看 Token 额度","gauge"},{"quota-calendar","重置日历","额度重置日历","calendar"}}){
                Intent go=new Intent(Intent.ACTION_VIEW,null,this,MainActivity.class).putExtra(TiboWatch.EXTRA_OPEN,s[0]);
                list.add(new android.content.pm.ShortcutInfo.Builder(this,s[0]).setShortLabel(s[1]).setLongLabel(s[2]).setIcon(android.graphics.drawable.Icon.createWithAdaptiveBitmap(theme.shortcutBitmap(s[3]))).setIntent(go).setRank(rank++).build());}
            m.setDynamicShortcuts(list);}
        catch(RuntimeException ignored){}
    }
    static boolean launchedFromHistory(Intent intent){return intent!=null&&(intent.getFlags()&Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)!=0;}
    private void handle(Intent intent) {
        if(PreviewMode.isBuild()||coordinator==null){intent.setData(null);return;}
        Uri uri=intent.getData(); if(uri==null)return;
        intent.setData(null); coordinator.callback(uri);
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(coordinator!=null){coordinator.attach(this);}
        PreferenceSyncHub.attach(this);if(!PreviewMode.isBuild()){PreferenceSyncHub.foreground(this);PreferenceSyncHub.startPolling(this);}
        // Back on 总览 after a pause (shade, dialog, another app on top): the Token cards resume refreshing themselves.
        if(board!=null)scheduleTokenTick(snapshots.get(TOKENS));
        // Back from the "install unknown apps" settings page: continue the update the person already started.
        if(awaitingInstallPermission){awaitingInstallPermission=false;if(getPackageManager().canRequestPackageInstalls())startUpdate();}
        // Back from a notification or battery settings page: show what still keeps the reminder from ringing.
        TokensPages.refreshReminder();
        if(accountBrowserPending){accountBrowserPending=false;if(ACCOUNT_PAGE.equals(selectedId)&&loggedIn()){accountStatus=null;accountDevices=null;build();}}
        // ColorOS may stop the reminder or refuse to restart it after an update; opening the app brings it back.
        if(!PreviewMode.isBuild()&&TiboWatch.active(this))TiboWatch.start(this,TiboWatch.checkDue(this));
        // A pull that landed while the app was in the background may have switched on a reminder.
        askSyncedNotify();
        // The avatar follows the server: fetch it at most every 10 minutes (and send anything waiting).
        // A change waiting for a session that may now write (after signing in again) goes at once.
        if(loggedIn()&&(avatarCheckedAt==0||SystemClock.elapsedRealtime()-avatarCheckedAt>10*60_000L||Avatar.pending(this)!=null&&auth.hasScope(AuthSession.PROFILE_WRITE)))syncAvatar(false);
        // The red dots mean a newer release is ready, so look quietly, at most every 30 minutes, without opening 关于.
        if(!updateChecking&&!updateBusy()&&(updateCheckedAt==0||SystemClock.elapsedRealtime()-updateCheckedAt>30*60_000L))checkUpdate();}
    @Override protected void onPause(){resumed=false;PreferenceSyncHub.stopPolling();ticker.removeCallbacks(statusTick);ticker.removeCallbacks(tokenTick);if(coordinator!=null)coordinator.detach(this);super.onPause();}
    @Override public void onAuthChanged(){avatarCheckedAt=0;if(!resumed||isDestroyed())return;if(!PreviewMode.isBuild())PreferenceSyncHub.foreground(this);if(loggedIn())syncAvatar(false);++generation;snapshots.clear();if(!loggedIn()){navigation.clear();selectedId=null;project=null;ledgerCursor=null;ledgerPages.clear();viewportReady=false;}build();}
    /** A pulled account document changed settings under this page: repaint from the preference files. */
    void onPreferencesApplied(){if(isDestroyed()||!resumed)return;theme=new ConsoleTheme(this);INK=theme.ink;MUTED=theme.muted;ACCENT=theme.accent;BG=theme.canvas;applyStatusBar();build();askSyncedNotify();}
    /** A reminder switched on from another phone arrives without this phone's notification permission: ask for it here. */
    private void askSyncedNotify(){
        if(PreviewMode.isBuild()||isDestroyed()||!resumed||!PreferenceSyncHub.takeNotifyAsk(this))return;
        if(!TiboWatch.active(this)||!notificationsBlocked())return;
        if(!notificationsAllowed()){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},SYNC_PERMISSION);return;}
        error("通知未开启","提醒已从账号同步到这台手机，但系统通知被关闭，提醒不会送达。可以在系统设置里为 God 开启通知。");
    }
    private static final int SYNC_PERMISSION=45;
    private static final int AUTH_TAB=41;
    /** The sign-in the Auth Tab was opened for, so its browser fallback repeats the same one (Google or Passkey). */
    private boolean lastFederated;
    /** After Chrome could not verify the app for the callback domain, later sign-ins use the plain browser + App Link. */
    private boolean browserLogin;
    /** One sign-in on the web page, chosen after a native attempt failed. */
    private boolean webOnce;
    /** Android 14+: the system Passkey sheet and a native consent prompt, no web page; the callback then takes the usual PKCE path. */
    private final NativeLogin nativeLogin=new NativeLogin(this,new NativeLogin.Host(){
        @Override public void consent(java.util.function.Consumer<Boolean> answer){
            boolean[] answered={false};
            show(new AlertDialog.Builder(MainActivity.this).setTitle("允许 God 访问你的工作台？").setMessage("用于读取额度与资产、管理已接入的工作台功能，并在此设备保持登录。")
                .setNegativeButton("取消",null).setPositiveButton("允许",(d,w)->{if(!answered[0]){answered[0]=true;answer.accept(true);}})
                .setOnDismissListener(d->{if(!answered[0]){answered[0]=true;answer.accept(false);}}));
        }
        @Override public void finished(String callback,NativePasskeyFlow.Failure failure){
            if(isDestroyed())return;
            if(callback!=null){coordinator.callback(Uri.parse(callback));return;}
            build();
            if(failure==null||failure.cancelled)return;
            show(new AlertDialog.Builder(MainActivity.this).setTitle("登录未完成").setMessage(failure.getMessage())
                .setNegativeButton("知道了",null).setPositiveButton("改用网页登录",(d,w)->{webOnce=true;login();}));
        }
    });
    @Override public void onAuthorizationReady(String url){
        if(!resumed||isDestroyed())return;
        boolean web=webOnce;webOnce=false;
        // The native Passkey sheet is Authelia's; a bound Google or Apple account always signs in on its own page.
        boolean federated=url.startsWith(AuthSession.FEDERATED_START);lastFederated=federated;
        if(!federated&&!web&&!browserLogin&&NativeLogin.supported(this)){session.setText("正在用通行密钥登录…");nativeLogin.start(url);return;}
        session.setText(federated?"请在登录页选择 Google 账号":browserLogin?"请在浏览器完成 Passkey 登录":"请在登录页完成 Passkey 验证");
        try{
            if(browserLogin){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));return;}
            // Auth Tab (Chrome 137+): the sign-in page, and its Passkey sheet, open over the app; when it reaches the
            // callback it closes and returns the URL as this activity's result, verified against god.robby.wang's
            // assetlinks. Browsers without Auth Tab read the same intent as a Custom Tab or a normal page, and the
            // existing App Link still delivers the callback. The extras are the AndroidX Auth Tab protocol keys.
            Uri callback=Uri.parse(AuthSession.REDIRECT_URI);
            Intent tab=new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
            Bundle extras=new Bundle();extras.putBinder("android.support.customtabs.extra.SESSION",null);tab.putExtras(extras);
            tab.putExtra("androidx.browser.auth.extra.LAUNCH_AUTH_TAB",true)
                .putExtra("androidx.browser.auth.extra.HTTPS_REDIRECT_HOST",callback.getHost())
                .putExtra("androidx.browser.auth.extra.HTTPS_REDIRECT_PATH",callback.getPath());
            startActivityForResult(tab,AUTH_TAB);
        }catch(ActivityNotFoundException e){error("无法打开登录","手机需要可处理 HTTPS 登录的浏览器。");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_AVATAR){
            // Some galleries (ColorOS among them) answer with the picture in ClipData instead of the data URI.
            Uri picked=data==null?null:data.getData()!=null?data.getData():data.getClipData()!=null&&data.getClipData().getItemCount()>0?data.getClipData().getItemAt(0).getUri():null;
            android.util.Log.i("GodAvatar","pick result="+result+" uri="+(picked!=null)+" clip="+(data!=null&&data.getClipData()!=null));
            if(result!=RESULT_OK||picked==null){if(result==RESULT_OK)error("头像未更新","相册没有返回图片，请再选一次。");return;}
            updater.execute(()->{try{Avatar.save(this,picked);android.util.Log.i("GodAvatar","saved");runOnUiThread(()->{if(!isDestroyed()){build();syncAvatar(true);}});}
                catch(Exception e){android.util.Log.w("GodAvatar","save failed "+e.getClass().getSimpleName());runOnUiThread(()->error("头像未更新","无法读取这张图片，请换一张试试。"));}});
            return;
        }
        if(request!=AUTH_TAB||coordinator==null)return;
        if(result==RESULT_OK&&data!=null&&data.getData()!=null){coordinator.callback(data.getData());return;}
        // 2 and 3 are Auth Tab's verification failed / timed out: the tab could not prove this app owns the callback.
        if(result==2||result==3){browserLogin=true;session.setText(idleSession());
            show(new AlertDialog.Builder(this).setTitle("登录未完成").setMessage("应用内登录页暂时无法确认 God的身份。可以改用浏览器登录，完成后会自动回到应用。")
                .setNegativeButton("取消",null).setPositiveButton("用浏览器登录",(d,w)->{if(lastFederated)googleLogin();else login();}));}
        // RESULT_CANCELED: the person closed the page, or a browser without tabs took over and the App Link will answer.
    }
    @Override public void onAuthError(String message){if(resumed&&!isDestroyed()){build();error("登录未完成",message);}}
    private void build() {
        ticker.removeCallbacks(statusTick);ticker.removeCallbacks(tokenTick);boardShells=new HashMap<>();saveViewport();viewportReady=false;++generation;board=null;
        boolean logged=loggedIn();
        if(!logged){navigation.clear();project=null;ledgerCursor=null;ledgerPages.clear();}
        DeviceAuthorization device=coordinator.deviceAuthorization();
        if(!PreviewMode.isBuild()&&(logged||device!=null||coordinator.busy())) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=new FrameLayout(this);setContentView(root);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsController insetController=getWindow().getInsetsController();
        applyStatusBar();
        if(insetController!=null)insetController.setSystemBarsAppearance(theme.dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        // The stage holds the sky and the page. The floating dock blurs the stage, never itself.
        FrameLayout stage=new FrameLayout(this);stage.setBackground(theme.backdrop());root.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);stage.addView(body,new FrameLayout.LayoutParams(-1,-1));
        FeatureCatalog.Feature detail=selectedId==null?null:find(selectedId);boolean about=ABOUT.equals(selectedId),boardEdit=BOARD.equals(selectedId),hiddenPage=HIDDEN_PAGE.equals(selectedId),accountPage=ACCOUNT_PAGE.equals(selectedId)&&logged,appearancePage=APPEARANCE_PAGE.equals(selectedId),privacyPage=PRIVACY_PAGE.equals(selectedId),noticesPage=NoticePages.page(selectedId);
        if(detail==null&&!about&&!boardEdit&&!hiddenPage&&!accountPage&&!appearancePage&&!privacyPage&&!noticesPage)selectedId=null;
        boolean nested=device!=null||detail!=null||about||boardEdit||hiddenPage||accountPage||appearancePage||privacyPage||noticesPage;
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),dp(8),dp(16),dp(nested?8:6));header.setMinimumHeight(dp(64));
        leading=new FrameLayout(this);trailing=new LinearLayout(this);trailing.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);
        // The small line under a title only carries status (sign-in progress, a session to re-verify, the preview
        // label); it takes no space and says nothing when there is none — no app name, no date.
        session=text(idleSession(),12,MUTED,false);session.setVisibility(View.GONE);
        session.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence t,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence t,int a,int b,int c){}
            @Override public void afterTextChanged(android.text.Editable t){boolean shown=t.length()>0;session.setVisibility(shown?View.VISIBLE:View.GONE);if(!nested)header.setVisibility(shown?View.VISIBLE:View.GONE);}
        });if(session.length()>0)session.setVisibility(View.VISIBLE);
        if(nested){
            // Detail pages: WeChat-style centred title between two round glass controls of equal width.
            heading=text("",17,INK,true);heading.setMaxLines(2);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.setGravity(Gravity.CENTER);heading.setAccessibilityHeading(true);
            session.setGravity(Gravity.CENTER);session.setSingleLine(true);session.setEllipsize(android.text.TextUtils.TruncateAt.END);titles.addView(heading);titles.addView(session);
            header.addView(leading,new LinearLayout.LayoutParams(dp(104),dp(48)));header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));header.addView(trailing,new LinearLayout.LayoutParams(dp(104),dp(48)));
        }else{
            // Tab roots carry no tab name: the dock already says where you are. The view stays for code that sets it.
            heading=theme.title(ConsoleTheme.tabLabel(group));heading.setVisibility(View.GONE);titles.addView(session);titles.addView(heading);
            header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));header.addView(trailing,new LinearLayout.LayoutParams(-2,dp(48)));
        }
        // Tab roots have no header: 总览 starts with its board or sign-in card, 功能 with search, 我 with the account
        // card, right under the status bar. The header comes back only while its line reports sign-in progress.
        boolean bare=!nested&&(loggedIn()||session.length()==0);if(bare)header.setVisibility(View.GONE);
        // Tab roots run edge to edge: the page scrolls under the status bar and their large title scrolls away with it.
        // Detail pages keep a fixed header, so back and 记一笔 stay one tap away.
        final boolean edge=!nested;
        if(!edge)body.addView(header);
        scroll=new PullScrollView(this,theme.effects);
        scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        // Scroll-edge effect: content dissolves under the header (or the status bar) instead of being cut at a hard line.
        scroll.setVerticalFadingEdgeEnabled(true);scroll.setFadingEdgeLength(dp(28));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(bare?12:4),dp(16),dp(120));
        final LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        if(edge){column.addView(header);column.addView(content);scroll.addView(column);}else scroll.addView(content);
        // The page area: the scroll, and above it the glass mark pull-to-refresh brings in on pages that use it.
        FrameLayout pageArea=new FrameLayout(this);pageArea.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        pullMark=new FrameLayout(this);pullMark.setBackground(theme.glass(-1));pullMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);pullMark.setVisibility(View.INVISIBLE);
        pullGlyph=theme.icon("refresh",theme.ink,20);((FrameLayout)pullMark).addView(pullGlyph,new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));
        pageArea.addView(pullMark,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        body.addView(pageArea,new LinearLayout.LayoutParams(-1,0,1));
        int current=TABS.indexOf(group);
        // Content keeps running under the dock to the screen edge, then fades into the sky below the gesture bar.
        View fade=new View(this);fade.setBackground(theme.bottomFade());fade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);stage.addView(fade,new FrameLayout.LayoutParams(-1,dp(40),Gravity.BOTTOM));
        final int dockHeight=dp(getResources().getConfiguration().fontScale>=1.3f?72:64);
        nav=new GlassPane(theme,stage);FrameLayout.LayoutParams dock=new FrameLayout.LayoutParams(-1,dockHeight,Gravity.BOTTOM);dock.leftMargin=dock.rightMargin=dp(16);root.addView(nav,dock);
        final StatusIsland island=new StatusIsland(this);island.setVisibility(View.GONE);root.addView(island,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(6),dp(6),dp(6),dp(6));
        View lens=new View(this);lens.setBackground(theme.lens());lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        nav.lens(lens,TABS.size(),current,shownTab,dp(6));nav.addView(tabs,new FrameLayout.LayoutParams(-1,-1));shownTab=current;
        for(String label:TABS) {
            boolean selected=group.equals(label);String name=ConsoleTheme.tabLabel(label);LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(Gravity.CENTER);
            tab.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,0),-1));tab.setSelected(selected);tab.setFocusable(true);tab.setClickable(true);tab.setContentDescription(name+(selected?"，已选中":""));tab.setTooltipText(name);
            if(label.equals("设置")){
                // The dot means a newer release can be installed; a background check may show it later without a rebuild.
                FrameLayout badge=new FrameLayout(this);badge.addView(tabGlyph(label,selected),new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
                View dot=theme.dot(theme.unread,8);badge.addView(dot,new FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP|Gravity.END));tab.addView(badge,new LinearLayout.LayoutParams(dp(32),dp(26)));
                meDot=dot;meTab=tab;meTabDescription=name+(selected?"，已选中":"");showDots();
            }else tab.addView(tabGlyph(label,selected));
            TextView caption=text(name,11,selected?INK:MUTED,selected);caption.setPadding(0,dp(2),0,0);caption.setSingleLine(true);caption.setGravity(Gravity.CENTER);caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tab.addView(caption);
            tab.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);if(group.equals(label))tabHome();else switchTab(label);});theme.press(tab);
            tabs.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
        }
        final LinearLayout page=content;final GlassPane bar=nav;final PullScrollView pager=scroll;
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            // With the status bar hidden (我 → 外观) tab roots start just below the screen edge; detail pages still keep
            // their header clear of the camera cutout so the back control and title are never under it.
            String style=statusStyle();
            if(StatusBarStyle.hides(style)){
                android.view.DisplayCutout dc=insets.getDisplayCutout();android.graphics.Rect band=dc==null?null:dc.getBoundingRectTop();
                int[] outline=null;
                // The cutout's own outline is the real hole on devices that describe it (Pixels); OPPO only declares a band.
                if(dc!=null&&Build.VERSION.SDK_INT>=31&&dc.getCutoutPath()!=null){android.graphics.RectF b=new android.graphics.RectF();dc.getCutoutPath().computeBounds(b,true);if(!b.isEmpty())outline=new int[]{Math.round(b.left),Math.round(b.top),Math.round(b.right),Math.round(b.bottom)};}
                int[] cut=StatusBarStyle.hole(band==null||band.isEmpty()?null:new int[]{band.left,band.top,band.right,band.bottom},outline);
                int[] shape=StatusBarStyle.island(root.getWidth()>0?root.getWidth():getResources().getDisplayMetrics().widthPixels,cut,dp(3));
                boolean islandOn=StatusBarStyle.ISLAND.equals(style);island.setVisibility(islandOn?View.VISIBLE:View.GONE);
                if(islandOn){island.place(shape,cut,StatusBarStyle.compactWidth(root.getWidth()>0?root.getWidth():getResources().getDisplayMetrics().widthPixels,cut,dp(3)));island.show(islandItem(true),islandItem(false));}
                int top=StatusBarStyle.top(style,edge,insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top,insets.getInsets(WindowInsets.Type.displayCutout()).top,bars.top,shape[3],dp(6));
                bars=android.graphics.Insets.of(bars.left,top,bars.right,bars.bottom);
            }else island.setVisibility(View.GONE);
            body.setPadding(bars.left,edge?0:bars.top,bars.right,0);
            // Edge to edge the page starts below the status bar, and content scrolled up fades out across its height.
            if(edge){column.setPadding(0,bars.top,0,0);pager.setFadingEdgeLength(Math.max(dp(28),bars.top));}
            pager.setTopInset(edge?bars.top:0);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)bar.getLayoutParams();lp.leftMargin=bars.left+dp(16);lp.rightMargin=bars.right+dp(16);lp.bottomMargin=bars.bottom+dp(10);bar.setLayoutParams(lp);
            fade.getLayoutParams().height=bars.bottom+dp(28);fade.requestLayout();
            page.setPadding(dp(16),edge?0:dp(4),dp(16),bars.bottom+(bar.getVisibility()==View.GONE?dp(24):dockHeight+dp(10+24)));return insets;});root.requestApplyInsets();
        if(device!=null){nav.setVisibility(View.GONE);showDeviceAuthorization(device);}
        else if(about)about();
        else if(boardEdit)boardEditor();
        else if(hiddenPage)hiddenPage();
        else if(accountPage)accountPage();
        else if(appearancePage)appearancePage();
        else if(privacyPage)privacyPage();
        else if(noticesPage)noticesPage();
        else if(detail!=null)showFeature(detail,false);
        else if(group.equals("总览"))home(); else if(group.equals("设置"))settings(); else features();
        if(enterFrom!=0){theme.enter(scroll,enterFrom==1?28:enterFrom==-1?-28:0,enterFrom==2?12:0);enterFrom=0;}
    }
    private String statusStyle(){return StatusBarStyle.resolve(prefs().getString(StatusBarStyle.KEY,null),prefs().getBoolean(StatusBarStyle.LEGACY,false));}
    /** 我 → 外观 → 隐藏状态栏: hidden until swiped in from the top edge, shown again when the switch is off. */
    private void applyStatusBar(){
        WindowInsetsController c=getWindow().getInsetsController();if(c==null)return;
        if(StatusBarStyle.hides(statusStyle())){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.statusBars());}
        else c.show(WindowInsets.Type.statusBars());
    }
    // Dialogs and other windows can bring the status bar back; hide it again whenever the page regains focus.
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)applyStatusBar();}
    /** What the status line says when nothing is in progress: only the preview build's label. */
    private static String idleSession(){return PreviewMode.isBuild()?"界面预览 · 合成数据":"";}
    /** Round glass header control (e.g. 记一笔); refreshing is pull-to-refresh on every page, never a header button. */
    private FrameLayout circle(String symbol,String label,Runnable action){FrameLayout c=theme.circle(symbol,label,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(48),dp(48));p.leftMargin=dp(4);c.setLayoutParams(p);return c;}
    private void home() {
        if(!loggedIn()){
            LinearLayout welcome=card();welcome.setGravity(Gravity.CENTER_HORIZONTAL);welcome.setPadding(dp(22),dp(28),dp(22),dp(22));
            FrameLayout mark=new FrameLayout(this);mark.setBackground(theme.glass(-1));mark.addView(theme.tabIcon("grid",true,40),new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));welcome.addView(mark,new LinearLayout.LayoutParams(dp(80),dp(80)));
            TextView title=text("登录你的工作台",24,INK,true);title.setGravity(Gravity.CENTER);title.setPadding(0,dp(18),0,dp(8));welcome.addView(title);
            TextView hint=text("在应用内用 Passkey 验证，安全查看你的数据与资产。",15,MUTED,false);hint.setGravity(Gravity.CENTER);hint.setPadding(0,0,0,dp(22));welcome.addView(hint);
            Button passkey=button("使用 Passkey 登录",this::login);theme.button(passkey,true);welcome.addView(passkey,new LinearLayout.LayoutParams(-1,-2));
            Button device=button("通过已有设备授权",this::deviceLogin);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,-2);d.topMargin=dp(10);welcome.addView(device,d);
            Button google=button("使用 Google 登录（只读）",this::googleLogin);LinearLayout.LayoutParams g=new LinearLayout.LayoutParams(-1,-2);g.topMargin=dp(10);welcome.addView(google,g);
        }else{
            // 总览 is only 看板 and 常用: the board is the person's own combination of feature cards.
            // Pull down on 总览 to refresh the board; the mark keeps turning until every card's read has finished.
            final PullScrollView page=scroll;page.setRefresh(pullMark,pullGlyph,"刷新看板",()->{if(!PreviewMode.isBuild())PreferenceSyncHub.foreground(this);loadBoard(true,page::finishRefresh);});
            board=new LinearLayout(this);board.setOrientation(LinearLayout.VERTICAL);content.addView(board,new LinearLayout.LayoutParams(-1,-2));loadBoard(false);
        }
        section("常用");Set<String> hidden=hiddenFeatures();quick(FeatureUsage.top(FeatureUsage.parse(prefs().getString("featureUsage",null)),System.currentTimeMillis(),4,id->find(id)!=null&&!"overview".equals(id)&&!hidden.contains(id)));
        restoreViewport();
    }
    private List<String> boardCards(){return Dashboard.parse(prefs().getString("dashboard",null));}
    private void saveBoard(List<String> ids){prefs().edit().putString("dashboard",Dashboard.serialize(ids)).apply();PreferenceSyncHub.note(this,"board");}
    private List<Dashboard.Clock> boardClocks(){return Dashboard.clocks(prefs().getString(Dashboard.CLOCKS,null));}
    private void saveClocks(List<Dashboard.Clock> clocks){prefs().edit().putString(Dashboard.CLOCKS,Dashboard.serializeClocks(clocks)).apply();GodWidgets.refreshAll(this);PreferenceSyncHub.note(this,"clocks");}
    private void openBoardEditor(){++generation;selectedId=BOARD;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    /**
     * Lays out the chosen cards (wide ones on their own row, small ones two to a row), shows any cached reads at
     * once, and fetches each distinct API path a single time. A failing source only blanks its own cards.
     */
    private void loadBoard(boolean refresh){loadBoard(refresh,null);}
    /** {@code done} runs on the main thread once every read this call started has finished (at once when none did). */
    private void loadBoard(boolean refresh,Runnable done){
        final LinearLayout box=board;if(box==null||!resumed){if(done!=null)done.run();return;}box.removeAllViews();
        List<String> ids=boardCards();
        if(ids.isEmpty()){LinearLayout c=theme.card(box);c.setBackground(theme.ripple(theme.card(),24));c.setFocusable(true);c.setClickable(true);c.setOnClickListener(v->openBoardEditor());
            c.addView(text("看板是空的",15,INK,true));TextView t=text("点这里添加想在这里看到的功能卡片。",13,MUTED,false);t.setPadding(0,dp(4),0,0);c.addView(t);if(done!=null)done.run();return;}
        final Map<String,List<LinearLayout>> shells=new HashMap<>();boardShells=shells;ticker.removeCallbacks(tokenTick);LinearLayout row=null;
        for(String id:ids){Dashboard.Card card=Dashboard.find(id);if(card==null)continue;LinearLayout shell;
            if(card.wide){row=null;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);shell=FeatureRenderer.widgetShell(this,box,card,lp);}
            else{if(row==null||row.getChildCount()>=2){row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(10);box.addView(row,rp);}
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(row.getChildCount()==1)lp.leftMargin=dp(10);shell=FeatureRenderer.widgetShell(this,row,card,lp);}
            editOnLongPress(shell);if(card.path==null){FeatureRenderer.clocks(this,shell,boardClocks());continue;}shells.computeIfAbsent(card.path,k->new ArrayList<>()).add(shell);}
        // Keep a lone small card at half width instead of stretching it across the row.
        for(int i=0;i<box.getChildCount();i++){View v=box.getChildAt(i);if(v instanceof LinearLayout&&((LinearLayout)v).getOrientation()==LinearLayout.HORIZONTAL&&((LinearLayout)v).getChildCount()==1){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,1,1);lp.leftMargin=dp(10);((LinearLayout)v).addView(new View(this),lp);}}
        final int epoch=++generation;final int[] reading={0};
        final Runnable finished=()->{if(--reading[0]==0&&done!=null)done.run();};
        for(String path:Dashboard.paths(ids)){final List<LinearLayout> targets=shells.get(path);JSONObject cached=snapshots.get(path);
            if(cached!=null)fillBoard(targets,cached);else for(LinearLayout shell:targets)FeatureRenderer.widgetLoading(this,shell);
            if(cached!=null&&!refresh){if(TOKENS.equals(path))scheduleTokenTick(cached);continue;}
            reading[0]++;
            boardReads.execute(()->{if(epoch!=generation){runOnUiThread(finished);return;}long started=SystemClock.elapsedRealtime();
                try{JSONObject data=read(path);android.util.Log.i("GodBoard",path+" ms="+(SystemClock.elapsedRealtime()-started));runOnUiThread(()->{try{if(epoch!=generation||!resumed||isDestroyed())return;saveViewport();snapshots.put(path,data);GodWidget.observe(this,path,data);fillBoard(targets,data);restoreViewport();if(TOKENS.equals(path))scheduleTokenTick(data);}finally{finished.run();}});}
                catch(Exception e){runOnUiThread(()->{try{if(epoch!=generation||!resumed||isDestroyed())return;
                    // A refresh may have invalidated the stored session: show sign-in instead of a board that can only fail.
                    if(!loggedIn()){navigation.clear();viewportReady=false;selectedId=null;project=null;ledgerCursor=null;ledgerPages.clear();snapshots.clear();if(ledger!=null)ledger.close();group="总览";build();error("请重新登录",safeError(e));return;}
                    if(snapshots.get(path)==null)for(LinearLayout shell:targets)FeatureRenderer.widgetMessage(this,shell,boardNote(path,e));}finally{finished.run();}});}});}
        if(reading[0]==0&&done!=null)done.run();
    }
    /** Long-pressing any 看板 card opens 编辑看板; screen readers announce the long-press action by that name. */
    private void editOnLongPress(View card){
        if(!card.isClickable()){card.setBackground(theme.ripple(theme.card(),24));theme.press(card);}
        card.setLongClickable(true);
        card.setOnLongClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);openBoardEditor();return true;});
        card.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK,"编辑看板"));}});
    }
    private void fillBoard(List<LinearLayout> shells,JSONObject data){for(LinearLayout shell:shells){Dashboard.Card card=Dashboard.find(String.valueOf(shell.getTag()));if(card!=null)FeatureRenderer.widget(this,shell,card,data);}}
    /** 编辑看板: switch cards on or off and order them; changes apply at once and survive restarts (layout only). */
    private void boardEditor(){
        heading.setText("编辑看板");
        leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        List<String> ids=boardCards();
        TextView hint=text("打开的卡片会按顺序显示在总览，按住右侧的 ≡ 拖动调整顺序。",13,MUTED,false);hint.setPadding(dp(6),dp(4),dp(6),dp(4));content.addView(hint);
        section("已添加");LinearLayout on=groupCard();
        for(int i=0;i<ids.size();i++){Dashboard.Card card=Dashboard.find(ids.get(i));if(card!=null)boardRow(on,card,true,i>0,i<ids.size()-1);}
        if(ids.isEmpty()){TextView t=text("还没有添加卡片",14,MUTED,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(14),dp(12),dp(14));on.addView(t);}
        if(ids.contains("clocks")){section("时区时钟卡片");LinearLayout zones=groupCard();clockRows(zones);}
        if(ids.contains("calendar")){section("重置日历卡片");LinearLayout range=groupCard();int days=Dashboard.calendarDays(this);
            featureRow("显示范围 · "+Dashboard.calendarRange(days),"总览列出这段时间内的全部重置","calendar",range,this::calendarRangeMenu);}
        if(ids.contains("tokens")){section("Token 额度卡片");LinearLayout accounts=groupCard();JSONObject cached=snapshots.get("tokens/quotas");
            if(cached!=null)tokenToggles(accounts,cached);
            else{TextView wait=text("正在读取账号…",14,MUTED,false);wait.setPadding(dp(12),dp(14),dp(12),dp(14));accounts.addView(wait);int epoch=generation;
                worker.execute(()->{try{JSONObject data=read("tokens/quotas");runOnUiThread(()->{if(epoch!=generation||!BOARD.equals(selectedId)||isDestroyed())return;snapshots.put("tokens/quotas",data);accounts.removeAllViews();tokenToggles(accounts,data);});}
                    catch(Exception e){runOnUiThread(()->{if(epoch==generation&&!isDestroyed()){String why="暂时读不到账号："+explain("tokens/quotas",e);wait.setText(why);}});}});}}
        List<Dashboard.Card> rest=new ArrayList<>();for(Dashboard.Card card:Dashboard.ALL)if(!ids.contains(card.id))rest.add(card);
        if(!rest.isEmpty()){section("可添加");LinearLayout off=groupCard();for(Dashboard.Card card:rest)boardRow(off,card,false,false,false);}
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(6);content.addView(button("恢复默认组合",()->{saveBoard(Dashboard.DEFAULT);build();}),rp);
        restoreViewport();
    }
    /** 重置日历's range on 总览: 今天 or the next 3, 7, 14 or 30 days; applied at once and synced with the account. */
    private void calendarRangeMenu(){
        List<Integer> ranges=Dashboard.CALENDAR_RANGES;String[] names=new String[ranges.size()];for(int i=0;i<names.length;i++)names[i]=Dashboard.calendarRange(ranges.get(i));
        show(new AlertDialog.Builder(this).setTitle("重置日历显示范围").setSingleChoiceItems(names,ranges.indexOf(Dashboard.calendarDays(this)),(d,w)->{
            prefs().edit().putString(Dashboard.CALENDAR_DAYS,String.valueOf(ranges.get(w))).apply();PreferenceSyncHub.note(this,"calendarDays");d.dismiss();build();}).setNegativeButton("取消",null));
    }
    /** 时区时钟 zones: tap to rename, the bin removes, 添加时区 picks a zone and then its name. Synced with the account. */
    private void clockRows(LinearLayout group){
        group.removeAllViews();List<Dashboard.Clock> list=boardClocks();long now=System.currentTimeMillis();
        for(int i=0;i<list.size();i++){Dashboard.Clock c=list.get(i);final int index=i;
            if(group.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(.5f));d.setMargins(dp(14),0,dp(14),0);group.addView(divider,d);}
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(6),dp(4),dp(6));row.setMinimumHeight(dp(56));
            row.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,18),18));row.setFocusable(true);row.setClickable(true);row.setOnClickListener(v->nameClock(group,index,c.name,c.zone));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.addView(text(c.name,15,INK,false));
            java.util.TimeZone tz=java.util.TimeZone.getTimeZone(c.zone);TextView zone=text(tz.getDisplayName(tz.inDaylightTime(new Date(now)),java.util.TimeZone.LONG,Locale.SIMPLIFIED_CHINESE)+" · "+Dashboard.offsetLabel(c.zone,java.time.ZoneId.systemDefault(),now),12,MUTED,false);zone.setPadding(0,dp(2),0,0);labels.addView(zone);
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.setContentDescription(c.name+"，"+zone.getText()+"，点按改名");
            FrameLayout remove=circle("trash","移除 "+c.name,()->{List<Dashboard.Clock> next=boardClocks();if(index<next.size())next.remove(index);saveClocks(next);clockRows(group);});row.addView(remove,new LinearLayout.LayoutParams(dp(48),dp(48)));
            group.addView(row);}
        if(list.isEmpty()){TextView t=text("还没有时区",14,MUTED,false);t.setPadding(dp(14),dp(14),dp(14),dp(6));group.addView(t);}
        if(list.size()<Dashboard.MAX_CLOCKS){if(group.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(.5f));d.setMargins(dp(14),0,dp(14),0);group.addView(divider,d);}
            LinearLayout add=new LinearLayout(this);add.setGravity(Gravity.CENTER_VERTICAL);add.setPadding(dp(14),dp(6),dp(14),dp(6));add.setMinimumHeight(dp(52));add.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,18),18));add.setFocusable(true);add.setClickable(true);
            add.addView(theme.icon("plus",ACCENT,18),new LinearLayout.LayoutParams(dp(18),dp(18)));TextView l=text("添加时区",15,ACCENT,false);l.setPadding(dp(10),0,0,0);add.addView(l);
            add.setOnClickListener(v->{String[] names=new String[Dashboard.SUGGESTED.size()];for(int i=0;i<names.length;i++)names[i]=Dashboard.SUGGESTED.get(i).name+"  ·  "+Dashboard.SUGGESTED.get(i).zone;
                show(new AlertDialog.Builder(this).setTitle("添加时区").setItems(names,(d,w)->nameClock(group,-1,Dashboard.SUGGESTED.get(w).name,Dashboard.SUGGESTED.get(w).zone)).setNegativeButton("取消",null));});
            group.addView(add);}
    }
    /** Names a new clock ({@code index} -1) or renames one; up to 12 characters. */
    private void nameClock(LinearLayout group,int index,String name,String zone){
        EditText input=new EditText(this);theme.input(input);input.setSingleLine(true);input.setText(name);input.setSelection(input.getText().length());input.setHint("名称，例如 Tibo");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        FrameLayout wrap=new FrameLayout(this);wrap.setPadding(dp(20),dp(8),dp(20),0);wrap.addView(input);
        show(new AlertDialog.Builder(this).setTitle(index<0?"时区名称":"修改名称").setView(wrap).setPositiveButton("保存",(d,w)->{String clean=Dashboard.clockName(input.getText().toString());if(clean==null)return;
            List<Dashboard.Clock> next=boardClocks();Dashboard.Clock c=new Dashboard.Clock(clean,zone);if(index<0){if(next.size()<Dashboard.MAX_CLOCKS)next.add(c);}else if(index<next.size())next.set(index,c);saveClocks(next);clockRows(group);}).setNegativeButton("取消",null));
    }
    /** One switch per connected Token account: whether its row shows on the 看板's Token 额度 card, remembered on this phone by account. */
    private void tokenToggles(LinearLayout group,JSONObject p){
        Set<String> hidden=Dashboard.hiddenTokens(this);List<JSONObject> list=new ArrayList<>();
        for(JSONObject a:FeatureRenderer.tokenAccounts(p))if(TokensModel.shownOnPage(a))list.add(a);
        list.sort(Comparator.<JSONObject>comparingInt(a->TokensModel.SECTIONS.indexOf(TokensModel.section(a))).thenComparing(TokensModel.SLOT_ORDER));
        if(list.isEmpty()){TextView t=text("还没有已接入的账号",14,MUTED,false);t.setPadding(dp(12),dp(14),dp(12),dp(14));group.addView(t);return;}
        for(JSONObject a:list){String id=a.optString("id");
            if(group.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(.5f));d.setMargins(dp(38),0,dp(14),0);group.addView(divider,d);}
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(8),dp(8),dp(8));row.setMinimumHeight(dp(56));
            View dot=theme.dot(TokensPages.tint(theme,a.optString("product")),8);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(14);row.addView(dot);
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);TextView who=text(TokensModel.displayName(a),15,INK,false);who.setSingleLine(true);who.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(who);
            TextView product=text(TokensModel.productName(a),12,MUTED,false);product.setPadding(0,dp(2),0,0);labels.addView(product);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            Switch toggle=new Switch(this);toggle.setChecked(!hidden.contains(id));toggle.setContentDescription("在看板显示 "+TokensModel.productName(a)+" "+TokensModel.displayName(a));toggle.setMinimumHeight(dp(48));
            toggle.setOnCheckedChangeListener((b,checked)->{prefs().edit().putString(Dashboard.HIDDEN_TOKENS,Dashboard.toggleHidden(prefs().getString(Dashboard.HIDDEN_TOKENS,null),id)).apply();PreferenceSyncHub.note(this,"hiddenQuotaCards");});
            row.addView(toggle);group.addView(row);}
    }
    private void boardRow(LinearLayout group,Dashboard.Card card,boolean added,boolean canUp,boolean canDown){
        if(group.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(.5f));d.setMargins(dp(62),0,dp(14),0);group.addView(divider,d);}
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(8),dp(8),dp(8));row.setMinimumHeight(dp(60));
        row.addView(theme.tile(card.symbol,36),new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(14),0,dp(6),0);labels.addView(text(card.title,15,INK,false));
        TextView sub=text(card.subtitle+(card.wide?" · 整行":" · 半宽"),12,MUTED,false);sub.setPadding(0,dp(2),0,0);labels.addView(sub);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);toggle.setChecked(added);toggle.setContentDescription((added?"移除":"添加")+card.title);toggle.setMinimumHeight(dp(48));
        toggle.setOnCheckedChangeListener((b,checked)->{saveBoard(Dashboard.toggle(boardCards(),card.id));b.post(this::build);});
        row.addView(toggle);
        if(added){row.setTag(card.id);row.setPadding(dp(12),dp(8),dp(2),dp(8));
            FrameLayout handle=new FrameLayout(this);ImageView grip=theme.icon("drag",INK,20);grip.setAlpha(.5f);handle.addView(grip,new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));
            handle.setContentDescription("拖动调整 "+card.title+" 的位置");row.addView(handle,new LinearLayout.LayoutParams(dp(44),dp(48)));
            dragToReorder(group,row,handle);movable(row,card,canUp,canDown);}
        group.addView(row);
    }
    /** Screen readers move a card with 上移 / 下移 instead of dragging. */
    private void movable(View row,Dashboard.Card card,boolean canUp,boolean canDown){
        row.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);
                if(canUp)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_up,"上移"));
                if(canDown)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_down,"下移"));}
            @Override public boolean performAccessibilityAction(View host,int action,Bundle args){
                if(action==R.id.action_move_up||action==R.id.action_move_down){saveBoard(Dashboard.move(boardCards(),card.id,action==R.id.action_move_up?-1:1));build();return true;}
                return super.performAccessibilityAction(host,action,args);}});
    }
    /**
     * Press the ≡ handle and drag: the row follows the finger above the others, the rows it passes slide out of the way
     * with a tick, and releasing saves the new order. The page does not scroll while a row is held.
     */
    // The handle only drags; it is not a button. Screen readers move cards with the row's 上移 / 下移 actions instead.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void dragToReorder(LinearLayout group,LinearLayout row,View handle){
        final float[] start=new float[1];final int[] from={-1},to={-1};
        handle.setOnTouchListener((v,e)->{
            List<View> rows=new ArrayList<>();for(int i=0;i<group.getChildCount();i++){View c=group.getChildAt(i);if(c.getTag() instanceof String)rows.add(c);}
            int pitch=rows.size()>1?rows.get(1).getTop()-rows.get(0).getTop():row.getHeight();
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    start[0]=e.getRawY();from[0]=to[0]=rows.indexOf(row);v.getParent().requestDisallowInterceptTouchEvent(true);
                    row.setBackground(theme.card(20));row.setElevation(dp(10));row.setTranslationZ(dp(4));v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    for(int i=0;i<group.getChildCount();i++)if(!(group.getChildAt(i).getTag() instanceof String))group.getChildAt(i).setAlpha(0f);
                    return true;
                case MotionEvent.ACTION_MOVE:{
                    if(from[0]<0)return true;float dy=e.getRawY()-start[0];row.setTranslationY(dy);
                    int target=Math.max(0,Math.min(rows.size()-1,from[0]+Math.round(dy/Math.max(1,pitch))));
                    if(target!=to[0]){to[0]=target;v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        for(int j=0;j<rows.size();j++){View other=rows.get(j);if(other==row)continue;float shift=j>from[0]&&j<=target?-pitch:j<from[0]&&j>=target?pitch:0;
                            if(theme.effects)other.animate().translationY(shift).setDuration(150).start();else other.setTranslationY(shift);}}
                    return true;}
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:{
                    if(from[0]<0)return true;List<String> order=new ArrayList<>();for(View r:rows)if(r!=row)order.add((String)r.getTag());
                    int at=Math.max(0,Math.min(order.size(),to[0]));order.add(at,(String)row.getTag());from[0]=-1;
                    for(View r:rows){r.animate().cancel();r.setTranslationY(0);}row.setElevation(0);row.setTranslationZ(0);
                    if(to[0]!=rows.indexOf(row))saveBoard(order);v.post(this::build);return true;}
                default:return false;
            }
        });
    }
    /** One-tap shortcuts in a single glass card, like WeChat's Discover entries: icon first, short name below. */
    private void quick(List<String> ids){
        LinearLayout grid=card();grid.setOrientation(LinearLayout.HORIZONTAL);grid.setPadding(dp(4),dp(10),dp(4),dp(8));
        for(String id:ids){FeatureCatalog.Feature f=find(id);if(f==null)continue;
            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER_HORIZONTAL);cell.setPadding(dp(2),dp(4),dp(2),dp(4));
            cell.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,16),16));cell.setFocusable(true);cell.setClickable(true);cell.setContentDescription(f.title+"，"+f.subtitle);cell.setOnClickListener(v->open(f));theme.press(cell);
            cell.addView(theme.tile(ConsoleTheme.symbol(f.id),40),new LinearLayout.LayoutParams(dp(40),dp(40)));
            TextView name=text(f.title,12,INK,false);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setPadding(0,dp(6),0,0);cell.addView(name);
            grid.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}
    }
    private LinearLayout board;
    /** What the 功能 search field holds; kept across rebuilds and tab switches, never persisted. */
    private String featureQuery="";
    /**
     * 功能, as on iOS: 洞察, 资产 and 工具 as sections of one list, with a search field over the bundled catalog (titles,
     * descriptions, ids and aliases; never account data). Typing only redraws the list, so the field keeps focus.
     */
    private void features(){
        LinearLayout field=new LinearLayout(this);field.setGravity(Gravity.CENTER_VERTICAL);field.setBackground(theme.glass(-1));field.setPadding(dp(14),0,dp(6),0);field.setMinimumHeight(dp(48));
        field.addView(theme.icon("search",MUTED,18),new LinearLayout.LayoutParams(dp(18),dp(18)));
        EditText input=new EditText(this);input.setHint("搜索功能");input.setText(featureQuery);input.setSingleLine(true);input.setBackground(null);input.setTextSize(16);input.setTextColor(INK);input.setHintTextColor(MUTED);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);input.setPadding(dp(10),dp(10),dp(4),dp(10));input.setContentDescription("搜索功能");
        field.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout clear=theme.circle("clear","清除搜索",()->{input.setText("");});clear.setBackground(null);clear.setVisibility(featureQuery.isEmpty()?View.GONE:View.VISIBLE);field.addView(clear,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.bottomMargin=dp(4);content.addView(field,fp);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);content.addView(list);
        input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){v.clearFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);return true;}return false;});
        input.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void afterTextChanged(android.text.Editable s){featureQuery=s.toString();clear.setVisibility(featureQuery.trim().isEmpty()?View.GONE:View.VISIBLE);featureList(list);}
        });
        featureList(list);restoreViewport();
    }
    private void featureList(LinearLayout list){
        list.removeAllViews();String q=featureQuery.trim();Set<String> hidden=hiddenFeatures();Map<String,FeatureUsage.Entry> usage=FeatureUsage.parse(prefs().getString("featureUsage",null));
        if(q.isEmpty()){
            for(String section:FeatureCatalog.SECTIONS){List<FeatureCatalog.Feature> shown=new ArrayList<>();for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals(section)&&!hidden.contains(f.id))shown.add(f);
                if(shown.isEmpty())continue;shown=lru(usage,shown);
                TextView t=text(section,15,INK,true);t.setAccessibilityHeading(true);t.setPadding(dp(6),dp(12),0,dp(8));list.addView(t);
                LinearLayout g=groupCard(list);for(FeatureCatalog.Feature f:shown)featureRow(f,g);}
            TextView tip=text("长按功能可以隐藏，隐藏的功能在「我 → 已隐藏的功能」。",12,MUTED,false);tip.setPadding(dp(6),dp(4),dp(6),dp(8));list.addView(tip);
            return;
        }
        List<FeatureCatalog.Feature> found=new ArrayList<>();int hiddenMatches=0;
        for(FeatureCatalog.Feature f:FeatureCatalog.search(q))if(hidden.contains(f.id))hiddenMatches++;else found.add(f);
        found=lru(usage,found);
        TextView count=text(found.isEmpty()?"没有找到与「"+q+"」相关的功能":"找到 "+found.size()+" 个功能",13,MUTED,false);count.setPadding(dp(6),dp(12),0,dp(8));count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);list.addView(count);
        if(!found.isEmpty()){LinearLayout g=groupCard(list);for(FeatureCatalog.Feature f:found)featureRow(f,g);}
        // A search never silently misses a hidden feature: it points to where hidden ones are kept.
        if(hiddenMatches>0){LinearLayout g=groupCard(list);featureRow("已隐藏的功能","有 "+hiddenMatches+" 个已隐藏的功能也匹配","eye",g,this::openHiddenPage);}
    }
    /** 功能 lists, and search results, put the most recently opened first; never-opened ones keep catalog order. */
    private List<FeatureCatalog.Feature> lru(Map<String,FeatureUsage.Entry> usage,List<FeatureCatalog.Feature> features){
        List<String> ids=new ArrayList<>();for(FeatureCatalog.Feature f:features)ids.add(f.id);
        List<FeatureCatalog.Feature> out=new ArrayList<>();for(String id:FeatureUsage.recent(usage,ids))out.add(find(id));return out;
    }
    private void featureRow(FeatureCatalog.Feature f){featureRow(f,groupCard());}
    /** A feature in 功能 or 我 → 管理: tap opens it, long-press offers 隐藏. */
    private void featureRow(FeatureCatalog.Feature f,LinearLayout list){
        LinearLayout row=featureRow(f.title,f.subtitle,ConsoleTheme.symbol(f.id),list,()->open(f));
        longPress(row,"隐藏或更多操作",()->{row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            show(new AlertDialog.Builder(this).setTitle(f.title).setItems(new String[]{"打开","隐藏"},(d,w)->{if(w==0)open(f);else hide(f);}).setNegativeButton("取消",null));});
    }
    /** Long-press on {@code row}, announced to screen readers by {@code label}. */
    private void longPress(View row,String label,Runnable action){
        row.setLongClickable(true);row.setOnLongClickListener(v->{action.run();return true;});
        row.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK,label));}});
    }
    private Set<String> userHidden(){return FeatureVisibility.parse(prefs().getString(FeatureVisibility.HIDDEN,null));}
    private Set<String> userShown(){return FeatureVisibility.parse(prefs().getString(FeatureVisibility.SHOWN,null));}
    /** Features left out of 功能, 我 → 管理 and 常用; they stay reachable from 已隐藏的功能, shortcuts and the board. */
    private Set<String> hiddenFeatures(){return FeatureVisibility.hidden(userHidden(),userShown(),FeatureCatalog.DEFAULT_HIDDEN);}
    private void saveVisibility(List<Set<String>> next){prefs().edit().putString(FeatureVisibility.HIDDEN,FeatureVisibility.serialize(next.get(0))).putString(FeatureVisibility.SHOWN,FeatureVisibility.serialize(next.get(1))).apply();PreferenceSyncHub.note(this,"hiddenFeatures");PreferenceSyncHub.note(this,"shownFeatures");}
    private void hide(FeatureCatalog.Feature f){
        saveVisibility(FeatureVisibility.hide(userHidden(),userShown(),f.id));build();
        Toast.makeText(this,"已隐藏「"+f.title+"」，可在「我 → 已隐藏的功能」恢复",Toast.LENGTH_SHORT).show();
    }
    private void unhide(FeatureCatalog.Feature f){
        saveVisibility(FeatureVisibility.show(userHidden(),userShown(),f.id,FeatureCatalog.DEFAULT_HIDDEN));build();
        Toast.makeText(this,"「"+f.title+"」已放回"+("设置".equals(f.group)?"「我 → 管理」":"「功能」"),Toast.LENGTH_SHORT).show();
    }
    private void openHiddenPage(){
        // From a 功能 search this crosses to 我, keeping 功能's own reading context as a tab switch does.
        if(!"设置".equals(group)){saveViewport();navigation.switchTo(group,"设置",new NavigationMemory.Destination(selectedId,project,ledgerCursor,ledgerPages));group="设置";}
        ++generation;selectedId=HIDDEN_PAGE;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    /**
     * 我 → 已隐藏的功能: everything hidden, in catalog order. Tap opens a feature as usual; 显示 puts it back where it
     * was. Features hidden by default say why, so a person knows what to expect before showing them again.
     */
    private void hiddenPage(){
        heading.setText("已隐藏的功能");
        leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        Set<String> hidden=hiddenFeatures(),mine=userHidden();
        TextView hint=text("在「功能」里长按可以隐藏功能。点「显示」放回原处；暂未接通的功能默认隐藏。",13,MUTED,false);hint.setPadding(dp(6),dp(4),dp(6),dp(8));content.addView(hint);
        LinearLayout g=groupCard();
        for(FeatureCatalog.Feature f:FeatureCatalog.all()){if(!hidden.contains(f.id))continue;
            String why=!mine.contains(f.id)?FeatureCatalog.DEFAULT_HIDDEN_REASONS.get(f.id):null;
            LinearLayout row=featureRow(f.title,why==null?f.subtitle:why,ConsoleTheme.symbol(f.id),g,()->open(f));
            Button restore=button("显示",()->unhide(f));restore.setContentDescription("显示 "+f.title);restore.setPadding(dp(16),0,dp(16),0);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,dp(40));sp.rightMargin=dp(6);row.addView(restore,row.getChildCount()-1,sp);
            longPress(row,"显示或打开",()->{row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                show(new AlertDialog.Builder(this).setTitle(f.title).setItems(new String[]{"打开","显示"},(d,w)->{if(w==0)open(f);else unhide(f);}).setNegativeButton("取消",null));});}
        if(g.getChildCount()==0){TextView none=text("没有隐藏的功能",15,MUTED,false);none.setPadding(dp(12),dp(16),dp(12),dp(16));g.addView(none);}
        restoreViewport();
    }
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
    private void open(FeatureCatalog.Feature f){recordUse(f.id);++generation;selectedId=f.id;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    /** Counts an open for 常用; the id and time sync with the account, debounced with the other settings. */
    private void recordUse(String id){if("overview".equals(id))return;prefs().edit().putString("featureUsage",FeatureUsage.serialize(FeatureUsage.record(FeatureUsage.parse(prefs().getString("featureUsage",null)),id,System.currentTimeMillis()))).apply();PreferenceSyncHub.note(this,"recentFeatures");}
    @Override public void openFeature(String id){FeatureCatalog.Feature f=find(id);if(f!=null&&loggedIn())open(f);}
    private void showFeature(FeatureCatalog.Feature f,boolean refresh){showFeature(f,refresh,null);}
    /** {@code done} runs on the main thread when this call's read has finished (at once when it starts none). */
    private void showFeature(FeatureCatalog.Feature f,boolean refresh,Runnable done) {
        saveViewport();viewportReady=false;displayedRoute=routeKey();content.removeAllViews();heading.setText(f.title);
        leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        trailing.removeAllViews();
        // Every data page refreshes by pulling down; the mark keeps turning until this page's read has finished.
        if(loggedIn()){final PullScrollView page=scroll;page.setRefresh(pullMark,pullGlyph,"刷新",()->showFeature(f,true,page::finishRefresh));}
        ticker.removeCallbacks(statusTick);if("status".equals(f.id)&&loggedIn())ticker.postDelayed(statusTick,60_000);
        if(!loggedIn()){LinearLayout c=card();c.addView(text(f.title,17,INK,true));TextView t=text("登录后查看此功能的真实数据。",15,MUTED,false);t.setPadding(0,dp(6),0,dp(18));c.addView(t);Button passkey=button("使用 Passkey 登录",this::login);theme.button(passkey,true);c.addView(passkey,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,-2);d.topMargin=dp(10);c.addView(button("通过已有设备授权",this::deviceLogin),d);restoreViewport();return;}
        String path=f.path;
        if(f.path.equals("travel-ledger/current/projects")&&project!=null)path="travel-ledger/current/snapshot?project="+Uri.encode(project)+"&limit=40"+(ledgerCursor==null?"":"&cursor="+Uri.encode(ledgerCursor));
        String key=path; JSONObject cached=key.startsWith("travel-ledger/")?null:snapshots.get(key);
        LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);content.addView(result);
        // Token pages count a reading older than two minutes as 待更新 (the web's rule), so an older cache reloads at once.
        boolean reload=refresh||cached!=null&&f.path.startsWith("tokens/")&&FeatureRenderer.ageMillis(cached)>120_000L;
        if(cached!=null){render(f,result,cached);TextView stamp=text(reload?"正在更新，暂时保留上次结果":"上次读取结果 · 下拉可刷新",12,MUTED,false);result.addView(stamp);restoreViewport();}
        else{TextView loading=text("正在读取…",15,MUTED,false);loading.setPadding(dp(4),dp(12),0,dp(12));result.addView(loading);}
        if(cached!=null&&!reload){if(done!=null)done.run();return;}
        if(!resumed){if(done!=null)done.run();return;}
        int epoch=++generation;
        worker.execute(()->{if(epoch!=generation){if(done!=null)runOnUiThread(done);return;}try{JSONObject data=read(key);runOnUiThread(()->{try{if(epoch!=generation||isDestroyed())return;saveViewport();if(!key.startsWith("travel-ledger/"))snapshots.put(key,data);GodWidget.observe(this,key,data);result.removeAllViews();session.setText(idleSession());render(f,result,data);restoreViewport();}finally{if(done!=null)done.run();}});}
        catch(Exception e){runOnUiThread(()->{try{if(epoch!=generation||isDestroyed())return;if(!loggedIn()){navigation.clear();viewportReady=false;ledgerCursor=null;ledgerPages.clear();snapshots.clear();ledger.close();selectedId=null;project=null;group="总览";build();error("请重新登录",safeError(e));return;}if(e instanceof MobileApi.ApiError&&((MobileApi.ApiError)e).status==401){snapshots.clear();result.removeAllViews();session.setText("会话需要重新验证");}else if(cached==null)result.removeAllViews();LinearLayout c=theme.card(result);String why=explain(f.path,e);TextView warning=text(why,15,WechatDigest.UNAVAILABLE.equals(why)?MUTED:theme.danger,false);warning.setPadding(0,0,0,dp(12));c.addView(warning);c.addView(button("重试",()->showFeature(f,true)),new LinearLayout.LayoutParams(-1,-2));restoreViewport();}finally{if(done!=null)done.run();}});}});
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
    /**
     * 我, laid out like iOS Settings: the account card, then plain grouped rows without headers. Every switch, slider
     * and menu sits one level in (外观, 隐私与数据, 个人中心); this page only shows where things are and their state.
     */
    private void settings(){
        profile();
        gap();LinearLayout general=groupCard();
        featureRow("外观",appearanceSummary(),"drop",general,()->openPage(APPEARANCE_PAGE));
        boolean watching=!PreviewMode.isBuild()&&TiboWatch.enabled(this);int notices=NoticeCenter.count(this)+(watching?1:0);
        featureRow("通知",notices==0?"未开启":notices+" 项已开启","bell",general,()->openPage(NOTICES_PAGE));
        featureRow("小组件","桌面、锁屏与快捷设置","grid",general,this::widgetSheet);
        Set<String> hidden=hiddenFeatures();
        gap();LinearLayout management=groupCard();for(FeatureCatalog.Feature f:FeatureCatalog.all())if(f.group.equals("设置")&&!hidden.contains(f.id))featureRow(f,management);
        int hiddenCount=0;for(FeatureCatalog.Feature f:FeatureCatalog.all())if(hidden.contains(f.id))hiddenCount++;
        featureRow("已隐藏的功能",hiddenCount==0?"无":hiddenCount+" 个","eye",management,this::openHiddenPage);
        gap();LinearLayout about=groupCard();
        featureRow("隐私与数据","","shield",about,()->openPage(PRIVACY_PAGE));
        String version=versionName();LinearLayout info=featureRow("关于 God",version.isEmpty()?"Android":"Android "+version,"info",about,this::openAbout);
        View red=theme.dot(theme.unread,8);LinearLayout.LayoutParams r=(LinearLayout.LayoutParams)red.getLayoutParams();r.rightMargin=dp(10);info.addView(red,info.getChildCount()-1,r);
        aboutDot=red;aboutRow=info;aboutDescription=String.valueOf(info.getContentDescription());showDots();
        restoreViewport();
    }
    private void gap(){content.addView(new View(this),new LinearLayout.LayoutParams(-1,dp(10)));}
    private void openPage(String id){saveViewport();++generation;selectedId=id;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    /** A second-level page of 我: centred title and the round back control. */
    private void subPage(String title){heading.setText(title);leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));}
    private String appearanceSummary(){String style=statusStyle();return (theme.contrast?"不透明表面":theme.glassOn?"Liquid Glass":"Liquid Glass 已关闭")+" · 状态栏"+StatusBarStyle.label(style);}
    /**
     * 我 → 通知, after iOS Settings: the first page is the system permission and five groups. A group is only drawn;
     * opening it does not write a preference, so the first-read latch stays as it was.
     */
    private void noticesPage(){
        NoticePages.Group g=NoticePages.find(selectedId);
        if(g==null)noticesHome(); else noticesGroup(g);
    }
    private NoticePages.Snapshot noticeSnapshot(){return NoticePages.read(NoticeCenter.prefs(this).getAll(),TiboWatch.prefs(this).getAll());}
    private void noticesHome(){
        subPage("通知");boolean preview=PreviewMode.isBuild();
        NoticePages.Snapshot snap=NoticePages.browse(noticeSnapshot(),NoticePages.ROOT);
        LinearLayout perm=groupCard();boolean blocked=!preview&&notificationsBlocked();
        featureRow("系统通知",preview?"界面预览不发送通知":blocked?"未开启":"已开启","bell",perm,preview?null:()->tiboFix("notifications"));
        gap();LinearLayout groups=groupCard();
        for(NoticePages.Group g:NoticePages.GROUPS)featureRow(g.title,NoticePages.summary(g,snap),g.symbol,groups,()->openPage(g.id));
        restoreViewport();
    }
    private void noticesGroup(NoticePages.Group g){
        subPage(g.title);boolean preview=PreviewMode.isBuild();Notices.Settings s=NoticeCenter.settings(this);
        NoticePages.browse(noticeSnapshot(),g.id);
        LinearLayout box=groupCard();
        if(NoticePages.QUOTA.equals(g.id)){
            noticeSwitch(box,Notices.DAILY,"gauge",s);
            if(s.has(Notices.DAILY))featureRow("每日提醒线 · "+s.daily+"%","一个账号当天用掉主要额度（通常是每周）的比例","edit",box,()->thresholdMenu("daily","每日提醒线",Notices.DAILY_CHOICES,s.daily,"当天用掉 %d%% 时提醒"));
            noticeSwitch(box,Notices.CREDITS,"gauge",s);
            if(s.has(Notices.CREDITS))featureRow("积分提醒线 · "+s.credits+" 点","当天积分余额累计下降这么多时提醒，每个账号每天一次","edit",box,()->thresholdMenu("creditsLine","积分提醒线",Notices.CREDIT_CHOICES,s.credits,"当天用掉 %d 点时提醒"));
            noticeSwitch(box,Notices.LOW,"gauge",s);
            if(s.has(Notices.LOW))featureRow("剩余提醒线 · "+s.low+"%","任一额度剩余低于这个比例时提醒，每个周期一次","edit",box,()->thresholdMenu("low","剩余提醒线",Notices.LOW_CHOICES,s.low,"剩余低于 %d%% 时提醒"));
            noticeSwitch(box,Notices.RESET,"refresh",s);noticeSwitch(box,Notices.ACCOUNT,"user",s);noticeSwitch(box,Notices.CARD,"calendar",s);
            content.addView(theme.info(List.of("锁屏上只显示「God · 额度提醒」，解锁后才显示账号和数字。","当天消耗和 Codex 积分都按每次检查的下降累计，从当地零点算起。余额上升视为充值，不计入当天消耗。")));
        }else if(NoticePages.SITES.equals(g.id)){
            noticeSwitch(box,Notices.SITES,"pulse",s);
        }else if(NoticePages.MODE.equals(g.id)){
            boolean loud=NoticeCenter.enhanced(this);
            modeSwitch(box,"enhanced","增强提醒",loud?"横幅弹出，带声音和震动":"关闭时为普通通知，只在通知栏出现","bell",loud);
            if(loud)modeSwitch(box,"persistent","持续提醒",NoticeCenter.persistent(this)?"一直响到你点「知道了」，10 分钟后再响，最多 3 轮":"打开后通知无法划掉，直到你点「知道了」","history",NoticeCenter.persistent(this));
            content.addView(theme.info(List.of("持续提醒要先打开增强提醒。","增强和持续提醒遵守系统的勿扰模式；Tibo 重置提醒仍按闹钟方式单独响铃。")));
        }else if(NoticePages.CHALLENGE.equals(g.id)){
            challengeSwitch(box);
        }else if(NoticePages.CHECK.equals(g.id)){
            featureRow("检查频率","每 5 分钟一次","history",box,null);
            featureRow("检查时的通知","短暂显示「God 正在检查提醒」，检查完消失","bell",box,null);
            featureRow("ColorOS 耗电管理","设置 → 应用 → God → 耗电管理，允许后台行为","info",box,preview?null:()->tiboFix("battery"));
            boolean watching=!preview&&(TiboWatch.enabled(this)||NoticeCenter.any(this));
            featureRow("检查状态",preview?"界面预览不在后台检查":watching?(TiboWatch.enabled(this)?TiboWatch.status(this):NoticeCenter.status(this)):"未开启","history",box,null);
            if(!preview)for(String[] problem:TiboWatch.problems(this))if(!"notifications".equals(problem[1]))featureRow(problem[0],"点按去设置","info",box,()->tiboFix(problem[1]));
            featureRow("发送测试通知","看看提醒在通知栏和锁屏上的样子","bell",box,()->{if(preview){error("界面预览","界面预览不发送通知。");return;}if(!notificationsAllowed()){noticePending=null;requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},NOTICE_PERMISSION);return;}NoticeCenter.test(this);});
            content.addView(theme.info(List.of("God 没有云端推送，提醒来自手机每 5 分钟一次的后台检查；手机关机或断网期间的变化会在恢复后补算。")));
        }
        restoreViewport();
    }
    private void challengeSwitch(LinearLayout parent){
        boolean preview=PreviewMode.isBuild(),on=!preview&&TiboWatch.enabled(this);
        LinearLayout row=featureRow("Tibo 重置提醒",on?"已开启 · 像闹钟一样响铃":"未开启 · 与 28 天挑战页是同一个开关","bell",parent,null);
        Switch toggle=new Switch(this);toggle.setChecked(on);toggle.setEnabled(!preview);toggle.setContentDescription("Tibo 重置提醒");toggle.setMinimumHeight(dp(48));
        toggle.setOnCheckedChangeListener((b,checked)->{
            if(preview){b.setChecked(false);return;}
            if(checked&&!notificationsAllowed()){b.setChecked(false);tiboTestPending=false;requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},TIBO_PERMISSION);return;}
            tiboSwitch(checked);build();});
        row.addView(toggle);
        content.addView(theme.info(List.of("这里的开关和「功能 → 28 天挑战」里的重置提醒是同一个。","Tibo 发出重置或发卡预告时，用闹钟铃声持续响铃并全屏提醒，直到你点「知道了」。")));
    }
    private boolean notificationsBlocked(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return true;
        NotificationManager nm=getSystemService(NotificationManager.class);return nm!=null&&!nm.areNotificationsEnabled();
    }
    private static final int NOTICE_PERMISSION=44;private String noticePending;
    private void noticeSwitch(LinearLayout parent,String kind,String symbol,Notices.Settings s){
        LinearLayout row=featureRow(Notices.label(kind),Notices.detail(kind,s),symbol,parent,null);
        Switch toggle=new Switch(this);toggle.setChecked(s.has(kind));toggle.setContentDescription(Notices.label(kind));toggle.setMinimumHeight(dp(48));
        toggle.setOnCheckedChangeListener((b,on)->{
            if(on&&!PreviewMode.isBuild()&&!loggedIn()){b.setChecked(false);error("登录后开启","登录 God 后才能读取额度和站点状态。");return;}
            if(on&&!PreviewMode.isBuild()&&!notificationsAllowed()){b.setChecked(false);noticePending=kind;requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},NOTICE_PERMISSION);return;}
            enableNotice(kind,on);});
        row.addView(toggle);
    }
    private void modeSwitch(LinearLayout parent,String which,String title,String detail,String symbol,boolean value){
        LinearLayout row=featureRow(title,detail,symbol,parent,null);
        Switch toggle=new Switch(this);toggle.setChecked(value);toggle.setContentDescription(title);toggle.setMinimumHeight(dp(48));
        toggle.setOnCheckedChangeListener((b,on)->{NoticeCenter.setMode(this,which,on);build();});row.addView(toggle);
    }
    private void enableNotice(String kind,boolean on){
        boolean first=on&&!NoticeCenter.any(this)&&!TiboWatch.enabled(this);
        NoticeCenter.set(this,kind,on,!PreviewMode.isBuild());
        if(first&&!PreviewMode.isBuild()&&!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))tiboFix("battery");
        build();
    }
    private void thresholdMenu(String which,String title,int[] choices,int current,String pattern){
        String[] names=new String[choices.length];int checked=-1;for(int i=0;i<choices.length;i++){names[i]=String.format(Locale.ROOT,pattern,choices[i]);if(choices[i]==current)checked=i;}
        show(new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(names,checked,(d,w)->{NoticeCenter.threshold(this,which,choices[w]);d.dismiss();build();}).setNegativeButton("取消",null));
    }
    private void privacyPage(){
        subPage("隐私与数据");
        String[][] parts={
            {"登录","Android 14 及以上在应用内用系统通行密钥登录，应用只转交一次性的签名结果，不接触密码或 Passkey 私钥；更早的系统在浏览器登录。会话使用 Android Keystore 加密且不参加备份，退出会清除本机令牌。"},
            {"数据","应用仅连接 Robby 的 God 私有接口，没有第三方统计，不保存网页 Cookie。业务数据仅在内存中展示，进入后台即清除；登录后禁止截屏与最近任务预览。可查看原生数据，并在确认后管理账目和人员。"},
            {"头像与签名","个人头像裁剪成 512 像素的 JPEG 后保存在本机和 God 服务器，不含拍摄位置等元数据。个性签名保存在你的 God 账号里，本机留一份副本用于显示。"},
            {"设置","看板、外观、功能的隐藏和排序、通知开关、提醒线和 Codex 积分点数线会保存到 God 账号，同一账号的手机保持一致。登录、灵动岛的开孔位置、提醒的检查记录、积分起点和已提醒标记只留在这台手机；未登录时设置也只在本机。"},
            {"重置提醒","开启 Tibo 重置提醒后，应用每 5 分钟读取一次 Tibo 的公开预告，本机只记下已提醒过的帖子编号。检查期间会短暂显示一条通知，检查完即消失。"},
            {"桌面小组件","Token 额度、重置日历、运行状态和微信消息小组件只显示 God 已经读到的数字：产品名、剩余百分比、重置时间、站点在线和异常数、今天的微信消息数与近 7 天日均。本机为它们保留一份这样的副本，不含邮箱、账号、站点或会话名称和消息内容；超过两小时显示「打开 God 更新」，退出登录立即清除。这四个只能放在桌面，时区时钟和 God 小组件可以放在锁屏。"},
            {"通知","开启「我 → 通知」里的提醒后，应用每 5 分钟在后台读取 Token 额度（开启站点提醒时还有运行状态）。检查时会短暂显示「God 正在检查提醒」，检查完这条通知消失，不再常驻「已开启」。为了判断变化，本机会保存每个额度最近的剩余比例、当天累计消耗、Codex 积分起点和是否已提醒、账号状态，以及站点域名和是否在线；不保存邮箱、套餐或其他页面内容，14 天没出现的记录会自动删除，全部关闭后不再读取。这些检查记录只留在这台手机。"}};
        for(String[] part:parts){section(part[0]);LinearLayout c=card();TextView t=text(part[1],14,INK,false);t.setLineSpacing(dp(3),1);c.addView(t);}
        restoreViewport();
    }
    /**
     * 我 → 外观, after iOS 26's Liquid Glass setting: a switch for glass, and a 清透 ↔ 着色 slider while it is on.
     * Dragging repaints this page's cards and the dock as a live preview; the level is saved when the drag ends (or
     * at once for screen-reader and keyboard steps) and every page drawn afterwards uses it. High-contrast text
     * always uses solid surfaces, so both controls are off there.
     */
    private void appearancePage(){
        subPage("外观");
        gap();LinearLayout look=groupCard();boolean usable=!theme.contrast;
        LinearLayout row=featureRow("Liquid Glass",!usable?"高对比度文字开启时使用不透明表面":theme.glassOn?"半透明玻璃与背景模糊":"已关闭 · 不透明表面，没有模糊","drop",look,null);
        Switch toggle=new Switch(this);toggle.setChecked(theme.glassOn);toggle.setEnabled(usable);toggle.setContentDescription("Liquid Glass");toggle.setMinimumHeight(dp(48));
        toggle.setOnCheckedChangeListener((b,on)->{prefs().edit().putBoolean(ConsoleTheme.GLASS_ON,on).apply();PreferenceSyncHub.note(this,"glassOn");b.post(()->{theme=new ConsoleTheme(this);build();});});
        row.addView(toggle);
        LinearLayout slider=new LinearLayout(this);slider.setOrientation(LinearLayout.VERTICAL);slider.setPadding(dp(10),0,dp(10),dp(10));
        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(theme.glassLevel);bar.setEnabled(usable&&theme.glassOn);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.accent));bar.setThumbTintList(android.content.res.ColorStateList.valueOf(theme.accent));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.muted));
        bar.setContentDescription("Liquid Glass 透明度");bar.setStateDescription(glassLabel(theme.glassLevel));
        slider.addView(bar,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout ends=new LinearLayout(this);ends.setPadding(dp(6),0,dp(6),0);ends.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ends.addView(text("清透",12,MUTED,false));ends.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));ends.addView(text("默认",12,MUTED,false));ends.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));ends.addView(text("着色",12,MUTED,false));
        slider.addView(ends);if(!bar.isEnabled())slider.setAlpha(.4f);look.addView(slider);
        String style=statusStyle();
        section("状态栏");look=groupCard();
        featureRow("显示方式 · "+StatusBarStyle.label(style),StatusBarStyle.detail(style)+(StatusBarStyle.hides(style)?" · 顶端下滑临时显示":""),"eye",look,this::statusStyleMenu);
        if(StatusBarStyle.ISLAND.equals(style)){
            featureRow("灵动岛左侧 · "+StatusBarStyle.itemLabel(islandItem(true)),"摄像头左边显示的内容","info",look,()->islandItemMenu(true));
            featureRow("灵动岛右侧 · "+StatusBarStyle.itemLabel(islandItem(false)),"摄像头右边显示的内容","info",look,()->islandItemMenu(false));
        }
        section("账号");LinearLayout syncLine=groupCard();
        boolean onAccount=loggedIn()&&!PreviewMode.isBuild(),canSync=onAccount&&PreferenceSyncHub.canWrite(this);
        featureRow(PreferenceSync.statusLine(onAccount,canSync,PreferenceSyncHub.updatedAt(this),System.currentTimeMillis()),PreferenceSync.statusDetail(onAccount,canSync,onAccount&&federatedSession()),"refresh",syncLine,null);
        final boolean[] dragging={false};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int level,boolean user){if(!user)return;s.setStateDescription(glassLabel(level));
                if(dragging[0])previewGlass(new ConsoleTheme(MainActivity.this,true,level));else saveGlassLevel(s,level);}
            @Override public void onStartTrackingTouch(SeekBar s){dragging[0]=true;}
            @Override public void onStopTrackingTouch(SeekBar s){dragging[0]=false;saveGlassLevel(s,s.getProgress());}
        });
        restoreViewport();
    }
    /** 不隐藏 / 只隐藏 / 隐藏并上移 / 灵动岛, each with what it does; the choice applies at once. */
    private void statusStyleMenu(){
        List<String> styles=StatusBarStyle.ALL;String[] names=new String[styles.size()];
        for(int i=0;i<names.length;i++)names[i]=StatusBarStyle.label(styles.get(i))+" · "+StatusBarStyle.detail(styles.get(i));
        show(new AlertDialog.Builder(this).setTitle("状态栏").setSingleChoiceItems(names,styles.indexOf(statusStyle()),(d,w)->{
            prefs().edit().putString(StatusBarStyle.KEY,styles.get(w)).remove(StatusBarStyle.LEGACY).apply();PreferenceSyncHub.note(this,"statusBar");d.dismiss();applyStatusBar();build();}).setNegativeButton("取消",null));
    }
    private String islandItem(boolean left){return StatusBarStyle.item(prefs().getString(left?StatusBarStyle.LEFT_KEY:StatusBarStyle.RIGHT_KEY,null),left);}
    /** One side of the island: 时间, 日期, 星期, 电量, Tibo 倒计时 or nothing; applies at once. */
    private void islandItemMenu(boolean left){
        List<String> items=StatusBarStyle.ITEMS;String[] names=new String[items.size()];for(int i=0;i<names.length;i++)names[i]=StatusBarStyle.itemLabel(items.get(i));
        show(new AlertDialog.Builder(this).setTitle(left?"灵动岛左侧":"灵动岛右侧").setSingleChoiceItems(names,items.indexOf(islandItem(left)),(d,w)->{
            prefs().edit().putString(left?StatusBarStyle.LEFT_KEY:StatusBarStyle.RIGHT_KEY,items.get(w)).apply();PreferenceSyncHub.note(this,left?"islandLeft":"islandRight");d.dismiss();build();}).setNegativeButton("取消",null));
    }
    /** Keeps a level, snapping to the default when within 3 of it, and repaints this page with it. */
    private void saveGlassLevel(SeekBar s,int level){
        int kept=Math.abs(level-ConsoleTheme.GLASS_DEFAULT)<=3?ConsoleTheme.GLASS_DEFAULT:level;if(kept!=level){s.setProgress(kept);s.setStateDescription(glassLabel(kept));}
        prefs().edit().putInt(ConsoleTheme.GLASS_LEVEL,kept).apply();PreferenceSyncHub.note(this,"glassLevel");theme=new ConsoleTheme(this);previewGlass(theme);
    }
    private static String glassLabel(int level){return level==ConsoleTheme.GLASS_DEFAULT?"默认":(level<ConsoleTheme.GLASS_DEFAULT?"清透 ":"着色 ")+Math.abs(level-ConsoleTheme.GLASS_DEFAULT)*2+"%";}
    /** Repaints this page's cards and the dock for {@code t} without rebuilding, so the dragged slider keeps its touch. */
    private void previewGlass(ConsoleTheme t){
        for(int i=0;i<content.getChildCount();i++){View v=content.getChildAt(i);android.graphics.drawable.Drawable b=v.getBackground();
            if(b instanceof ConsoleTheme.Glass)v.setBackground(t.card());
            else if(b instanceof android.graphics.drawable.RippleDrawable&&v.isClickable())v.setBackground(t.ripple(t.card(),24));}
        if(nav!=null)nav.setBackground(t.dock());
    }
    /** Account card at the top of 我, in WeChat's profile position; opens the same sign-in / sign-out choices as before. */
    private void profile(){
        boolean logged=loggedIn();LinearLayout c=card();c.setOrientation(LinearLayout.HORIZONTAL);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(dp(16),dp(18),dp(14),dp(18));
        c.setBackground(theme.ripple(theme.card(),24));c.setFocusable(true);c.setClickable(true);c.setContentDescription(logged?"个人中心":"登录 God");c.setOnClickListener(v->{if(loggedIn())openAccount();else account();});theme.press(c);
        // The avatar is its own tap target (更换头像); the rest of the card keeps opening the account choices.
        android.graphics.Bitmap picture=Avatar.get(this);FrameLayout avatar=new FrameLayout(this);
        if(picture==null){avatar.setBackground(theme.glass(-1));avatar.addView(theme.icon("user",INK,28),new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));}
        else avatar.addView(Avatar.view(this,picture),new FrameLayout.LayoutParams(-1,-1));
        FrameLayout edit=new FrameLayout(this);edit.setBackground(theme.glass(-1));edit.addView(theme.icon("edit",INK,11),new FrameLayout.LayoutParams(dp(11),dp(11),Gravity.CENTER));
        avatar.addView(edit,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.BOTTOM|Gravity.END));
        avatar.setFocusable(true);avatar.setClickable(true);avatar.setContentDescription(picture==null?"设置头像":"更换头像");avatar.setOnClickListener(v->avatarMenu());theme.press(avatar);
        c.addView(avatar,new LinearLayout.LayoutParams(dp(60),dp(60)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(16),0,dp(8),0);profileName=text(!logged?"尚未登录":accountStatus!=null&&accountStatus.displayName!=null?accountStatus.displayName:"已登录",20,INK,true);profileName.setSingleLine(true);profileName.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(profileName);
        String signature=signature();
        TextView detail=profileDetail=text(!logged?"使用 Passkey、已有设备或 Google 登录":federatedSession()?"Google 登录 · 只读，修改需用 Passkey 登录":avatarWaiting()?"头像只在本机 · 重新登录后同步":signature.isEmpty()?"添加个性签名":signature,13,MUTED,false);
        if(logged&&signature.isEmpty()&&!avatarWaiting())detail.setAlpha(.6f);detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);detail.setPadding(0,dp(4),0,0);labels.addView(detail);
        c.addView(labels,new LinearLayout.LayoutParams(0,-2,1));ImageView chevron=theme.icon("chevron",MUTED,14);chevron.setAlpha(.35f);c.addView(chevron);
        // The name comes from the account; read it quietly once per foreground visit and fill it in place.
        if(logged&&accountStatus==null&&!accountNameRead){accountNameRead=true;loadAccountStatus(null);}
    }
    private static final int PICK_AVATAR=42;
    /** 我 tab glyph: the avatar when one is set (ringed when selected), otherwise the person symbol. */
    private View tabGlyph(String label,boolean selected){
        android.graphics.Bitmap picture=label.equals("设置")?Avatar.get(this):null;
        if(picture==null)return theme.tabIcon(ConsoleTheme.symbol(label),selected,24);
        FrameLayout f=new FrameLayout(this);int inner=selected?20:24;f.addView(Avatar.view(this,picture),new FrameLayout.LayoutParams(dp(inner),dp(inner),Gravity.CENTER));
        if(selected){GradientDrawable ring=new GradientDrawable();ring.setShape(GradientDrawable.OVAL);ring.setStroke(dp(1.5f),INK);f.setBackground(ring);}
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(24),dp(24)));return f;
    }
    private void avatarMenu(){
        boolean has=Avatar.get(this)!=null,waiting=avatarWaiting();
        List<String> items=new ArrayList<>(List.of("从相册选择"));if(has)items.add("移除头像");if(waiting)items.add("重新登录以同步头像");
        show(new AlertDialog.Builder(this).setTitle("个人头像").setItems(items.toArray(new String[0]),(d,w)->{
            String item=items.get(w);if(item.equals("从相册选择"))pickAvatar();else if(item.equals("移除头像")){Avatar.clear(this);build();syncAvatar(true);}else avatarSignIn();
        }).setNegativeButton("取消",null));
    }
    /** 添加到桌面 offers every widget: God, and the five that show figures God already read. */
    private void pinWidget(android.appwidget.AppWidgetManager widgets){
        String[] names={"God","Token 额度","重置日历","时区时钟","运行状态","微信消息"};
        Class<?>[] kinds={GodWidget.class,TokenWidget.class,CalendarWidget.class,ClockWidget.class,StatusWidget.class,WechatWidget.class};
        show(new AlertDialog.Builder(this).setTitle("添加到桌面").setItems(names,(d,w)->widgets.requestPinAppWidget(new ComponentName(this,kinds[w]),null,null)).setNegativeButton("取消",null));
    }
    /** The widget as it looks now, plus the ways to place it: home screen, Pixel lock screen and 快捷设置. */
    private void widgetSheet(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        FrameLayout frame=new FrameLayout(this);try{frame.addView(GodWidget.views(this).apply(this,frame));}catch(RuntimeException ignored){}
        box.addView(frame,new LinearLayout.LayoutParams(-1,dp(68)));
        TextView how=text("桌面：点「添加到桌面」选一个，或长按桌面空白处 → 小组件 → God。还有 Token 额度、重置日历、时区时钟、运行状态和微信消息五个，只显示数字，不显示邮箱和会话名。\n\nPixel 锁屏（Android 16 QPR2 起）：设置 → 显示与触控 → 锁定屏幕 → 锁屏上的微件，打开后在锁屏上编辑并添加 God。锁屏上点按会先要求解锁。\n\n快捷设置：点「添加到快捷设置」，或下拉快捷设置 → 编辑 → 把 God 拖进来。\n\n小组件只显示 God 图标和 Tibo 的公开预告，不显示账号数据。",13,MUTED,false);
        how.setPadding(0,dp(14),0,0);box.addView(how);
        android.appwidget.AppWidgetManager widgets=android.appwidget.AppWidgetManager.getInstance(this);
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("God 小组件").setView(box).setNegativeButton("关闭",null);
        if(widgets!=null&&widgets.isRequestPinAppWidgetSupported())b.setPositiveButton("添加到桌面",(d,w)->pinWidget(widgets));
        if(Build.VERSION.SDK_INT>=33)b.setNeutralButton("添加到快捷设置",(d,w)->addTile());
        show(b);
    }
    private void addTile(){
        if(Build.VERSION.SDK_INT<33)return;
        try{getSystemService(android.app.StatusBarManager.class).requestAddTileService(new ComponentName(this,GodTileService.class),"God",android.graphics.drawable.Icon.createWithResource(this,R.drawable.tab_home_solid),getMainExecutor(),result->{});}
        catch(RuntimeException e){error("无法添加","请下拉快捷设置 → 编辑，把 God 拖进来。");}
    }
    private long avatarCheckedAt;
    /** A change on this phone waits for a session that may write the avatar (signed in before 0.17.0). */
    private boolean avatarWaiting(){return !PreviewMode.isBuild()&&loggedIn()&&Avatar.pending(this)!=null&&!auth.hasScope(AuthSession.PROFILE_WRITE);}
    /** Sends a change or fetches the server's avatar in the background; only the person's own change reports failures. */
    private void syncAvatar(boolean user){
        if(PreviewMode.isBuild()||!loggedIn())return;avatarCheckedAt=SystemClock.elapsedRealtime();
        boolean canWrite=auth.hasScope(AuthSession.PROFILE_WRITE);String pending=Avatar.pending(this);
        android.util.Log.i("GodAvatar","sync user="+user+" canWrite="+canWrite+" pending="+pending);
        if(user&&pending!=null&&!canWrite){avatarSignIn();return;}
        updater.execute(()->{try{boolean changed=Avatar.sync(this,api,canWrite);android.util.Log.i("GodAvatar","sync ok changed="+changed+" pending="+Avatar.pending(this));runOnUiThread(()->{if(!isDestroyed()&&changed&&"设置".equals(group)&&selectedId==null)build();});}
            catch(Exception e){android.util.Log.w("GodAvatar","sync failed "+(e instanceof MobileApi.ApiError?"http="+((MobileApi.ApiError)e).status+" "+((MobileApi.ApiError)e).code:e.getClass().getSimpleName()));
                runOnUiThread(()->{if(!isDestroyed()&&user)error("头像暂未同步",e instanceof MobileApi.ApiError?e.getMessage():"网络暂不可用，下次打开 App 时会再试。");});}});
    }
    private void avatarSignIn(){
        show(new AlertDialog.Builder(this).setTitle("同步头像需要重新登录").setMessage("头像已保存在这台手机上。God 需要你重新登录一次，授权把头像保存到服务器；登录后会自动上传。").setNegativeButton("以后再说",null)
            .setPositiveButton("重新登录",(d,w)->{signOut();account();}));
    }
    private void openAccount(){saveViewport();++generation;selectedId=ACCOUNT_PAGE;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    /** Reads account/status off the UI thread; fills the 我 card's name and, when given, the page's account part. */
    private void loadAccountStatus(Runnable done){
        final int gen=generation;
        boardReads.execute(()->{AccountCenter.Status s=null;Exception failure=null;
            try{s=AccountCenter.status(read(AccountCenter.STATUS));}catch(Exception e){failure=e;}
            final AccountCenter.Status got=s;final Exception error=failure;
            runOnUiThread(()->{if(isDestroyed())return;if(got!=null){accountStatus=got;adoptSignature(got);}
                if(got!=null&&got.displayName!=null&&profileName!=null&&profileName.isAttachedToWindow())profileName.setText(got.displayName);
                if(gen!=generation||done==null)return;accountError=error;done.run();});});
    }
    private Exception accountError,devicesError;
    private void loadDevices(Runnable done){
        final int gen=generation;
        boardReads.execute(()->{AccountCenter.Devices d=null;Exception failure=null;
            try{d=AccountCenter.devices(read(AccountCenter.DEVICES));}catch(Exception e){failure=e;}
            final AccountCenter.Devices got=d;final Exception error=failure;
            runOnUiThread(()->{if(isDestroyed()||gen!=generation)return;if(got!=null)accountDevices=got;devicesError=error;done.run();});});
    }
    /**
     * 我 → 个人中心, the same account as god.robby.wang/account/: who is signed in, 登录与安全 (two-step verification
     * and every Passkey, with 添加 Passkey on the identity provider's own page), 第三方账号 (Apple and 微信; removing a
     * binding here, adding one on the web), 联系方式 (contact email; phone not offered), 在线设备 (each God app
     * signed in, 本机 first, and 下线其他设备), then 退出登录. Each part loads on its own and pull-to-refresh reads both.
     */
    private void accountPage(){
        heading.setText("个人中心");
        leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(0,dp(8),0,dp(18));
        android.graphics.Bitmap picture=Avatar.get(this);FrameLayout avatar=new FrameLayout(this);
        if(picture==null){avatar.setBackground(theme.glass(-1));avatar.addView(theme.icon("user",INK,36),new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER));}
        else avatar.addView(Avatar.view(this,picture),new FrameLayout.LayoutParams(-1,-1));
        avatar.setFocusable(true);avatar.setClickable(true);avatar.setContentDescription(picture==null?"设置头像":"更换头像");avatar.setOnClickListener(v->avatarMenu());theme.press(avatar);
        hero.addView(avatar,new LinearLayout.LayoutParams(dp(76),dp(76)));
        TextView name=text(accountStatus!=null&&accountStatus.displayName!=null?accountStatus.displayName:"God 账号",22,INK,true);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(12),0,dp(4));hero.addView(name);
        String signature=signature();TextView sub=text(signature.isEmpty()?"添加个性签名":signature,14,MUTED,false);sub.setGravity(Gravity.CENTER);sub.setPadding(dp(16),0,dp(16),0);if(signature.isEmpty())sub.setAlpha(.6f);
        sub.setFocusable(true);sub.setClickable(true);sub.setOnClickListener(v->editSignature());hero.addView(sub);content.addView(hero);
        LinearLayout profile=groupCard();
        featureRow("头像",Avatar.get(this)==null?"未设置":"已设置 · 同步到 God 服务器","user",profile,this::avatarMenu);
        featureRow("个性签名",signature.isEmpty()?"未设置":signature,"edit",profile,this::editSignature);
        final LinearLayout account=new LinearLayout(this);account.setOrientation(LinearLayout.VERTICAL);content.addView(account);
        final LinearLayout devices=new LinearLayout(this);devices.setOrientation(LinearLayout.VERTICAL);content.addView(devices);
        Runnable fillAccount=()->{account.removeAllViews();accountParts(account,name);},fillDevices=()->{devices.removeAllViews();deviceParts(devices);};
        if(accountStatus==null){accountError=null;placeholder(account,"正在读取账号…");loadAccountStatus(fillAccount);}else fillAccount.run();
        if(accountDevices==null){devicesError=null;placeholder(devices,"正在读取在线设备…");loadDevices(fillDevices);}else fillDevices.run();
        View gap=new View(this);content.addView(gap,new LinearLayout.LayoutParams(-1,dp(12)));LinearLayout leave=groupCard();LinearLayout out=featureRow("退出登录","清除本机令牌，需要重新用 Passkey 登录","clear",leave,()->show(new AlertDialog.Builder(this).setTitle("退出登录？").setMessage("会清除这台手机上的 God 登录。其他设备不受影响。").setNegativeButton("取消",null).setPositiveButton("退出登录",(d,w)->signOut())));
        ((TextView)((LinearLayout)out.getChildAt(1)).getChildAt(0)).setTextColor(theme.danger);
        final PullScrollView page=scroll;page.setRefresh(pullMark,pullGlyph,"刷新个人中心",()->{final int[] left={2};Runnable one=()->{if(--left[0]==0)page.finishRefresh();};
            loadAccountStatus(()->{fillAccount.run();one.run();});loadDevices(()->{fillDevices.run();one.run();});});
        restoreViewport();
    }
    /** The signature as last read from or saved to the account; this phone keeps a copy only to draw 我 at once. */
    private boolean federatedSession(){return !PreviewMode.isBuild()&&auth!=null&&auth.isFederated();}
    private String signature(){return Signature.clean(prefs().getString(Signature.KEY,""));}
    private void cacheSignature(String value){if(value==null||value.isEmpty())prefs().edit().remove(Signature.KEY).apply();else prefs().edit().putString(Signature.KEY,value).apply();}
    /**
     * The account's answer wins over the copy here. A signature set on this phone before it was synced (0.25.0 kept it
     * only locally) is sent once when the account has none on record and the session may save it.
     */
    private void adoptSignature(AccountCenter.Status s){
        if(s==null||!s.profileKnown)return;
        String local=signature();
        if(s.signature==null&&!local.isEmpty()&&s.canSaveProfile&&!PreviewMode.isBuild()&&!signatureUploading){signatureUploading=true;saveSignature(local,false);return;}
        if(s.signature!=null&&!s.signature.equals(local)){cacheSignature(s.signature);if(ACCOUNT_PAGE.equals(selectedId)){build();return;}if(profileDetail!=null&&profileDetail.isAttachedToWindow()&&!avatarWaiting()&&!federatedSession()){profileDetail.setText(s.signature.isEmpty()?"添加个性签名":s.signature);profileDetail.setAlpha(s.signature.isEmpty()?.6f:1f);}}
    }
    private boolean signatureUploading;
    private void profileSignIn(){
        show(new AlertDialog.Builder(this).setTitle("保存签名需要重新登录").setMessage("这次登录还不能修改个人资料。重新登录一次，授权 God 把头像和个性签名保存到你的账号。").setNegativeButton("以后再说",null)
            .setPositiveButton("重新登录",(d,w)->{signOut();account();}));
    }
    /** One line under the name on 我; kept on this phone. An empty answer removes it. */
    private void editSignature(){
        EditText field=new EditText(this);field.setText(signature());field.setSelection(field.getText().length());field.setHint("写一句话介绍自己");field.setSingleLine(true);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(Signature.MAX)});
        FrameLayout box=new FrameLayout(this);box.setPadding(dp(20),dp(8),dp(20),0);box.addView(field);
        show(new AlertDialog.Builder(this).setTitle("个性签名").setMessage("显示在「我」的账号卡片上，最多 "+Signature.MAX+" 个字，保存在你的 God 账号里，换设备登录也在。").setView(box).setNegativeButton("取消",null)
            .setPositiveButton("保存",(d,w)->saveSignature(Signature.clean(field.getText().toString()),true)));
    }
    /** Saves to the account, then updates the copy here; the preview only keeps it locally. */
    private void saveSignature(String value,boolean user){
        if(PreviewMode.isBuild()){cacheSignature(value);build();return;}
        if(!auth.hasScope(AuthSession.PROFILE_WRITE)){if(user)profileSignIn();return;}
        JSONObject body;try{body=new JSONObject().put("signature",value);}catch(JSONException e){return;}
        updater.execute(()->{try{JSONObject r=api.post(AccountCenter.SIGNATURE,body);JSONObject p=r.optJSONObject("profile");String saved=p==null||p.isNull("signature")?value:Signature.clean(p.optString("signature",""));
                runOnUiThread(()->{if(isDestroyed())return;cacheSignature(saved);if(accountStatus!=null){accountStatus.signature=saved;accountStatus.profileKnown=true;}if(user)toast(saved.isEmpty()?"已清除个性签名":"个性签名已保存");build();});}
            catch(Exception e){runOnUiThread(()->{if(isDestroyed()||!user)return;
                if(e instanceof MobileApi.ApiError&&AccountCenter.needsSignIn(((MobileApi.ApiError)e).status,((MobileApi.ApiError)e).code)){profileSignIn();return;}
                error("签名未保存",accountMessage(e,"连接未完成，请检查网络后重试"));});}});
    }
    private void placeholder(LinearLayout parent,String message){LinearLayout c=theme.card(parent);TextView t=text(message,14,MUTED,false);t.setPadding(dp(2),dp(6),dp(2),dp(6));c.addView(t);}
    private void accountSection(LinearLayout parent,String title){TextView t=text(title,15,INK,true);t.setAccessibilityHeading(true);t.setPadding(dp(6),dp(12),0,dp(8));parent.addView(t);}
    private void accountParts(LinearLayout parent,TextView name){
        AccountCenter.Status s=accountStatus;
        if(s==null){placeholder(parent,accountMessage(accountError,"账号暂时读不到，下拉可重试。"));return;}
        if(s.displayName!=null)name.setText(s.displayName);
        boolean identity=!s.unavailable.contains("identity");
        accountSection(parent,"登录与安全");LinearLayout security=groupCard(parent);
        if(!identity||s.totp==null)featureRow("登录安全","暂时读不到，下拉可重试","shield",security,null);
        else{
            featureRow("两步验证",s.totp?"已绑定验证器 App":"未绑定验证器 App","shield",security,()->openAccountWeb(s.settingsUrl));
            int count=s.passkeys==null?(Boolean.TRUE.equals(s.passkeyBound)?-1:0):s.passkeys.size();
            featureRow("Passkey",count<0?"已添加":count==0?"尚未添加":count+" 个 · 可用于登录 God","key",security,null);
            if(s.passkeys!=null)for(AccountCenter.Passkey k:s.passkeys)featureRow(k.name,k.detail,"key",security,null);
            featureRow("添加 Passkey","在登录安全页创建，完成后返回这里自动刷新","plus",security,()->openAccountWeb(s.settingsUrl));
        }
        accountSection(parent,"第三方账号");LinearLayout social=groupCard(parent);
        if(!identity||s.social==null||s.social.isEmpty())featureRow("第三方账号","暂时读不到，下拉可重试","people",social,null);
        else for(AccountCenter.Social x:s.social){
            // Each provider: its bound accounts one per row (点按解除), then 绑定 / 再绑定一个 on the web until the limit.
            String symbol="apple".equals(x.provider)?"user":"google".equals(x.provider)?"globe":"chat";
            if(!x.available&&x.links.isEmpty()){featureRow(x.label,"暂未开放",symbol,social,null);continue;}
            for(int i=0;i<x.links.size();i++){AccountCenter.Link l=x.links.get(i);final int n=i+1;
                featureRow(x.label+" 账号 "+n,"已绑定"+(l.boundAt==null?"":" · "+AccountCenter.day(l.boundAt))+(l.id==null?"":" · 点按解除"),symbol,social,l.id==null?null:()->unbind(x,l,n));}
            if(x.available&&x.links.size()<x.limit)featureRow(x.links.isEmpty()?"绑定 "+x.label+" 账号":"再绑定一个 "+x.label+" 账号",(x.links.isEmpty()?"":"最多 "+x.limit+" 个 · ")+"在网页个人中心完成",symbol,social,()->openAccountWeb(AccountCenter.WEB));
        }
        accountSection(parent,"联系方式");LinearLayout contact=groupCard(parent);
        if(s.unavailable.contains("email")||s.emailBound==null)featureRow("邮箱","暂时读不到，下拉可重试","mail",contact,null);
        else featureRow("邮箱",s.emailBound?s.emailMasked+" · 已验证 · 点按更换":"未绑定 · 点按绑定联系邮箱","mail",contact,s.emailCanSend||!s.emailBound?this::bindEmail:null);
        featureRow("手机号",Boolean.TRUE.equals(s.phoneBound)?"已绑定":"暂不支持绑定","phone",contact,null);
        if(!s.canWrite)parent.addView(theme.info(List.of("这次登录还不能修改账号：绑定邮箱、解除第三方账号和下线其他设备需要重新登录一次授权。")));
    }
    private void deviceParts(LinearLayout parent){
        AccountCenter.Devices d=accountDevices;
        accountSection(parent,"在线设备");LinearLayout list=groupCard(parent);
        if(d==null){featureRow("在线设备",accountMessage(devicesError,"暂时读不到，下拉可重试"),"phone",list,null);return;}
        java.time.Instant now=java.time.Instant.now();int shown=0,folded=0;
        for(AccountCenter.Device x:d.list){
            if(!x.current&&shown>=AccountCenter.FOLD&&!devicesUnfolded){folded++;continue;}
            LinearLayout row=featureRow(AccountCenter.deviceTitle(x),AccountCenter.deviceDetail(x,now),"phone",list,null);shown++;
            if(x.current){row.setContentDescription(row.getContentDescription()+"，本机");TextView mark=text("本机",12,ACCENT,true);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);mark.setPadding(dp(10),dp(4),dp(10),dp(4));mark.setBackground(round((ACCENT&0x00ffffff)|0x22000000,99));row.addView(mark);}
        }
        if(d.list.isEmpty())featureRow("没有已登录的 App","","phone",list,null);
        if(folded>0){final int n=folded;featureRow("还有 "+n+" 个较早的登录","点按展开","history",list,()->{devicesUnfolded=true;parent.removeAllViews();deviceParts(parent);});}
        int others=AccountCenter.others(d);
        if(others>0){LinearLayout row=featureRow("下线其他设备",others+" 个其他登录 · 保留本机","clear",list,()->revokeOthers(others));((TextView)((LinearLayout)row.getChildAt(1)).getChildAt(0)).setTextColor(theme.danger);}
        if(federatedSession())parent.addView(theme.info(List.of("用 Google 登录时，这台手机不在列表里：列表只包含用 Passkey 登录的 God。")));
        else if(!d.currentFound&&!d.list.isEmpty())parent.addView(theme.info(List.of("暂时没认出本机的登录（刚刷新过会话时可能如此），下拉刷新后会标出本机。")));
    }
    private String accountMessage(Exception e,String fallback){
        if(e instanceof MobileApi.ApiError){MobileApi.ApiError a=(MobileApi.ApiError)e;String m=AccountCenter.explain(a.status,a.code);return m!=null?m:safeError(e);}
        return e==null?fallback:safeError(e);
    }
    /** Adding a Passkey or binding Apple / 微信 happens on the web; the page opens over the app and 个人中心 refreshes on return. */
    private void openAccountWeb(String url){
        if(PreviewMode.isBuild()){error("界面预览","此预览不连接真实账号。");return;}
        Intent tab=new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        Bundle extras=new Bundle();extras.putBinder("android.support.customtabs.extra.SESSION",null);tab.putExtras(extras);
        try{accountBrowserPending=true;startActivity(tab);}catch(ActivityNotFoundException e){accountBrowserPending=false;error("无法打开","手机需要可打开网页的浏览器。");}
    }
    private void accountSignIn(){
        show(new AlertDialog.Builder(this).setTitle("需要重新登录").setMessage("这次登录还不能修改账号。重新登录一次，授权 God 绑定邮箱、解除第三方账号和下线其他设备。").setNegativeButton("以后再说",null)
            .setPositiveButton("重新登录",(d,w)->{signOut();account();}));
    }
    /** One 个人中心 write: refused at once without god.mobile.account.write, never retried, reported in plain words. */
    private void accountWrite(String route,JSONObject body,java.util.function.Consumer<JSONObject> done){
        if(PreviewMode.isBuild()){error("界面预览","界面预览不执行写入。");return;}
        if(!auth.hasScope(AuthSession.ACCOUNT_WRITE)){accountSignIn();return;}
        updater.execute(()->{try{JSONObject r=api.post(route,body);runOnUiThread(()->{if(!isDestroyed())done.accept(r);});}
            catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;
                if(e instanceof MobileApi.ApiError&&AccountCenter.needsSignIn(((MobileApi.ApiError)e).status,((MobileApi.ApiError)e).code)){accountSignIn();return;}
                error("未完成",accountMessage(e,"连接未完成，请检查网络后重试"));});}});
    }
    private void reloadAccount(){accountStatus=null;accountDevices=null;if(ACCOUNT_PAGE.equals(selectedId))build();}
    private void unbind(AccountCenter.Social x,AccountCenter.Link link,int n){
        show(new AlertDialog.Builder(this).setTitle("解除"+x.label+"账号 "+n+"？").setMessage("解除后不能再用这个"+x.label+"账号登录 God，用它登录的 App 也会在 10 分钟内退出。可以随时在网页个人中心重新绑定。").setNegativeButton("取消",null)
            .setPositiveButton("解除绑定",(d,w)->{try{accountWrite(AccountCenter.UNBIND,new JSONObject().put("provider",x.provider).put("id",link.id).put("confirm","unbind:"+x.provider+":"+link.id),r->{toast("已解除"+x.label+"账号 "+n);reloadAccount();});}catch(org.json.JSONException ignored){}}));
    }
    private void revokeOthers(int count){
        show(new AlertDialog.Builder(this).setTitle("下线其他 "+count+" 个登录？").setMessage("除这台手机外，其他设备上的 God 会在 10 分钟内退出登录，需要重新用 Passkey 登录。").setNegativeButton("取消",null)
            .setPositiveButton("下线",(d,w)->{try{accountWrite(AccountCenter.REVOKE_OTHERS,new JSONObject().put("confirm","revoke:others"),r->{toast("已下线 "+r.optInt("signedOut",0)+" 个登录");devicesUnfolded=false;reloadAccount();});}catch(org.json.JSONException ignored){}}));
    }
    /** Contact email: an address, a six-digit code sent to it, then the verified, masked address. Recovery email is separate. */
    private void bindEmail(){
        if(!PreviewMode.isBuild()&&!auth.hasScope(AuthSession.ACCOUNT_WRITE)){accountSignIn();return;}
        EditText field=new EditText(this);field.setHint("name@example.com");field.setSingleLine(true);field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        FrameLayout box=new FrameLayout(this);box.setPadding(dp(20),dp(8),dp(20),0);box.addView(field);
        show(new AlertDialog.Builder(this).setTitle("绑定联系邮箱").setMessage("用于接收通知，不会改变登录方式。").setView(box).setNegativeButton("取消",null)
            .setPositiveButton("发送验证码",(d,w)->{String email=field.getText().toString().trim();if(!AccountCenter.emailLooksValid(email)){error("邮箱格式不正确","请检查后重新输入。");return;}
                try{accountWrite(AccountCenter.EMAIL_SEND,new JSONObject().put("email",email),r->{String id=r.optString("challengeId","");if(!id.matches("[A-Za-z0-9_-]{32}")){error("未完成","服务器未返回预期结果");return;}enterEmailCode(id,r.optString("masked",""));});}catch(org.json.JSONException ignored){}}));
    }
    private void enterEmailCode(String challenge,String masked){
        EditText field=new EditText(this);field.setHint("6 位验证码");field.setSingleLine(true);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        FrameLayout box=new FrameLayout(this);box.setPadding(dp(20),dp(8),dp(20),0);box.addView(field);
        show(new AlertDialog.Builder(this).setTitle("输入验证码").setMessage("验证码已发送到 "+(masked.matches("[^\\s@*]\\*\\*\\*@[A-Za-z0-9.-]{3,253}")?masked:"你的邮箱")+"，10 分钟内有效。").setView(box).setNegativeButton("取消",null)
            .setPositiveButton("验证",(d,w)->{String code=field.getText().toString().trim();if(!code.matches("\\d{6}")){error("验证码不正确","请输入 6 位数字。");return;}
                try{accountWrite(AccountCenter.EMAIL_VERIFY,new JSONObject().put("challengeId",challenge).put("code",code),r->{toast("邮箱已绑定");reloadAccount();});}catch(org.json.JSONException ignored){}}));
    }
    private void signOut(){++generation;snapshots.clear();GodWidgets.clear(this);accountStatus=null;accountDevices=null;devicesUnfolded=false;navigation.clear();viewportReady=false;selectedId=null;project=null;content.removeAllViews();ledger.close();coordinator.logout();group="总览";build();}
    /** System photo picker where available (no storage permission); otherwise any app that offers an image. */
    private void pickAvatar(){
        // Launch directly: package visibility hides the picker from resolveActivity() on Android 11+.
        try{startActivityForResult(new Intent("android.provider.action.PICK_IMAGES").setType("image/*"),PICK_AVATAR);return;}catch(ActivityNotFoundException ignored){}
        try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),PICK_AVATAR);}
        catch(ActivityNotFoundException e){error("无法选择图片","手机上没有可以选择图片的应用。");}
    }
    private void openAbout(){++generation;selectedId=ABOUT;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=1;build();}
    private android.content.SharedPreferences prefs(){return getSharedPreferences("god-ui",MODE_PRIVATE);}
    private View meDot,aboutDot;private View meTab,aboutRow;private String meTabDescription="",aboutDescription="";
    /** A red dot only when a newer release than the installed one has been found; reading notes never needs one. */
    private boolean updateDot(){return update!=null;}
    private void showDots(){boolean on=updateDot();
        if(meDot!=null){meDot.setVisibility(on?View.VISIBLE:View.GONE);if(meTab!=null)meTab.setContentDescription(meTabDescription+(on?"，有新版本可更新":""));}
        if(aboutDot!=null){aboutDot.setVisibility(on?View.VISIBLE:View.GONE);if(aboutRow!=null)aboutRow.setContentDescription(aboutDescription+(on?"，有新版本可更新":""));}}
    /** The selected tab tapped again (a double tap included): back to that tab's first page, or to its top when already there. */
    private void tabHome(){
        if(coordinator==null||coordinator.deviceAuthorization()!=null)return;
        if(selectedId!=null){saveViewport();++generation;selectedId=null;project=null;ledgerCursor=null;ledgerPages.clear();enterFrom=-1;build();}
        else if(scroll!=null)scroll.smoothScrollTo(0,0);
    }
    /**
     * 关于 God, modelled on WeChat's 关于微信: brand and version first, one way to get a newer build,
     * then 更新记录 newest first. The installed version is expanded and marked; older ones show their one-line
     * theme until tapped, so the page scans like a list and reads like notes.
     */
    private void about(){
        heading.setText("关于");
        leading.removeAllViews();leading.addView(theme.circle("back","返回",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        String version=versionName();
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(0,dp(10),0,dp(22));
        FrameLayout mark=new FrameLayout(this);mark.setBackground(theme.glass(-1));mark.addView(theme.tabIcon("grid",true,38),new FrameLayout.LayoutParams(dp(38),dp(38),Gravity.CENTER));hero.addView(mark,new LinearLayout.LayoutParams(dp(76),dp(76)));
        TextView name=text("God",22,INK,true);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(14),0,dp(4));hero.addView(name);
        TextView build=text(version.isEmpty()?"Android":"Android "+version,14,MUTED,false);build.setGravity(Gravity.CENTER);hero.addView(build);content.addView(hero);
        // Update lives right under the version, like 关于微信. Every open checks again (at most once a minute), so a
        // release published after one was found still shows up; pulling down checks at once.
        updateBox=new LinearLayout(this);updateBox.setOrientation(LinearLayout.VERTICAL);content.addView(updateBox);renderUpdate();
        if(AppUpdate.recheck(updateState,updateChecking,updateCheckedAt,SystemClock.elapsedRealtime()))checkUpdate();
        final PullScrollView page=scroll;page.setRefresh(pullMark,pullGlyph,"检查新版本",()->checkUpdate(page::finishRefresh));
        // The installed version reads as its own card; every other version is one row in a single grouped list,
        // like WeChat's 功能介绍, so ten releases scan as a list instead of a stack of cards.
        if(expandedNotes.isEmpty()&&!version.isEmpty())expandedNotes.add(version);
        java.time.LocalDate today=java.time.LocalDate.now();
        ReleaseNotes.Entry installed=ReleaseNotes.find(version);
        if(installed!=null){section("本次更新");noteRow(installed,true,today,card());}
        section(installed==null?"更新记录":"历史版本");LinearLayout history=card();history.setPadding(dp(6),dp(6),dp(6),dp(6));
        if(theme.effects)history.setLayoutTransition(new android.animation.LayoutTransition());
        for(ReleaseNotes.Entry e:ReleaseNotes.ALL)if(e!=installed)noteRow(e,false,today,history);
        content.addView(theme.info(List.of("安装包校验值和验证说明见 GitHub Releases。")));
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
    private boolean updateBusy(){String s=updateState;return s.equals("checking")||s.equals("downloading")||s.equals("installing");}
    private void checkUpdate(){checkUpdate(null);}
    /**
     * Asks GitHub for the newest release. With a version already shown the check runs quietly behind its card and
     * replaces it only with a newer answer; a failed re-check keeps it. A download or install in progress is never
     * interrupted. {@code done} runs on the main thread when this call is over.
     */
    private void checkUpdate(Runnable done){
        String s=updateState;
        if(updateChecking||s.equals("downloading")||s.equals("installing")){if(done!=null)done.run();return;}
        updateChecking=true;if(update==null){updateState="checking";updateNote=null;renderUpdate();}
        String current=versionName();
        updater.execute(()->{
            AppUpdate.Release result=null;Exception failure=null;
            try{String real=PreviewMode.realUpdateAs();
                if(PreviewMode.isBuild()&&real==null){Thread.sleep(700);result=PreviewMode.update();}else result=AppUpdate.check(real!=null?real:current);}
            catch(Exception e){failure=e;}
            final AppUpdate.Release found=result;final Exception error=failure;
            runOnUiThread(()->{
                try{updateChecking=false;updateCheckedAt=SystemClock.elapsedRealtime();
                    String now=updateState;if(now.equals("downloading")||now.equals("installing"))return;
                    if(error==null){update=found;updateState=found==null?"latest":"available";updateNote=null;}
                    else if(update==null){updateState="failed";updateNote=updateError(error);}
                    renderUpdate();showDots();}
                finally{if(done!=null)done.run();}
            });
        });
    }
    /** 立即更新: download, verify, then the system installer asks the person to confirm. */
    private void startUpdate(){
        AppUpdate.Release r=update;if(r==null||updateBusy())return;
        if(!getPackageManager().canRequestPackageInstalls()){
            show(new AlertDialog.Builder(this).setTitle("允许安装更新").setMessage("Android 需要你允许 God安装应用。在设置里打开「允许来自此来源的应用」，返回后会自动继续更新。")
                .setNegativeButton("取消",null).setPositiveButton("去设置",(d,w)->{awaitingInstallPermission=true;
                    try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
                    catch(ActivityNotFoundException e){awaitingInstallPermission=false;error("无法打开设置","请在系统设置里允许本应用安装应用。");}}));
            return;
        }
        updateState="downloading";updatePercent=0;updateNote=null;updateCancel.set(false);renderUpdate();
        updater.execute(()->{
            try{
                java.io.File apk=PreviewMode.isBuild()&&PreviewMode.realUpdateAs()==null?previewPackage():AppUpdate.download(this,r,(done,total)->{int p=total>0?(int)Math.min(100,done*100/total):-1;if(p!=updatePercent){updatePercent=p;runOnUiThread(this::renderUpdate);}},updateCancel);
                runOnUiThread(()->{updateState="installing";updateNote="正在交给系统安装…";renderUpdate();});
                AppUpdate.install(this,apk);
            }catch(Exception e){runOnUiThread(()->{updateState="error";updateNote=updateError(e);renderUpdate();});}
        });
    }
    /**
     * Preview builds never download: they fake the progress, then reinstall their own APK so the real installer
     * session, confirmation screen and status callback can be exercised on a device.
     */
    private java.io.File previewPackage()throws Exception{
        for(int p=0;p<=100;p+=10){updatePercent=p;runOnUiThread(this::renderUpdate);Thread.sleep(90);}
        java.io.File dir=new java.io.File(getCacheDir(),"updates");AppUpdate.clear(dir);if(!dir.mkdirs()&&!dir.isDirectory())throw new java.io.IOException("无法写入缓存");
        java.io.File copy=new java.io.File(dir,"preview.apk");
        try(java.io.InputStream in=new java.io.FileInputStream(getApplicationInfo().sourceDir);java.io.OutputStream out=new java.io.FileOutputStream(copy)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        return copy;
    }
    private void onUpdateStatus(int status){runOnUiThread(()->{
        if(isDestroyed())return;
        if(status==android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION){updateState="installing";updateNote="请在系统弹窗中确认安装";}
        else if(status==android.content.pm.PackageInstaller.STATUS_SUCCESS){updateState="installing";updateNote="安装完成，应用将重新启动";}
        else{updateState="error";updateNote=UpdateReceiver.message(status);}
        renderUpdate();
    });}
    private static String updateError(Exception e){String m=e.getMessage();return m!=null&&m.length()<=40&&m.matches(".*\\p{IsHan}.*")?m:"网络连接失败，请稍后重试";}
    private static String fileSize(long bytes){return bytes>=1048576?String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0):Math.max(1,Math.round(bytes/1024.0))+" KB";}
    /** Redraws only the update area of the 关于 page; a no-op on any other page. */
    private void renderUpdate(){
        LinearLayout box=updateBox;if(box==null||!ABOUT.equals(selectedId)||!box.isAttachedToWindow()&&box.getParent()==null)return;box.removeAllViews();
        AppUpdate.Release r=update;String state=updateState;
        if(r==null){
            LinearLayout group=theme.card(box);group.setPadding(dp(4),dp(4),dp(4),dp(4));
            String sub=switch(state){case "checking"->"正在检查…";case "latest"->"已是最新版本";case "failed"->(updateNote==null?"检查失败":updateNote)+"，点按重试";default->"点按检查";};
            featureRow("检查新版本",sub,"download",group,state.equals("checking")?null:this::checkUpdate);return;
        }
        LinearLayout c=theme.card(box);c.setPadding(dp(18),dp(16),dp(18),dp(18));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge=text("发现新版本",12,ACCENT,true);badge.setPadding(dp(8),dp(2),dp(8),dp(2));badge.setBackground(theme.shape((ACCENT&0x00ffffff)|0x1f000000,99));top.addView(badge);
        top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));top.addView(text(r.date.isEmpty()?"":ReleaseNotes.dateLabel(r.date,java.time.LocalDate.now())+"发布",12,theme.faint,false));c.addView(top);
        TextView version=text(r.version,26,INK,true);version.setPadding(0,dp(10),0,0);c.addView(version);
        TextView title=text(r.title,16,INK,false);title.setPadding(0,dp(2),0,0);c.addView(title);
        if(!r.summary.isEmpty()){TextView summary=text(r.summary,14,MUTED,false);summary.setPadding(0,dp(8),0,0);c.addView(summary);}
        TextView meta=text("安装包 "+fileSize(r.size)+" · 校验 SHA256 与签名后安装，保留登录和数据",12,theme.faint,false);meta.setPadding(0,dp(8),0,0);c.addView(meta);
        int p=updatePercent;
        if(state.equals("downloading")){
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setIndeterminate(p<0);bar.setProgress(Math.max(0,p));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.soft));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(6));bp.topMargin=dp(14);c.addView(bar,bp);
            TextView t=text(p>=100?"正在校验安装包…":p<0?"正在下载…":"正在下载 "+p+"%",13,MUTED,false);t.setPadding(0,dp(6),0,0);c.addView(t);
        }else if(updateNote!=null){TextView t=text(updateNote,13,state.equals("error")?theme.danger:MUTED,false);t.setPadding(0,dp(12),0,0);c.addView(t);}
        String label=switch(state){case "downloading"->p>=100?"正在校验…":"正在下载…";case "installing"->"等待系统安装";case "error"->"重试更新";default->"立即更新";};
        Button go=button(label,this::startUpdate);theme.button(go,true);go.setEnabled(!updateBusy());
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.topMargin=dp(16);c.addView(go,gp);
    }
    @Override public void onDeviceAuthorization(DeviceAuthorization info){if(resumed&&!isDestroyed())build();}
    private void showDeviceAuthorization(DeviceAuthorization info){
        heading.setText("授权此设备");session.setText("等待本人确认");
        leading.removeAllViews();leading.addView(theme.circle("back","取消验证",this::goBack),new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        LinearLayout c=step(1,"扫码打开安全验证页");
        // The code only carries the fixed verification address, never the one-time code: that is still typed and checked on the other device.
        android.widget.ImageView qr=new android.widget.ImageView(this);qr.setImageDrawable(new QrDrawable(QrCode.encode(info.verificationUri)));qr.setContentDescription("安全验证页地址的二维码");
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(dp(196),dp(196));qp.gravity=Gravity.CENTER_HORIZONTAL;qp.topMargin=dp(14);qp.bottomMargin=dp(10);c.addView(qr,qp);
        c.addView(text("用已经能登录 God 的 iPhone、手机或电脑扫码；也可以手动打开：",14,MUTED,false));
        TextView url=text(info.verificationUri.toString(),15,ACCENT,false);url.setPadding(0,dp(8),0,dp(4));c.addView(url);
        c=step(2,"输入这组一次性代码");
        TextView code=text(info.userCode,30,INK,true);code.setLetterSpacing(0.12f);code.setPadding(0,dp(18),0,dp(18));code.setGravity(Gravity.CENTER);code.setBackground(theme.shape(theme.soft,18));
        LinearLayout.LayoutParams box=new LinearLayout.LayoutParams(-1,-2);box.topMargin=dp(14);box.bottomMargin=dp(14);c.addView(code,box);
        c.addView(text("核对应用为「God 工作台 Android」，再确认授权。不要把代码发给其他人。",14,MUTED,false));
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
    private void login(){if(PreviewMode.isBuild()){error("界面预览","此预览不连接真实账号。");return;}if(coordinator.busy()||nativeLogin.running())return;session.setText("正在准备安全登录…");coordinator.login();}
    /** Read-only sign-in with a Google account bound in the user center; writes still need a Passkey sign-in. */
    private void googleLogin(){if(PreviewMode.isBuild()){error("界面预览","此预览不连接真实账号。");return;}if(coordinator.busy()||nativeLogin.running())return;session.setText(GOOGLE_PREPARING);coordinator.login("google");}
    private static final String GOOGLE_PREPARING="正在准备 Google 登录…";
    private void account(){if(PreviewMode.isBuild()){error("界面预览","仅包含合成展示数据，不连接账号或服务。");return;}if(!loggedIn()){show(new AlertDialog.Builder(this).setTitle("登录 God").setItems(new String[]{"通过已登录设备授权","使用 Passkey 登录","使用 Google 登录（只读）"},(d,w)->{if(w==0)deviceLogin();else if(w==1)login();else googleLogin();}).setNegativeButton("取消",null));return;}show(new AlertDialog.Builder(this).setTitle("God 账号").setMessage("会话保存在此设备的加密存储中。").setNegativeButton("取消",null).setPositiveButton("退出登录",(d,w)->signOut()));}
    private void goBack(){if(coordinator==null){finish();return;}if(coordinator.deviceAuthorization()!=null){coordinator.cancelDeviceLogin();build();return;}if(selectedId!=null){saveViewport();++generation;if(project!=null){project=null;ledgerCursor=null;ledgerPages.clear();showFeature(find(selectedId),true);}else{selectedId=NoticePages.parent(selectedId);enterFrom=-1;build();}}else if(!group.equals("总览")){switchTab("总览");}else moveTaskToBack(true);}
    // API 33+ uses the registered OnBackInvokedDispatcher; this is the API 30-32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    @Override protected void onStop(){saveViewport();viewportReady=false;super.onStop();board=null;accountStatus=null;accountDevices=null;accountNameRead=false;if(ledger!=null)ledger.close();++generation;snapshots.clear();if(auth!=null&&loggedIn()&&content!=null){content.removeAllViews();content.addView(text("God",24,INK,true));}}
    @Override protected void onDestroy(){++generation;nativeLogin.dispose();if(ledger!=null)ledger.dispose();worker.shutdownNow();updateCancel.set(true);updater.shutdownNow();if(UpdateReceiver.listener==updateListener)UpdateReceiver.listener=null;super.onDestroy();}
    private FeatureCatalog.Feature find(String id){return FeatureCatalog.all().stream().filter(f->f.id.equals(id)).findFirst().orElse(null);}
    private void section(String title){TextView t=text(title,15,INK,true);t.setAccessibilityHeading(true);t.setPadding(dp(6),dp(12),0,dp(8));content.addView(t);}
    private LinearLayout card(){return theme.card(content);}
    private TextView text(String s,int size,int color,boolean bold){TextView t=theme.text(s,size,bold);t.setTextColor(color);return t;}
    private Button button(String label,Runnable action){return theme.button(label,action);}
    private GradientDrawable round(int color,int radius){return theme.shape(color,radius);}
    private boolean loggedIn(){return PreviewMode.isBuild()?PreviewMode.loggedIn():auth.hasSession();}
    private JSONObject read(String path)throws Exception{return PreviewMode.isBuild()?PreviewMode.data(path):api.get(path);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    /**
     * Token 额度 needs god.mobile.tokens.read, requested since 0.10.1. Sessions stored before then fail the exact-scope
     * check and sign in again, so a 403 here means the grant on the server side is missing; say how to recover.
     */
    private String explain(String path,Exception e){
        if("wechat/digest".equals(path)&&e instanceof MobileApi.ApiError){String why=WechatDigest.failure(((MobileApi.ApiError)e).status,((MobileApi.ApiError)e).code);if(why!=null)return why;}
        if("tokens/quotas".equals(path)&&e instanceof MobileApi.ApiError&&((MobileApi.ApiError)e).status==403)return "这次登录没有 Token 额度读取权限，请在「我」里退出后重新登录。";
        return safeError(e);
    }
    /** 微信汇总的 503 文案已经说明原因，不再加「暂不可读」前缀。 */
    private String boardNote(String path,Exception e){
        if("wechat/digest".equals(path)&&e instanceof MobileApi.ApiError&&WechatDigest.failure(((MobileApi.ApiError)e).status,((MobileApi.ApiError)e).code)!=null)return explain(path,e);
        return "暂不可读："+explain(path,e);
    }
    private String safeError(Exception e){String m=e.getMessage();return m!=null&&m.matches("[\\p{IsHan}，。、；：！？ ·…A-Za-z0-9-]{1,100}")?m:"连接未完成，请检查网络后重试";}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private static final int TIBO_PERMISSION=43;private boolean tiboTestPending;
    @Override public void tiboSwitch(boolean on){
        if(PreviewMode.isBuild())return;
        if(!on){TiboWatch.disable(this);return;}
        if(!notificationsAllowed()){tiboTestPending=false;requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},TIBO_PERMISSION);return;}
        TiboWatch.enable(this);
        if(!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))tiboFix("battery");
    }
    @Override public void tiboTest(){if(!notificationsAllowed()){tiboTestPending=true;requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},TIBO_PERMISSION);return;}TiboWatch.test(this);}
    private boolean notificationsAllowed(){return Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTICE_PERMISSION){boolean granted=results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;
            if(!granted){error("没有通知权限","God 需要通知权限才能提醒你。可以在系统设置里为 God 开启通知后再试。");return;}
            if(noticePending==null)NoticeCenter.test(this);else enableNotice(noticePending,true);noticePending=null;return;}
        if(request==SYNC_PERMISSION){boolean granted=results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;
            if(!granted)error("没有通知权限","提醒已从账号同步到这台手机，但 God 没有通知权限，提醒不会送达。可以在系统设置里为 God 开启通知。");
            else if(TiboWatch.active(this))TiboWatch.start(this,true);
            TokensPages.refreshReminder();build();return;}
        if(request!=TIBO_PERMISSION)return;
        if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){if(tiboTestPending)TiboWatch.test(this);else tiboSwitch(true);}
        else error("没有通知权限","重置提醒需要通知权限才能响铃。可以在系统设置里为 God 开启通知后再试。");
        TokensPages.refreshReminder();if(NoticePages.page(selectedId))build();
    }
    /** Opens the system page that removes one obstacle named on the 重置提醒 card. The app is sideloaded, not on Play. */
    @android.annotation.SuppressLint("BatteryLife")
    @Override public void tiboFix(String what){
        String pkg=getPackageName();Uri app=Uri.parse("package:"+pkg);Intent intent=switch(what){
            case "notifications"->new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,pkg);
            case "channel"->new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,pkg).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,TiboWatch.CH_ALARM);
            case "fullscreen"->Build.VERSION.SDK_INT>=34?new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,app):new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,app);
            case "battery"->new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,app);
            case "exact"->Build.VERSION.SDK_INT>=31?new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,app):new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,app);
            default->new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,app);};
        try{startActivity(intent);}catch(RuntimeException e){try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,app));}catch(RuntimeException ignored){}}
    }
    private void error(String title,String message){show(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null));}
    private void show(AlertDialog.Builder builder){AlertDialog d=builder.create();d.show();theme.dialog(d);}
}
