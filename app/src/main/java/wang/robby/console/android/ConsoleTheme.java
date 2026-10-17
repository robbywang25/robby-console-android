package wang.robby.console.android;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.*;

/**
 * Liquid Glass palette. A soft sky gradient canvas, translucent content cards with a specular rim,
 * and a glass control layer (header buttons, dock, selection lens) that floats above content.
 * High-contrast text, disabled animations and battery saver fall back to solid, outlined surfaces.
 */
public final class ConsoleTheme {
    public final Context context;
    public final boolean dark, contrast, effects, blur;
    public final int canvas, surface, ink, muted, faint, accent, accentStrong, line, soft, danger, success, warning, glassFill, dockFill, lensFill, skyTop;
    /** WeChat's unread red (WeUI #FA5151): only for "something new you have not opened yet". */
    public final int unread=0xfffa5151;
    private final int skyMid, skyBottom;
    public ConsoleTheme(Context c) {
        context=c;
        dark=(c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        AccessibilityManager a=(AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        contrast=Build.VERSION.SDK_INT>=36 && a!=null && a.isHighContrastTextEnabled();
        android.os.PowerManager power=(android.os.PowerManager)c.getSystemService(Context.POWER_SERVICE);
        effects=!contrast&&android.animation.ValueAnimator.areAnimatorsEnabled()&&(power==null||!power.isPowerSaveMode());
        blur=effects&&Build.VERSION.SDK_INT>=31;
        accentStrong=0xff0052d9;
        if(dark){
            skyTop=0xff11203b;skyMid=0xff090c14;skyBottom=0xff050608;canvas=0xff080b12;
            surface=contrast?0xff161a22:0x1affffff;ink=Color.WHITE;muted=0xffa7aebb;faint=0xff737b8a;
            accent=0xff4582e6;line=contrast?0xff5a6170:0x1affffff;soft=0x1affffff;
            danger=0xffff8a80;success=0xff5ccb93;warning=0xfff2b552;
            glassFill=contrast?0xff1c212b:0x26ffffff;dockFill=blur?0x99161a22:0xf2161a22;lensFill=0x24ffffff;
        }else{
            skyTop=0xffcfe1f6;skyMid=0xffedf1f8;skyBottom=0xfff6ece3;canvas=0xffeef2f8;
            surface=contrast?Color.WHITE:0xd9ffffff;ink=0xff0f1115;muted=0xff5b6270;faint=0xff8a909c;
            accent=0xff0052d9;line=contrast?0xff8a909c:0x170b1a33;soft=0x0f0b1a33;
            danger=0xffd54941;success=0xff1f9a62;warning=0xffc96a0a;
            glassFill=contrast?Color.WHITE:0xb8ffffff;dockFill=blur?0xa6ffffff:0xf5ffffff;lensFill=0x140b1a33;
        }
    }
    public int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    public GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    /** Full-window sky gradient; solid in high-contrast mode. */
    public Drawable backdrop(){if(contrast)return new ColorDrawable(canvas);return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{skyTop,skyMid,skyBottom});}
    /** Content card: translucent, 24 dp, specular rim. */
    public Drawable card(){return card(24);}
    public Drawable card(float radius){if(contrast){GradientDrawable d=shape(surface,radius);d.setStroke(dp(1.5f),muted);return d;}return new Glass(surface,dp(radius),dp(1),dark,false);}
    /** Control-layer glass. A negative radius is a capsule. */
    public Drawable glass(float radius){if(contrast){GradientDrawable d=shape(glassFill,radius<0?99:radius);d.setStroke(dp(1.5f),muted);return d;}return new Glass(glassFill,radius<0?-1:dp(radius),dp(1),dark,true);}
    /** Bottom scroll-edge: transparent to the sky's bottom colour, so text never sits bare under the gesture bar. */
    public Drawable bottomFade(){int base=contrast?canvas:skyBottom;return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{base&0x00ffffff,(base&0x00ffffff)|0xcc000000,base});}
    public Drawable dock(){if(contrast){GradientDrawable d=shape(dockFill,99);d.setStroke(dp(1.5f),muted);return d;}return new Glass(dockFill,-1,dp(1),dark,true);}
    public Drawable lens(){if(contrast)return shape(soft,99);return new Glass(lensFill,-1,dp(.75f),dark,false);}
    public Drawable ripple(Drawable base){return ripple(base,22);}
    public Drawable ripple(Drawable base,float radius){return new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x140b1a33),base,radius<0?new Glass(Color.WHITE,-1,0,dark,false):shape(Color.WHITE,radius));}
    public TextView text(String value,int size,boolean bold){TextView v=new TextView(context);v.setText(value);v.setTextSize(size);v.setTextColor(bold?ink:muted);v.setFontFeatureSettings("tnum");v.setLineSpacing(dp(2),1);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    /** Left-aligned large page title, as in the reference app's tab roots. */
    public TextView title(String value){TextView v=new TextView(context);v.setText(value);v.setTextSize(30);v.setTextColor(ink);v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);v.setAccessibilityHeading(true);return v;}
    public void button(Button b,boolean prominent){
        b.setTextSize(15);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(20),dp(10),dp(20),dp(10));b.setElevation(0);b.setStateListAnimator(null);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{prominent?0x99ffffff:faint,prominent?Color.WHITE:accent}));
        b.setBackground(ripple(prominent?capsule(accentStrong):glass(-1),-1));press(b);
    }
    public Button button(String label,Runnable action){Button b=new Button(context);b.setText(label);button(b,false);b.setOnClickListener(v->action.run());return b;}
    private Drawable capsule(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(99));return d;}
    public LinearLayout card(LinearLayout root){LinearLayout c=new LinearLayout(context);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(card());c.setElevation(0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);root.addView(c,p);return c;}
    /** Round glass control (48 dp) holding one symbol; used for header actions. */
    public FrameLayout circle(String symbol,String description,Runnable action){
        FrameLayout f=new FrameLayout(context);f.setBackground(ripple(glass(-1),-1));f.setContentDescription(description);f.setTooltipText(description);f.setFocusable(true);f.setClickable(true);
        f.addView(icon(symbol,ink,22),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));f.setOnClickListener(v->action.run());press(f);return f;
    }
    /** Small rounded tile with a faint category tint behind a monochrome symbol. Color stays auxiliary. */
    public FrameLayout tile(String symbol,int size){
        FrameLayout box=new FrameLayout(context);int hue=hue(symbol);
        box.setBackground(contrast?shape(soft,size*.3f):new Glass((hue&0x00ffffff)|(dark?0x38000000:0x24000000),dp(size*.3f),dp(.75f),dark,false));
        int glyph=Math.round(size*.55f);box.addView(icon(symbol,ink,glyph),new FrameLayout.LayoutParams(dp(glyph),dp(glyph),Gravity.CENTER));return box;
    }
    private static int hue(String symbol){return switch(symbol){
        case "chart","trend"->0xff3b82f6;case "ledger","plus","edit"->0xfff59e0b;case "globe","server"->0xff14b8a6;case "pulse"->0xff22c55e;
        case "clipboard","document","history","gauge","calendar"->0xff8b5cf6;case "bell"->0xffef4444;case "chat"->0xff07c160;case "image"->0xffec4899;case "shield","key","user","people","download"->0xff0052d9;
        case "trash"->0xffef4444;default->0xff64748b;};}
    public ImageView icon(String name,int color,int size){if("grid".equals(name))return tabIcon(name,true,size);ImageView v=new ImageView(context);v.setImageDrawable(new Symbol(name,ink));v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return v;}
    public ImageView tabIcon(String name,boolean selected,int size){
        int asset=switch(name){
            case "grid"->selected?R.drawable.tab_home_solid:R.drawable.tab_home_outline;
            case "chart"->selected?R.drawable.tab_insights_solid:R.drawable.tab_insights_outline;
            case "globe"->selected?R.drawable.tab_assets_solid:R.drawable.tab_assets_outline;
            case "tools"->selected?R.drawable.tab_tools_solid:R.drawable.tab_tools_outline;
            case "features"->selected?R.drawable.tab_features_solid:R.drawable.tab_features_outline;
            case "me"->selected?R.drawable.tab_me_solid:R.drawable.tab_me_outline;
            default->selected?R.drawable.tab_settings_solid:R.drawable.tab_settings_outline;
        };
        ImageView v=new ImageView(context);v.setImageResource(asset);v.setImageTintList(ColorStateList.valueOf(selected?ink:muted));v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return v;
    }
    public static String symbol(String id){return switch(id){
        case "总览","overview"->"grid";case "洞察","observer","umami","posthog","openai"->"chart";
        case "资产","domains"->"globe";case "tokens"->"gauge";case "quota-calendar"->"calendar";case "tibo"->"bell";case "工具"->"tools";case "功能"->"features";case "设置"->"me";
        case "travel-ledger"->"ledger";case "clipboard"->"clipboard";case "infrastructure"->"server";
        case "status","site-maintenance"->"pulse";case "market","okx"->"trend";
        case "engineering"->"code";case "image-workshop"->"image";case "wechat-cards","wechat-publishing","miniprogram-catalog"->"chat";
        case "prompts","visit-insight-reports"->"document";case "secrets","wechat-login-audit"->"shield";default->"grid";};}
    /** Visible tab names. "设置" stays the internal identity so saved navigation keeps working. */
    public static String tabLabel(String group){return "设置".equals(group)?"我":group;}
    /**
     * Auxiliary status colour for a rendered value, or 0 when the value carries no status.
     * Only exact display strings produced by FeatureRenderer qualify; the text always stays visible.
     */
    public int status(String value){
        if(value==null)return 0;
        return switch(value){
            case "正常","运行中","在线","可达","已解析","成功","良好","可用","已配置","已开启","已管理","已匹配","完整","已绑定","登录成功","可直接比较","管理员已绑定"->success;
            case "需关注","待处理","待改善","数据已陈旧","维护中","部分币种缺失","可能尚未完整","需核对","缓存","口径不同","未开启","未管理","需要绑定","有"->warning;
            case "暂不可用","异常","离线","不可达","未解析","较差","已拒绝","登录被拒绝","未配置","需要重新登录","未匹配，需官方核验","非已绑定管理员"->danger;
            default->0;
        };
    }
    public View dot(int color,float size){View v=new View(context);GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);v.setBackground(d);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return v;}
    /** Liquid "flex": pressed controls sink slightly and spring back. Skipped when motion is reduced. */
    public void press(View v){
        if(!effects)return;
        StateListAnimator s=new StateListAnimator();
        AnimatorSet down=new AnimatorSet();down.playTogether(ObjectAnimator.ofFloat(null,"scaleX",.96f),ObjectAnimator.ofFloat(null,"scaleY",.96f));down.setDuration(110);down.setInterpolator(new DecelerateInterpolator());
        AnimatorSet up=new AnimatorSet();up.playTogether(ObjectAnimator.ofFloat(null,"scaleX",1f),ObjectAnimator.ofFloat(null,"scaleY",1f));up.setDuration(320);up.setInterpolator(new OvershootInterpolator(2.2f));
        s.addState(new int[]{android.R.attr.state_pressed},down);s.addState(new int[0],up);v.setStateListAnimator(s);
    }
    /** Page entrance: fade with a short slide. dx>0 enters from the right (push), dx<0 from the left (pop). */
    public void enter(View v,int dx,int dy){
        if(!effects)return;
        v.setAlpha(0f);v.setTranslationX(dp(dx));v.setTranslationY(dp(dy));
        v.animate().alpha(1f).translationX(0).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
    }
    public void input(EditText v){v.setTextColor(ink);v.setHintTextColor(faint);v.setBackgroundTintList(null);v.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable focused=shape(soft,14);focused.setStroke(dp(1.5f),accent);StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},focused);GradientDrawable normal=shape(soft,14);normal.setStroke(dp(1),dark?0x33ffffff:0x260b1a33);states.addState(new int[]{},normal);v.setBackground(states);v.setTextSize(16);}
    public void dialog(android.app.AlertDialog d){
        Window w=d.getWindow();if(w==null)return;
        // Dialogs are for reading: an opaque glass face with the rim. On the reference ColorOS device cross-window
        // blur was not composited and even a 98% face let page text show through, so the face is fully opaque.
        w.setBackgroundDrawable(contrast?card():new Glass(dark?0xff1a1d24:0xffffffff,dp(28),dp(1),dark,true));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(dark?.5f:.2f);
        for(int which:new int[]{-1,-2,-3}){Button b=d.getButton(which);if(b!=null){b.setTextColor(accent);b.setAllCaps(false);b.setMinHeight(dp(48));}}
    }

    /** Translucent fill, a rim that is brighter where light hits the top edge, and an optional top sheen. Radius &lt; 0 is a capsule. */
    static final class Glass extends Drawable {
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),rim=new Paint(Paint.ANTI_ALIAS_FLAG),sheen=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius,stroke;private final boolean dark,shine;private final RectF r=new RectF(),in=new RectF();
        Glass(int color,float radius,float stroke,boolean dark,boolean shine){this.radius=radius;this.stroke=stroke;this.dark=dark;this.shine=shine;fill.setColor(color);rim.setStyle(Paint.Style.STROKE);rim.setStrokeWidth(stroke);}
        private float corner(RectF b){return radius<0?b.height()/2f:Math.min(radius,Math.min(b.width(),b.height())/2f);}
        @Override protected void onBoundsChange(Rect b){
            r.set(b);float h=stroke/2f;in.set(r.left+h,r.top+h,r.right-h,r.bottom-h);
            rim.setShader(stroke<=0?null:new LinearGradient(0,b.top,0,b.bottom,dark?new int[]{0x47ffffff,0x14ffffff,0x0dffffff}:new int[]{0xffffffff,0x99ffffff,0x1f0b1a33},new float[]{0,.55f,1},Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(0,b.top,0,b.top+b.height()*.6f,dark?0x14ffffff:0x66ffffff,0x00ffffff,Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas c){float k=corner(r);c.drawRoundRect(r,k,k,fill);if(shine)c.drawRoundRect(r,k,k,sheen);if(stroke>0){float j=corner(in);c.drawRoundRect(in,j,j,rim);}}
        @Override public void getOutline(Outline o){Rect b=getBounds();o.setRoundRect(b,radius<0?b.height()/2f:Math.min(radius,Math.min(b.width(),b.height())/2f));}
        @Override public void setAlpha(int a){fill.setAlpha(a);}
        @Override public void setColorFilter(ColorFilter f){fill.setColorFilter(f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    /** Small consistent vector symbols, never font-dependent emoji. Coordinates use a 24-unit grid. */
    private static final class Symbol extends Drawable {
        final String name;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Symbol(String n,int color){name=n;p.setColor(color);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        void line(Canvas c,float...v){Path q=new Path();q.moveTo(v[0],v[1]);for(int i=2;i<v.length;i+=2)q.lineTo(v[i],v[i+1]);c.drawPath(q,p);}
        void box(Canvas c,float x,float y,float w,float h){c.drawRoundRect(x,y,x+w,y+h,2,2,p);}
        @Override public void draw(Canvas c){int s=c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
            switch(name){
                case "grid":box(c,3,3,7,7);box(c,14,3,7,7);box(c,3,14,7,7);box(c,14,14,7,7);break;
                case "chart":line(c,3,3,3,21,22,21);line(c,7,16,7,11);line(c,12,16,12,6);line(c,17,16,17,9);break;
                case "trend":line(c,3,17,9,11,13,14,21,5);line(c,15,5,21,5,21,11);break;
                case "globe":c.drawCircle(12,12,9,p);c.drawOval(8,3,16,21,p);line(c,3,12,21,12);break;
                case "ledger":box(c,5,3,15,18);line(c,9,3,9,21);line(c,12,8,16,8);line(c,12,13,16,13);line(c,3,7,6,7);line(c,3,17,6,17);break;
                case "clipboard":box(c,5,5,14,16);box(c,9,2,6,5);line(c,9,12,15,12);line(c,9,16,14,16);break;
                case "server":box(c,3,3,18,7);box(c,3,14,18,7);line(c,7,6.5f,7.1f,6.5f);line(c,7,17.5f,7.1f,17.5f);line(c,15,6.5f,18,6.5f);line(c,15,17.5f,18,17.5f);break;
                case "pulse":line(c,2,12,6,12,9,5,14,19,17,12,22,12);break;
                case "code":line(c,7,6,2,12,7,18);line(c,17,6,22,12,17,18);line(c,14,3,10,21);break;
                case "image":box(c,3,3,18,18);c.drawCircle(8,8,1.5f,p);line(c,3,17,9,11,14,16,17,13,21,17);break;
                case "chat":box(c,3,3,18,15);line(c,7,18,7,22,12,18);line(c,7,8,17,8);line(c,7,12,14,12);break;
                case "document":box(c,5,3,14,18);line(c,9,8,15,8);line(c,9,12,15,12);line(c,9,16,13,16);break;
                case "shield":line(c,12,2,21,6,20,15,16,20,12,22,8,20,4,15,3,6,12,2);line(c,8,12,11,15,16,9);break;
                case "gear":c.drawCircle(12,12,7,p);c.drawCircle(12,12,2.5f,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);line(c,12,2,12,5);c.restore();}break;
                case "tools":line(c,5,3,10,8,8,10,3,5);line(c,9,9,20,20);c.drawCircle(19,19,2,p);line(c,21,3,18,6,18,9,15,12,12,9,15,6,15,3);line(c,11,14,5,20,3,18,9,12);break;
                case "user":c.drawCircle(12,8,3.5f,p);c.drawArc(4,13,20,27,185,170,false,p);break;
                case "people":c.drawCircle(9,8,3.2f,p);c.drawArc(2.5f,13.5f,15.5f,26,180,180,false,p);c.drawCircle(16.5f,8.5f,2.6f,p);c.drawArc(13,14.5f,21.5f,24,250,110,false,p);break;
                case "back":line(c,15,5,8,12,15,19);break;
                case "chevron":line(c,9,6,15,12,9,18);break;
                case "search":c.drawCircle(10.5f,10.5f,6.5f,p);line(c,15.5f,15.5f,21,21);break;
                case "clear":line(c,7,7,17,17);line(c,17,7,7,17);break;
                case "refresh":c.drawArc(4,4,20,20,40,285,false,p);line(c,15,3,20,4,20,9);break;
                case "key":c.drawCircle(8,9,4,p);line(c,11,12,20,21);line(c,16,17,19,14);break;
                case "plus":line(c,12,5,12,19);line(c,5,12,19,12);break;
                case "edit":line(c,4,20,4,16,15,5,19,9,8,20,4,20);line(c,13,7,17,11);break;
                case "trash":line(c,4,7,20,7);line(c,9,7,9,4,15,4,15,7);line(c,6,7,7,20,17,20,18,7);line(c,10,11,10,16);line(c,14,11,14,16);break;
                case "history":c.drawCircle(12,12,8.5f,p);line(c,12,7.5f,12,12,15,14);break;
                case "swap":line(c,4,8,18,8);line(c,15,5,18,8,15,11);line(c,20,16,6,16);line(c,9,13,6,16,9,19);break;
                case "info":c.drawCircle(12,12,9,p);line(c,12,11,12,16.5f);line(c,12,7.6f,12,7.7f);break;
                case "gauge":c.drawArc(3,5,21,23,180,180,false,p);line(c,12,14,16.5f,8.5f);c.drawCircle(12,14,1.2f,p);break;
                case "download":line(c,12,4,12,15);line(c,7,10,12,15,17,10);line(c,5,20,19,20);break;
                case "calendar":box(c,4,5.5f,16,14.5f);line(c,4,10,20,10);line(c,8.5f,3.5f,8.5f,7.5f);line(c,15.5f,3.5f,15.5f,7.5f);line(c,8,14,8.1f,14);line(c,12,14,12.1f,14);line(c,16,14,16.1f,14);line(c,8,17,8.1f,17);break;
                case "bell":line(c,6,16.5f,6,11);c.drawArc(6,5,18,17,180,180,false,p);line(c,18,11,18,16.5f);line(c,4.5f,16.5f,19.5f,16.5f);line(c,10,19.5f,14,19.5f);line(c,12,3.5f,12,5);break;
                default: c.drawCircle(12,12,8,p);
            }c.restoreToCount(s);
        }
        @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
