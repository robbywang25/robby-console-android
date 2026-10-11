package wang.robby.console.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;

/** One native palette: readable data surfaces, glass only on the control plane. */
public final class ConsoleTheme {
    public final Context context;
    public final boolean dark, contrast, effects;
    public final int canvas, surface, ink, muted, accent, line, soft, danger;
    public ConsoleTheme(Context c) {
        context=c;
        dark=(c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        AccessibilityManager a=(AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        contrast=Build.VERSION.SDK_INT>=36 && a!=null && a.isHighContrastTextEnabled();
        android.os.PowerManager power=(android.os.PowerManager)c.getSystemService(Context.POWER_SERVICE);
        effects=!contrast&&android.animation.ValueAnimator.areAnimatorsEnabled()&&(power==null||!power.isPowerSaveMode());
        canvas=dark?0xff0e1117:0xfff4f6f9; surface=dark?0xff1b1f27:Color.WHITE;
        ink=dark?0xfff0f3fa:0xff17243b; muted=dark?0xffafb9ca:0xff627087;
        accent=dark?0xff9ab8ff:0xff315ad9; line=dark?0xff343c4b:0xffe2e7ef;
        soft=dark?0xff263651:0xffeaf0fe; danger=dark?0xffffb4a5:0xffa13c28;
    }
    public int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    public GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    public Drawable card(){GradientDrawable d=shape(surface,22);d.setStroke(dp(contrast?1.5f:0.5f),contrast?muted:line);return d;}
    public Drawable backdrop(){if(!effects)return new ColorDrawable(canvas);return new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{dark?0xff152038:0xffeaf0fc,canvas,canvas});}
    public Drawable glass(boolean selected){
        if(!effects)return selected?shape(soft,28):card();
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            selected?new int[]{dark?0xff344b70:0xffe0eaff,dark?0xff243653:0xfff0f5ff}:
                new int[]{dark?0xeb303949:0xf7ffffff,dark?0xc51c2635:0xe8edf2fb});
        d.setCornerRadius(dp(28));d.setStroke(dp(1),dark?0x557f96b7:0xfaffffff);return d;
    }
    public Drawable ripple(Drawable base){return new RippleDrawable(ColorStateList.valueOf(dark?0x339ab8ff:0x1e315ad9),base,shape(Color.WHITE,22));}
    public TextView text(String value,int size,boolean bold){TextView v=new TextView(context);v.setText(value);v.setTextSize(size);v.setTextColor(bold?ink:muted);v.setFontFeatureSettings("tnum");v.setLineSpacing(dp(2),1);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    public void button(Button b,boolean prominent){
        b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(18),dp(10),dp(18),dp(10));b.setStateListAnimator(null);b.setElevation(0);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{muted,prominent?(dark?0xff102244:Color.WHITE):accent}));
        b.setBackground(ripple(prominent?shape(accent,28):glass(false)));
    }
    public Button button(String label,Runnable action){Button b=new Button(context);b.setText(label);button(b,false);b.setOnClickListener(v->action.run());return b;}
    public LinearLayout card(LinearLayout root){LinearLayout c=new LinearLayout(context);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(card());c.setElevation(dark||contrast?0:dp(1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);root.addView(c,p);return c;}
    public ImageView icon(String name,int color,int size){ImageView v=new ImageView(context);v.setImageDrawable(new Symbol(name,color));v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return v;}
    public static String symbol(String id){return switch(id){
        case "总览","overview"->"grid";case "洞察","observer","umami","posthog","openai"->"chart";
        case "资产","domains"->"globe";case "工具"->"tools";case "设置"->"gear";
        case "travel-ledger"->"ledger";case "clipboard"->"clipboard";case "infrastructure"->"server";
        case "status","site-maintenance"->"pulse";case "market","okx"->"trend";
        case "engineering"->"code";case "image-workshop"->"image";case "wechat-cards","wechat-publishing","miniprogram-catalog"->"chat";
        case "prompts","visit-insight-reports"->"document";case "secrets","wechat-login-audit"->"shield";default->"grid";};}
    public void input(EditText v){v.setTextColor(ink);v.setHintTextColor(muted);v.setBackgroundTintList(null);v.setPadding(dp(12),dp(12),dp(12),dp(12));GradientDrawable focused=shape(surface,16);focused.setStroke(dp(1.5f),accent);StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},card());v.setBackground(states);v.setTextSize(16);}
    public void dialog(android.app.AlertDialog d){
        Window w=d.getWindow();if(w==null)return;w.setBackgroundDrawable(card());
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(dark?.55f:.25f);
        if(Build.VERSION.SDK_INT>=31&&effects)w.setBackgroundBlurRadius(dp(20));
        for(int which:new int[]{-1,-2,-3}){Button b=d.getButton(which);if(b!=null){b.setTextColor(accent);b.setAllCaps(false);b.setMinHeight(dp(48));}}
    }
    /** Frosted navigation samples only the app's own sibling view in memory. No screenshot or disk capture. */
    public static final class GlassBar extends LinearLayout {
        private final ConsoleTheme t;private final View source;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path clip=new Path();private final RectF bounds=new RectF();private LinearGradient tint;
        private final RenderNode node;private final int[] a=new int[2],b=new int[2];
        public GlassBar(Context c){this(c,null);}
        public GlassBar(Context c,View source){super(c);this.source=source;t=new ConsoleTheme(c);setWillNotDraw(false);setLayerType(View.LAYER_TYPE_NONE,null);
            node=Build.VERSION.SDK_INT>=31?new RenderNode("God navigation backdrop"):null;
            if(Build.VERSION.SDK_INT>=31&&node!=null)node.setRenderEffect(RenderEffect.createBlurEffect(t.dp(18),t.dp(18),Shader.TileMode.CLAMP));
            setElevation(t.dp(8));setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),t.dp(32));}});
        }
        public void clearBackdrop(){if(node!=null)node.discardDisplayList();}
        @Override protected void onDetachedFromWindow(){clearBackdrop();super.onDetachedFromWindow();}
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);tint=new LinearGradient(0,0,w,h,t.dark?0xe82b3445:0xeaffffff,t.dark?0xee192330:0xe2edf2fb,Shader.TileMode.CLAMP);}
        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();if(w==0||h==0)return;
            bounds.set(0,0,w,h);clip.reset();clip.addRoundRect(bounds,t.dp(32),t.dp(32),Path.Direction.CW);int save=c.save();c.clipPath(clip);
            c.drawColor(t.surface);
            if(Build.VERSION.SDK_INT>=31&&t.effects&&c.isHardwareAccelerated()&&source!=null&&source.getWidth()>0){
                getLocationInWindow(a);source.getLocationInWindow(b);node.setPosition(0,0,w,h);
                RecordingCanvas record=node.beginRecording(w,h);record.translate(b[0]-a[0],b[1]-a[1]);source.draw(record);node.endRecording();c.drawRenderNode(node);
            }
            paint.setShader(tint);c.drawRoundRect(bounds,t.dp(32),t.dp(32),paint);paint.setShader(null);
            paint.setColor(t.contrast?t.muted:t.dark?0x608ca0bd:0xfaffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(t.dp(1));bounds.inset(t.dp(.5f),t.dp(.5f));c.drawRoundRect(bounds,t.dp(32),t.dp(32),paint);paint.setStyle(Paint.Style.FILL);c.restoreToCount(save);
        }
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
                case "back":line(c,15,5,8,12,15,19);break;
                case "chevron":line(c,9,6,15,12,9,18);break;
                case "refresh":c.drawArc(4,4,20,20,40,285,false,p);line(c,15,3,20,4,20,9);break;
                case "key":c.drawCircle(8,9,4,p);line(c,11,12,20,21);line(c,16,17,19,14);break;
                default: c.drawCircle(12,12,8,p);
            }c.restoreToCount(s);
        }
        @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
