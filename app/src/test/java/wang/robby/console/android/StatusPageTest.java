package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** The phone must name each probe result the way status.robby.wang does, and never call an unchecked site healthy. */
public final class StatusPageTest {
    @Test public void siteLabelsFollowTheWebPage() {
        assertEquals("在线", StatusPage.site(true, true, "public_reachable", true));
        assertEquals("认证门可达", StatusPage.site(true, true, "auth_gateway", true));
        assertEquals("服务可达", StatusPage.site(true, true, "service_reachable", true));
        assertEquals("认证边界异常", StatusPage.site(false, true, "auth_gateway", true));
        assertEquals("DNS 未解析", StatusPage.site(false, false, "public_reachable", false));
        assertEquals("暂不可达", StatusPage.site(false, false, "public_reachable", true));
        assertEquals("未检测", StatusPage.site(null, null, "public_reachable", null));
    }
    @Test public void uncheckedIsNeitherHealthyNorBroken() {
        assertEquals(StatusPage.NONE, StatusPage.siteTone(null));
        assertEquals(StatusPage.OK, StatusPage.siteTone(true));
        assertEquals(StatusPage.BAD, StatusPage.siteTone(false));
    }
    @Test public void staleUpstreamNeverReadsAsCurrent() {
        assertEquals("上次状态 · 已过期", StatusPage.upstream(false, true, "operational"));
        assertEquals(StatusPage.WARN, StatusPage.upstreamTone(false, true, "operational"));
        assertEquals("全部正常", StatusPage.upstream(true, false, "operational"));
        assertEquals("暂不可读", StatusPage.upstream(false, false, "unknown"));
        assertEquals(StatusPage.BAD, StatusPage.upstreamTone(true, false, "outage"));
        assertEquals("性能下降", StatusPage.component("degraded_performance"));
        assertEquals(StatusPage.BAD, StatusPage.componentTone("major_outage"));
        assertEquals("未知", StatusPage.component("something_new"));
    }
    @Test public void verdictPutsProblemsBeforeGaps() {
        assertEquals("2 项需要关注", StatusPage.verdict(2, 1));
        assertEquals("1 项尚未检测", StatusPage.verdict(0, 1));
        assertEquals("全部符合预期", StatusPage.verdict(0, 0));
        assertEquals(StatusPage.BAD, StatusPage.verdictTone(2, 1, 0));
        assertEquals(StatusPage.WARN, StatusPage.verdictTone(1, 0, 0));
    }
    @Test public void figureCaptionsNeverOverstate() {
        assertEquals("全部符合预期", StatusPage.figureCaption(7, 7, 7));
        assertEquals("1 项异常", StatusPage.figureCaption(6, 7, 7));
        assertEquals("2 项未检测", StatusPage.figureCaption(5, 5, 7));
        assertEquals("计数未知", StatusPage.figureCaption(-1, -1, -1));
        assertEquals("计数未知", StatusPage.figureCaption(8, 7, 7));
        assertEquals(StatusPage.BAD, StatusPage.figureTone(4, 5, 7));
    }
}
