package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Which of Tibo's reset or card-grant announcements should ring, and what the alarm says. Pure, so the rules are tested
 * without a phone: only intent kinds from Tibo's own verified posts, each post at most once, nothing from before the
 * reminder was switched on, and nothing old unless its announced time is still ahead.
 */
final class TiboAlert {
    static final long RECENT_MS = 24 * 3600_000L;
    final String id, kind, title, excerpt, url; final long published, deadline; final boolean conditional;
    TiboAlert(String id, String kind, String title, String excerpt, String url, long published, long deadline, boolean conditional) {
        this.id = id; this.kind = kind; this.title = title; this.excerpt = excerpt; this.url = url; this.published = published; this.deadline = deadline; this.conditional = conditional;
    }

    /** The tokens/tibo/alerts view; anything not an intent post of Tibo's own account is dropped. */
    static List<TiboAlert> parse(JSONObject p) {
        List<TiboAlert> out = new ArrayList<>(); if (p == null || p.optInt("schemaVersion", -1) != 1) return out;
        JSONArray list = p.optJSONArray("alerts"); if (list == null) return out;
        for (int i = 0; i < Math.min(50, list.length()); i++) {
            JSONObject e = list.optJSONObject(i); if (e == null) continue;
            TiboAlert a = from(e); if (a != null) out.add(a);
        }
        return out;
    }
    static TiboAlert from(JSONObject e) {
        TiboChallenge.Post post = new TiboChallenge.Post(e);
        if (!post.id.matches("\\d{15,22}") || post.url == null || !post.url.endsWith("/" + post.id) || !TiboChallenge.INTENT.contains(post.kind) || post.published <= 0 || post.title.isEmpty()) return null;
        return new TiboAlert(post.id, post.kind, post.title, post.excerpt, post.url, post.published, post.deadline, post.conditional);
    }
    JSONObject json() {
        try { return new JSONObject().put("id", id).put("kind", kind).put("title", title).put("excerpt", excerpt).put("sourceUrl", url).put("verified", true)
            .put("publishedAt", Instant.ofEpochMilli(published).toString()).put("deadline", deadline > 0 ? Instant.ofEpochMilli(deadline).toString() : JSONObject.NULL).put("conditional", conditional); }
        catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    /**
     * Alerts that should ring now, newest first. The first check after switching on only learns what is already there
     * ({@code primed} false). A post rings once; one older than a day rings only while its announced time is ahead.
     */
    static List<TiboAlert> due(List<TiboAlert> alerts, Set<String> seen, boolean primed, long enabledAt, long now) {
        List<TiboAlert> out = new ArrayList<>(); if (!primed) return out;
        for (TiboAlert a : alerts) {
            if (seen.contains(a.id) || a.published < enabledAt - 60_000L) continue;
            if (now - a.published <= RECENT_MS || a.deadline > now) out.add(a);
        }
        out.sort((a, b) -> Long.compare(b.published, a.published)); return out;
    }

    String kindLabel() { return TiboChallenge.kindLabel(kind); }
    String heading() {
        String base = switch (kind) { case "reset-scheduled" -> "Tibo 定好了额度重置时间"; case "cards-announced" -> "Tibo 宣布发放重置卡"; case "cards-scheduled" -> "Tibo 定好了发卡时间"; default -> "Tibo 预告了额度重置"; };
        return conditional ? base + "（有条件）" : base;
    }
    /** The announced time in the phone's zone with what is left, or that no time was given. */
    String when(ZoneId zone, long now) {
        if (deadline <= 0) return "时间未公布";
        String at = DateTimeFormatter.ofPattern("MM/dd HH:mm").format(Instant.ofEpochMilli(deadline).atZone(zone));
        long minutes = (deadline - now) / 60_000L;
        return minutes <= 0 ? at + " · 预告时间已到" : at + " · " + (minutes >= 60 ? minutes / 60 + " 小时 " + minutes % 60 + " 分钟后" : minutes + " 分钟后");
    }
    String body(ZoneId zone, long now) { return when(zone, now) + (excerpt.isEmpty() ? "" : "\n" + excerpt); }
}
