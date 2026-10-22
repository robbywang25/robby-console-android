package wang.robby.console.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 微信消息: today's count and the seven-day daily average, never a conversation name. Drawn by {@link GodWidgets}. */
public final class WechatWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) { GodWidgets.update(c, manager, ids, WidgetSnapshot.WECHAT); }
}
