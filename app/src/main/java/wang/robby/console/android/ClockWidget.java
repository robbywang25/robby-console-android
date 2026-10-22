package wang.robby.console.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 时区时钟: the first three clocks of 编辑看板, ticked by the system; may sit on the lock screen. Drawn by {@link GodWidgets}. */
public final class ClockWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) { GodWidgets.update(c, manager, ids, GodWidgets.CLOCK); }
}
