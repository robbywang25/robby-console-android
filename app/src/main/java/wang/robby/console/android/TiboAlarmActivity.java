package wang.robby.console.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.time.ZoneId;

/**
 * The full-screen page of a Tibo 重置提醒, shown over the lock screen like an alarm. Only the public post is shown.
 * 知道了 stops the ringing; leaving without it lets the reminder ring again in ten minutes.
 */
public final class TiboAlarmActivity extends Activity {
    private TiboAlert alert;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true); setTurnScreenOn(true);
        show(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); show(intent); }

    private void show(Intent intent) {
        try { alert = TiboAlert.from(new JSONObject(intent.getStringExtra("alert"))); } catch (Exception e) { alert = null; }
        if (alert == null) { finish(); return; }
        // 查看原帖 from the notification: acknowledge, then open the post.
        if (TiboChallenge.official(intent.getStringExtra("open")) != null) { openPost(); return; }
        boolean test = intent.getBooleanExtra("test", false); ConsoleTheme theme = new ConsoleTheme(this); long now = System.currentTimeMillis();
        getWindow().getDecorView().setBackground(theme.backdrop());
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(theme.dp(24), theme.dp(72), theme.dp(24), theme.dp(40));
        FrameLayout bell = theme.tile("bell", 72); page.addView(bell, new LinearLayout.LayoutParams(theme.dp(72), theme.dp(72)));
        TextView label = theme.text(test ? "试响 · 不是真实预告" : alert.kindLabel(), 14, false); label.setPadding(0, theme.dp(20), 0, 0); label.setGravity(Gravity.CENTER); page.addView(label);
        TextView heading = theme.text(alert.heading(), 26, true); heading.setGravity(Gravity.CENTER); heading.setPadding(0, theme.dp(6), 0, 0); heading.setAccessibilityHeading(true); page.addView(heading);
        TextView when = theme.text(alert.when(ZoneId.systemDefault(), now), 17, true); when.setTextColor(theme.danger); when.setGravity(Gravity.CENTER); when.setPadding(0, theme.dp(12), 0, 0); page.addView(when);
        LinearLayout card = theme.card(page); ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = theme.dp(24);
        TextView title = theme.text(alert.title, 16, true); card.addView(title);
        if (!alert.excerpt.isEmpty()) { TextView excerpt = theme.text(alert.excerpt, 14, false); excerpt.setPadding(0, theme.dp(8), 0, 0); card.addView(excerpt); }
        TextView note = theme.text("来自 Tibo 本人账号的公开帖子，适用范围以原帖为准；账号额度是否到账以 Token 额度为准。", 12, false); note.setTextColor(theme.faint); note.setPadding(0, theme.dp(10), 0, 0); card.addView(note);
        Button ok = theme.button("知道了", () -> { TiboWatch.ack(this); finish(); }); theme.button(ok, true);
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(-1, -2); wide.topMargin = theme.dp(28); page.addView(ok, wide);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2); second.topMargin = theme.dp(10);
        if (!test) page.addView(theme.button("查看原帖", this::openPost), second);
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(-1, -2); third.topMargin = theme.dp(10);
        page.addView(theme.button("打开 28 天挑战", () -> { TiboWatch.ack(this); startActivity(new Intent(this, MainActivity.class).putExtra(TiboWatch.EXTRA_OPEN, "tibo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish(); }), third);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
        ok.requestFocus(); ok.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    private void openPost() {
        TiboWatch.ack(this);
        String url = alert == null ? null : TiboChallenge.official(alert.url);
        if (url != null && !url.endsWith("/0000000000000000000")) try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) { }
        finish();
    }
}
