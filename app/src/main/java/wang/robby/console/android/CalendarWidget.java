package wang.robby.console.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 重置日历: the next five scheduled resets. Drawn by {@link GodWidgets}. */
public final class CalendarWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) { GodWidgets.update(c, manager, ids, WidgetSnapshot.CALENDAR); }
}
