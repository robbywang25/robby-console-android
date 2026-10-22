package wang.robby.console.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/** Token 额度: up to six accounts, least left first. Its receiver also takes the stale-time redraw for all five. Drawn by {@link GodWidgets}. */
public final class TokenWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) { GodWidgets.update(c, manager, ids, WidgetSnapshot.TOKENS); }
    @Override public void onReceive(Context c, Intent intent) { super.onReceive(c, intent); if (GodWidgets.ACTION_REFRESH.equals(intent.getAction())) GodWidgets.refreshAll(c); }
}
