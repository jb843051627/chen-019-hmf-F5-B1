package com.fc.v2.hmf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fc.v2.model.auto.THmfSpendLine;
import com.fc.v2.service.impl.THmfSpendLineServiceImpl;

/**
 * 支出审定额线口径逐条核对：在场窗口（启用够得着、交棒不含当天）、缺交棒之日按长期在场、
 * 已交棒不进现行、并列取顺位大者再取代号靠后、无可用线不硬凑、取值范围外不收、
 * 名单/逐条/角标同一把尺、按代号求值只看 at 时刻生效版本。
 */
class SpendLineRuleEvalTest {

    private THmfSpendLineServiceImpl service;
    private FakeLines lines;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new THmfSpendLineServiceImpl();
        this.lines = new FakeLines();
        Field f = THmfSpendLineServiceImpl.class.getDeclaredField("hmfSpendLineMapper");
        f.setAccessible(true);
        f.set(service, lines);
    }

    /** 审定线内存假 Mapper。 */
    static final class FakeLines extends InMemoryMapper<THmfSpendLine>
            implements com.fc.v2.mapper.auto.THmfSpendLineMapper {
        @Override
        Serializable idOf(THmfSpendLine t) {
            return t.getId();
        }

        @Override
        void assignId(THmfSpendLine t, long id) {
            t.setId(id);
        }
    }

    private static Date d(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private THmfSpendLine line(String code, String th1, String th2, String th3,
                               String start, String end, Integer priority, Integer status) {
        THmfSpendLine r = new THmfSpendLine();
        r.setRuleCode(code);
        r.setRuleName("线-" + code);
        if (th1 != null) {
            r.setTh1Max(new BigDecimal(th1));
        }
        if (th2 != null) {
            r.setTh2Max(new BigDecimal(th2));
        }
        if (th3 != null) {
            r.setTh3Max(new BigDecimal(th3));
        }
        r.setEffStart(start == null ? null : d(start));
        r.setEffEnd(end == null ? null : d(end));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        lines.insert(r);
        return r;
    }

    private List<String> codesOf(List<THmfSpendLine> rows) {
        return rows.stream().map(THmfSpendLine::getRuleCode).collect(Collectors.toList());
    }

    // ---- 在场窗口：启用之日够得着、交棒之日（不含）还没到；交替日旧退新进 ----

    @Test
    void handoverDay_oldOutNewIn_sameAnswerEverywhere() {
        line("LA", "10", "20", "30", "2025-01-01 00:00:00", "2025-07-01 00:00:00", 1, 0);
        line("LB", "50", "60", "70", "2025-07-01 00:00:00", null, 1, 0);

        Date before = d("2025-06-30 23:59:59");
        Date handover = d("2025-07-01 00:00:00");

        assertEquals(java.util.Arrays.asList("LA"), codesOf(service.listAvailable(before)),
                "交替日前一刻还是旧线在场");
        assertEquals(java.util.Arrays.asList("LB"), codesOf(service.listAvailable(handover)),
                "交替当天：旧的从当天退出、新的从当天进来");

        // 同一刻两处的答案要一模一样：名单头一条与求值所用同线
        assertEquals(3, service.evaluateTop(new BigDecimal("25"), before),
                "旧线 25 落在 (20,30] 判 3 档");
        assertEquals(1, service.evaluateTop(new BigDecimal("25"), handover),
                "新线 25 落在 [0,50] 判 1 档");
        assertEquals(service.countAvailable(before), service.listAvailable(before).size());
        assertEquals(service.countAvailable(handover), service.listAvailable(handover).size());
    }

    @Test
    void backfillUsesLineActiveAtThatTime_notTheNewest() {
        line("LA", "10", "20", "30", "2025-01-01 00:00:00", "2025-07-01 00:00:00", 1, 0);
        line("LB", "50", "60", "70", "2025-07-01 00:00:00", null, 9, 0);
        // 补算去年下半年之前那一期：照那一期的日子回看当时在场的那条，
        // 不能顺手拿最新立（顺位也更高）的那条顶上去
        assertEquals(3, service.evaluateTop(new BigDecimal("25"), d("2025-06-15 12:00:00")));
    }

    // ---- 缺交棒之日按长期在场；已交棒不进现行 ----

    @Test
    void missingEndDateTreatedAsLongTermActive() {
        line("LO", "10", "20", "30", "2020-01-01 00:00:00", null, 1, 0);
        assertEquals(1, service.listAvailable(d("2026-10-03 10:00:00")).size(),
                "只有启用之日、没填交棒之日的老账按长期在场认");
        assertEquals(1, service.countAvailable(d("2026-10-03 10:00:00")));
    }

    @Test
    void retiredLineStaysOutOfCurrentCalculation() {
        THmfSpendLine dead = line("OLD", "10", "20", "30", "2020-01-01 00:00:00", null, 9, 1);
        Date at = d("2026-10-03 10:00:00");
        assertTrue(service.listAvailable(at).isEmpty(), "已交棒的不进现行名单");
        assertEquals(0, service.countAvailable(at));
        assertEquals(0, service.evaluateTop(new BigDecimal("5"), at));
        assertFalse(service.usable(dead.getId(), at), "已交棒逐条判也是不可用");
    }

    // ---- 并列在场：顺位大的先说话；顺位同取代号靠后的；两次算不许给两条 ----

    @Test
    void tiesBreakByPriorityThenCodeDesc_deterministic() {
        line("LX-01", "10", "20", "30", "2025-01-01 00:00:00", null, 5, 0);
        line("LX-02", "40", "50", "60", "2025-01-01 00:00:00", null, 5, 0);
        line("LZ-00", "70", "80", "90", "2025-01-01 00:00:00", null, 9, 0);
        Date at = d("2026-01-01 00:00:00");

        assertEquals(java.util.Arrays.asList("LZ-00", "LX-02", "LX-01"),
                codesOf(service.listAvailable(at)),
                "顺位降序；顺位并列取代号靠后的");

        int first = service.evaluateTop(new BigDecimal("5"), at);
        int second = service.evaluateTop(new BigDecimal("5"), at);
        assertEquals(first, second, "两次算给回两条不同的线即算错");
        assertEquals(1, first, "顺位最大的 LZ-00 说话：5 落在 [0,70] 判 1 档");
    }

    // ---- 一条也够不着：不硬凑结论 ----

    @Test
    void noReachableLine_givesNoConclusion() {
        line("GONE", "10", "20", "30", "2024-01-01 00:00:00", "2024-12-31 00:00:00", 1, 0);
        Date at = d("2025-08-01 00:00:00");
        assertEquals(0, service.evaluateTop(new BigDecimal("5"), at),
                "无可用线：求值不给结论，不拿邻近那条的数凑");
        assertTrue(service.listAvailable(at).isEmpty());
        assertEquals(0, service.countAvailable(at));
    }

    // ---- 取值范围之外的数今年不收 ----

    @Test
    void outOfRangeInputRejected() {
        line("R", "10", "20", "100", "2025-01-01 00:00:00", null, 1, 0);
        Date at = d("2026-01-01 00:00:00");
        assertEquals(0, service.evaluateTop(new BigDecimal("-0.01"), at), "负数不收");
        assertEquals(0, service.evaluateTop(new BigDecimal("100.01"), at), "越过上限不收");
        assertEquals(3, service.evaluateTop(new BigDecimal("100"), at), "贴着上限照收");
        assertEquals(0, service.evaluate("R", new BigDecimal("-5"), at), "按代号求值同一道门");
        assertEquals(0, service.evaluate("R", new BigDecimal("101"), at));
    }

    // ---- 分档边界：贴着上限归该档 ----

    @Test
    void levelBoundaries() {
        line("L", "10", "20", "30", "2025-01-01 00:00:00", null, 1, 0);
        Date at = d("2026-01-01 00:00:00");
        assertEquals(1, service.evaluateTop(new BigDecimal("0"), at));
        assertEquals(1, service.evaluateTop(new BigDecimal("10"), at));
        assertEquals(2, service.evaluateTop(new BigDecimal("10.01"), at));
        assertEquals(2, service.evaluateTop(new BigDecimal("20"), at));
        assertEquals(3, service.evaluateTop(new BigDecimal("30"), at));
        assertEquals(4, service.evaluateTop(new BigDecimal("30.01"), at));
    }

    // ---- 逐条判定 usable：与名单同一把尺；三界全空、高界越界都起不来 ----

    @Test
    void usableChecksSameRulerAsList() {
        THmfSpendLine ok = line("U1", "10", "20", "30", "2025-01-01 00:00:00", null, 1, 0);
        THmfSpendLine future = line("U2", "10", "20", "30", "2027-01-01 00:00:00", null, 1, 0);
        THmfSpendLine noTh = line("U3", null, null, null, "2025-01-01 00:00:00", null, 1, 0);
        THmfSpendLine overTop = line("U4", "10", "20", "101", "2025-01-01 00:00:00", null, 1, 0);
        Date at = d("2026-01-01 00:00:00");

        assertTrue(service.usable(ok.getId(), at));
        assertFalse(service.usable(future.getId(), at), "启用之日还没够着");
        assertFalse(service.usable(noTh.getId(), at), "三条分界数全空起不来");
        assertFalse(service.usable(overTop.getId(), at), "高界越出取值范围不收");
        assertFalse(service.usable(null, at));
        assertFalse(service.usable(ok.getId(), null));
        assertFalse(service.usable(999999L, at), "查无此条");
    }

    // ---- 按代号求值：只认 at 时刻生效的那一条版本 ----

    @Test
    void evaluateByCodePicksVersionEffectiveAt() {
        line("DUP", "10", "20", "30", "2025-01-01 00:00:00", "2025-06-01 00:00:00", 1, 0);
        line("DUP", "40", "50", "60", "2025-06-01 00:00:00", null, 1, 0);

        assertEquals(3, service.evaluate("DUP", new BigDecimal("25"), d("2025-03-01 00:00:00")),
                "三月那刻生效的是旧版：25 落在 (20,30]");
        assertEquals(1, service.evaluate("DUP", new BigDecimal("25"), d("2025-09-01 00:00:00")),
                "九月那刻生效的是新版：25 落在 [0,40]");
        assertEquals(0, service.evaluate("NO-SUCH", new BigDecimal("25"), d("2025-09-01 00:00:00")),
                "代号查无此线：不给结论");
        assertEquals(0, service.evaluate("  ", new BigDecimal("25"), d("2025-09-01 00:00:00")));
        assertEquals(0, service.evaluate("DUP", null, d("2025-09-01 00:00:00")));
        assertEquals(0, service.evaluate("DUP", new BigDecimal("25"), null));
    }

    @Test
    void evaluateByCode_allVersionsEnded_givesNoConclusion() {
        line("OLD1", "10", "20", "30", "2023-01-01 00:00:00", "2023-12-31 00:00:00", 1, 0);
        line("OLD1", "40", "50", "60", "2024-01-01 00:00:00", "2024-12-31 00:00:00", 1, 0);
        assertEquals(0, service.evaluate("OLD1", new BigDecimal("25"), d("2025-08-01 00:00:00")),
                "几版都已交棒：不硬凑结论");
    }

    // ---- 空参数一律不给结论 ----

    @Test
    void nullArgsNeverConclude() {
        line("N", "10", "20", "30", "2025-01-01 00:00:00", null, 1, 0);
        Date at = d("2026-01-01 00:00:00");
        assertEquals(0, service.evaluateTop(null, at));
        assertEquals(0, service.evaluateTop(new BigDecimal("5"), null));
        assertTrue(service.listAvailable(null).isEmpty());
        assertEquals(0, service.countAvailable(null));
    }
}
