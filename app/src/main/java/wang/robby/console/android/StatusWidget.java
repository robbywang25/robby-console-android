package wang.robby.console.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 运行状态: sites online and how many sites or upstreams are down. Drawn by {@link GodWidgets}. */
public final class StatusWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) { GodWidgets.update(c, manager, ids, WidgetSnapshot.STATUS); }
}
