package com.fc.v2.hmf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.THmfAcctCard;
import com.fc.v2.model.auto.THmfArch;
import com.fc.v2.service.impl.THmfAcctCardServiceImpl;

/**
 * 分户账立户单口径逐条核对：底册归属只认查回的在册那一条（状态/代号/三线同源）、
 * 面积折档等于上限计入上一层（<90→1、90..<144→2、144..<300→3、≥300→4）、
 * 负数（含零）面积进门拦下不判档、已迁出/查无的底册立不下、立单号去重、
 * 新建与编辑同一把尺（缺栏照库存、改面积重折档、改到迁出底册改不成）、
 * 清单照调用方条件查不另塞 status=0。
 */
class AcctCardGradeTest {

    private THmfAcctCardServiceImpl service;
    private FakeAcctCardMapper cards;
    private FakeArchMapper arches;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new THmfAcctCardServiceImpl();
        this.cards = new FakeAcctCardMapper();
        this.arches = new FakeArchMapper();
        Field base = THmfAcctCardServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        base.setAccessible(true);
        base.set(service, cards);
        Field arch = THmfAcctCardServiceImpl.class.getDeclaredField("hmfArchMapper");
        arch.setAccessible(true);
        arch.set(service, arches);
    }

    private THmfArch arch(long ignored, String siteNo, int status) {
        THmfArch a = new THmfArch();
        a.setSiteNo(siteNo);
        a.setTh1Max(new BigDecimal("90"));
        a.setTh2Max(new BigDecimal("144"));
        a.setTh3Max(new BigDecimal("300"));
        a.setStatus(status);
        a.setDelFlag(0);
        arches.insert(a); // id 由内存 Mapper 分配
        return a;
    }

    private THmfAcctCard card(String billNo, long siteId, String qty) {
        THmfAcctCard c = new THmfAcctCard();
        c.setBillNo(billNo);
        c.setSiteId((int) siteId);
        if (qty != null) {
            c.setQty(new BigDecimal(qty));
        }
        return c;
    }

    @Test
    void gradeBoundaries_equalLimitCountsUpOneLayer() {
        long jz = arch(0, "JZ00", 0).getId();
        // 等于上限计入上一层（schema 钉死）：与支出额线"贴上限归该档"相反，各自自洽。
        int[] expect = {1, 2, 2, 3, 3, 4, 4};
        String[] qtys = {"89.99", "90", "100", "144", "200", "300", "300.01"};
        for (int i = 0; i < qtys.length; i++) {
            THmfAcctCard c = card("B" + i, jz, qtys[i]);
            assertEquals(1, service.insertTHmfAcctCard(c), qtys[i] + " 应立得下");
            assertEquals(expect[i], c.getGradeLevel(),
                    qtys[i] + " 应折到第 " + expect[i] + " 档");
        }
    }

    @Test
    void negativeOrZeroAreaRejected_noGradeMadeUp() {
        long jz = arch(0, "JZ00", 0).getId();
        for (String q : new String[]{"-1", "-0.01", "0"}) {
            int before = cards.all().size();
            assertEquals(0, service.insertTHmfAcctCard(card("N-" + q, jz, q)),
                    q + " 是负的（含零）面积，立单该拦下");
            assertEquals(before, cards.all().size(), q + " 不许落一条再硬判档");
        }
    }

    @Test
    void movedOutOrMissingArchRejected_siteNoFromArch() {
        long moved = arch(1, "JZ01", 1).getId(); // 已迁出
        assertEquals(0, service.insertTHmfAcctCard(card("M1", moved, "100")),
                "底册已迁出，端点归属钉不死，立不下");
        assertEquals(0, service.insertTHmfAcctCard(card("M2", 999999, "100")),
                "查无此底册，立不下");

        long jz = arch(0, "JZ00", 0).getId();
        THmfAcctCard c = card("OK", jz, "100");
        assertEquals(1, service.insertTHmfAcctCard(c));
        assertEquals("JZ00", c.getSiteNo(), "底册代号照查回的底册同源写出");
        assertEquals(0, c.getStatus(), "新立单缺省待核对");
    }

    @Test
    void missingAreaLeavesGradeBlank_notZero() {
        long jz = arch(0, "JZ00", 0).getId();
        THmfAcctCard c = card("BLANK", jz, null);
        assertEquals(1, service.insertTHmfAcctCard(c));
        assertNull(c.getGradeLevel(), "面积缺栏不硬折 0 档凑数");
    }

    @Test
    void duplicateBillNoRejected() {
        long jz = arch(0, "JZ00", 0).getId();
        assertEquals(1, service.insertTHmfAcctCard(card("DUP", jz, "100")));
        assertEquals(0, service.insertTHmfAcctCard(card("DUP", jz, "120")),
                "同立单号第二张立不下");
    }

    @Test
    void editUsesSameRuler_regradesAndGuards() {
        long jz = arch(0, "JZ00", 0).getId();
        long mv = arch(1, "JZ01", 1).getId();
        THmfAcctCard c = card("E1", jz, "100");
        service.insertTHmfAcctCard(c);
        assertEquals(2, c.getGradeLevel());

        // 只改面积：底册照库存，档照新面积重折——新建与编辑一处规矩
        THmfAcctCard chg = new THmfAcctCard();
        chg.setId(c.getId());
        chg.setQty(new BigDecimal("200"));
        assertEquals(1, service.updateTHmfAcctCard(chg));
        assertEquals(3, service.selectTHmfAcctCardById(c.getId()).getGradeLevel());
        assertEquals("JZ00", service.selectTHmfAcctCardById(c.getId()).getSiteNo());

        // 编辑递负数：照样拦下
        THmfAcctCard bad = new THmfAcctCard();
        bad.setId(c.getId());
        bad.setQty(new BigDecimal("-5"));
        assertEquals(0, service.updateTHmfAcctCard(bad));

        // 把归属改到已迁出的底册：改不成
        THmfAcctCard moved = new THmfAcctCard();
        moved.setId(c.getId());
        moved.setSiteId((int) mv);
        assertEquals(0, service.updateTHmfAcctCard(moved));
    }

    @Test
    void editDuplicateBillNoAcrossCardsRejected() {
        long jz = arch(0, "JZ00", 0).getId();
        THmfAcctCard a = card("A", jz, "100");
        THmfAcctCard b = card("B", jz, "100");
        service.insertTHmfAcctCard(a);
        service.insertTHmfAcctCard(b);
        THmfAcctCard steal = new THmfAcctCard();
        steal.setId(b.getId());
        steal.setBillNo("A");
        assertEquals(0, service.updateTHmfAcctCard(steal), "编辑把单号改成别人的，该拦下");
    }

    @Test
    void listRespectsCallerWrapper_noHiddenStatusFilter() {
        long jz = arch(0, "JZ00", 0).getId();
        THmfAcctCard a = card("L0", jz, "100");
        THmfAcctCard b = card("L1", jz, "100");
        service.insertTHmfAcctCard(a); // status 0
        service.insertTHmfAcctCard(b);
        b.setStatus(1); // 改成已核对（模拟"到分户底册把状态改一下"）
        cards.updateById(b);

        List<THmfAcctCard> all = service.selectTHmfAcctCardList(new QueryWrapper<THmfAcctCard>());
        assertEquals(2, all.size(), "无条件清单两状态都该露，不另塞 status=0");
        List<THmfAcctCard> checked = service.selectTHmfAcctCardList(
                new QueryWrapper<THmfAcctCard>().eq("status", 1));
        assertEquals(1, checked.size());
        assertEquals("L1", checked.get(0).getBillNo());
    }
}
