package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THmfSpendLineMapper;
import com.fc.v2.model.auto.THmfSpendLine;
import com.fc.v2.service.ITHmfSpendLineService;

/**
 * 支出审定额线 Service业务层处理（rule-eval 形状）
 *
 * 口径（今年钉死）：
 * 在场只认一条——启用之日够得着、交棒之日（不含）还没到；存量老账没填交棒之日的
 * 按长期在场认，不因缺栏当它没有。并列在场取让线顺位大的那条，顺位也同取代号靠后的，
 * 随机那一套不认，两次算给回两条不同的线即算错。一条也够不着时不硬凑结论：
 * 求值回 0、名单给空，不拿邻近那条的数凑一个。已交棒（status=1）的不进现行计算，
 * 档里留着只供翻。屏上名单（listAvailable）、逐条判定（usable）、角标条数
 * （countAvailable）同一把尺同一回算，两处数字对得齐；取值范围之外的数
 * （负的、越过上限的）今年不收，缺栏越栏在回话里点明，不笼统一句带过。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THmfSpendLineServiceImpl implements ITHmfSpendLineService {

    /** 优先级降序；并列按 ruleCode 降序（代号靠后的先说话，两次算给回同一条） */
    private static final Comparator<THmfSpendLine> PRIORITY_THEN_CODE_DESC = new Comparator<THmfSpendLine>() {
        @Override
        public int compare(THmfSpendLine a, THmfSpendLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    /** 取值范围下限（含）：负数今年不收 */
    private static final BigDecimal RANGE_MIN = BigDecimal.ZERO;

    /** 取值范围上限（含）：越过上限的今年不收，高界越出此线的规则整条起不来 */
    private static final BigDecimal RANGE_MAX = new BigDecimal("100");

    @javax.annotation.Resource
    private THmfSpendLineMapper hmfSpendLineMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public THmfSpendLine selectTHmfSpendLineById(Long id) {
        return this.hmfSpendLineMapper.selectById(id);
    }

    @Override
    public List<THmfSpendLine> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<THmfSpendLine>();
        }
        // 已交棒（status=1）不进现行名单，档里留着只供翻
        List<THmfSpendLine> rows = this.hmfSpendLineMapper.selectList(new QueryWrapper<THmfSpendLine>()
                .eq("del_flag", 0).eq("status", 0));
        List<THmfSpendLine> out = new ArrayList<THmfSpendLine>();
        if (rows == null) {
            return out;
        }
        String now = ts(at);
        for (THmfSpendLine r : rows) {
            // 在场判定收在 SpendLineOnSite 一处：名单、角标、逐条、定位同一把尺
            if (!SpendLineOnSite.onSite(r, now)) {
                continue;
            }
            out.add(r);
        }
        if (out.size() > 200) {
            // 一页硬顶：超了截断，不许一次把整册扛到屏上
            out = new ArrayList<THmfSpendLine>(out.subList(0, 200));
        }
        out.sort(PRIORITY_THEN_CODE_DESC);
        return out;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        THmfSpendLine rule = findRule(ruleCode, at);
        if (rule == null) {
            // 该代号这一刻一条生效版本也够不着：不硬凑结论
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        List<THmfSpendLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            // 一条也够不着：不硬凑结论，不拿邻近那条的数凑
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        THmfSpendLine r = this.hmfSpendLineMapper.selectById(id);
        if (r == null || r.getStatus() == null || r.getStatus() != 0) {
            // 已交棒的那条不进现行计算
            return false;
        }
        // 与名单同一把尺：在场判定走同一处
        if (!SpendLineOnSite.onSite(r, ts(at))) {
            return false;
        }
        // 三条分界数全空起不来；有界越出取值范围的今年也不收
        BigDecimal highest = null;
        if (r.getTh1Max() != null && (highest == null || r.getTh1Max().compareTo(highest) > 0)) {
            highest = r.getTh1Max();
        }
        if (r.getTh2Max() != null && (highest == null || r.getTh2Max().compareTo(highest) > 0)) {
            highest = r.getTh2Max();
        }
        if (r.getTh3Max() != null && (highest == null || r.getTh3Max().compareTo(highest) > 0)) {
            highest = r.getTh3Max();
        }
        if (highest == null) {
            return false;
        }
        return highest.compareTo(RANGE_MAX) <= 0;
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        // 与 listAvailable 同一把尺：角标条数与屏上名单同一回算出来，两处对得齐
        List<THmfSpendLine> rows = this.hmfSpendLineMapper.selectList(new QueryWrapper<THmfSpendLine>()
                .eq("del_flag", 0).eq("status", 0));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String now = ts(at);
        int n = 0;
        for (THmfSpendLine r : rows) {
            if (!SpendLineOnSite.onSite(r, now)) {
                continue;
            }
            n++;
        }
        return n;
    }

    /**
     * 按代号定位 at 时刻生效的那一条版本：只认在场（启用够得着、交棒还没到）的，
     * 同代号几版并列取启用之日晚的那条；一条都不在场时回 null——不拿尚未启用
     * 或已交棒的凑数，够不着就不硬凑结论。
     */
    private THmfSpendLine findRule(String ruleCode, Date at) {
        List<THmfSpendLine> rows = this.hmfSpendLineMapper.selectList(new QueryWrapper<THmfSpendLine>()
                .eq("del_flag", 0).eq("rule_code", ruleCode).eq("status", 0));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        String now = ts(at);
        THmfSpendLine best = null;
        for (THmfSpendLine r : rows) {
            if (!SpendLineOnSite.onSite(r, now)) {
                continue;
            }
            if (best == null) {
                best = r;
                continue;
            }
            String bs = best.getEffStart() == null ? "" : ts(best.getEffStart());
            if ((r.getEffStart() == null ? "" : ts(r.getEffStart())).compareTo(bs) <= 0) {
                continue;
            }
            best = r;
        }
        return best;
    }

    /**
     * 分档：贴着上限归该档（等于上限不往高里抬）；取值范围 [0, 100] 之外的数
     * 今年不收，回 0 不给结论——按代号求值与多规则求值走同一道门。
     */
    private int levelOf(THmfSpendLine rule, BigDecimal input) {
        if (input.compareTo(RANGE_MIN) < 0 || input.compareTo(RANGE_MAX) > 0) {
            return 0;
        }
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
