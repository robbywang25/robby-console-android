package wang.robby.console.android;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * Floating control surface (the dock). On Android 12+ with motion and full effects it shows a live,
 * blurred copy of the content scrolling beneath it, then the theme's glass tint and rim on top.
 * Elsewhere it is the same tint drawn near-opaque. The blurred copy is a separate RenderNode that is
 * re-recorded before each frame; the pane itself is not invalidated, so it never drives extra frames.
 */
@SuppressLint("ViewConstructor") // Built in code with its blur source; never inflated from XML.
final class GlassPane extends FrameLayout {
    private final View source; private final RenderNode node; private final int[] from=new int[2], to=new int[2]; private final boolean motion;
    private View lens; private int slots, index, glideFrom=-1, inset;
    private final ViewTreeObserver.OnPreDrawListener follow=()->{record();return true;};
    GlassPane(ConsoleTheme theme, View source) {
        super(theme.context); this.source=source; motion=theme.effects; setWillNotDraw(false); setBackground(theme.dock()); setClipToOutline(true);
        if(theme.blur&&Build.VERSION.SDK_INT>=31){
            node=new RenderNode("glass-backdrop"); node.setClipToOutline(true);
            float r=theme.blurRadius(); node.setRenderEffect(RenderEffect.createBlurEffect(r,r,Shader.TileMode.CLAMP));
        } else node=null;
    }
    /**
     * Selection lens behind slot {@code selected} of {@code count} equal slots. When {@code previous} is another
     * slot the lens glides from there on first layout, so a tab switch reads as one continuous movement.
     */
    void lens(View v,int count,int selected,int previous,int insetPx){
        lens=v; slots=count; index=selected; inset=insetPx; glideFrom=motion&&previous>=0&&previous!=selected?previous:-1;
        addView(v,0,new LayoutParams(0,0));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        super.onLayout(changed,l,t,r,b);
        if(lens==null||slots==0||index<0)return;
        int w=(r-l-2*inset)/slots, x=inset+index*w; lens.layout(x,inset,x+w,b-t-inset);
        if(glideFrom>=0){ lens.setTranslationX((glideFrom-index)*w); glideFrom=-1;
            lens.animate().translationX(0).setDuration(380).setInterpolator(new OvershootInterpolator(1.1f)).start(); }
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(node!=null)getViewTreeObserver().addOnPreDrawListener(follow);}
    @Override protected void onDetachedFromWindow(){if(node!=null){getViewTreeObserver().removeOnPreDrawListener(follow);node.discardDisplayList();}super.onDetachedFromWindow();}
    private void record(){
        int w=getWidth(),h=getHeight(); if(node==null||w==0||h==0||source.getWidth()==0)return;
        node.setPosition(0,0,w,h); Outline o=new Outline(); o.setRoundRect(0,0,w,h,h/2f); node.setOutline(o);
        source.getLocationInWindow(from); getLocationInWindow(to);
        RecordingCanvas canvas=node.beginRecording(w,h);
        try{ canvas.translate(from[0]-to[0],from[1]-to[1]); source.draw(canvas); } finally { node.endRecording(); }
    }
    @Override public void draw(Canvas canvas){
        if(node!=null&&canvas.isHardwareAccelerated()&&node.hasDisplayList())canvas.drawRenderNode(node);
        super.draw(canvas);
    }
}
