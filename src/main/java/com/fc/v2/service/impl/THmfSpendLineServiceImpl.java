package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
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
            return ca.compareTo(cb);
        }
    };

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
                .eq("del_flag", 0));
        List<THmfSpendLine> out = new ArrayList<THmfSpendLine>();
        if (rows == null) {
            return out;
        }
        String now = ts(at);
        for (THmfSpendLine r : rows) {
            String s = r.getEffStart() == null ? null : ts(r.getEffStart());
            String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
            if (e != null && e.startsWith("0000-00-00")) {
                // 老库零日期与没填同看：按长期在场认
                e = null;
            }
            // 在场：启用之日够得着（没填按长远在场）、交棒之日还没到（不含交棒当天）
            boolean started = s == null || s.compareTo(now) <= 0;
            boolean notEnded = e == null || e.compareTo(now) >= 0;
            if (!started || !notEnded) {
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
        if (r == null || r.getStatus() == null) {
            // 已交棒的那条不进现行计算
            return false;
        }
        String now = ts(at);
        String s = r.getEffStart() == null ? null : ts(r.getEffStart());
        String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
        if (e != null && e.startsWith("0000-00-00")) {
            e = null;
        }
        boolean started = s == null || s.compareTo(now) <= 0;
        boolean notEnded = e == null || e.compareTo(now) > 0;
        if (!started || !notEnded) {
            return false;
        }
        // 三条分界数全空起不来；高界越出取值范围的今年也不收
        if (r.getTh1Max() == null && r.getTh2Max() == null && r.getTh3Max() == null) {
            return false;
        }
        return resolveLimit(r, 1) != null || resolveLimit(r, 2) != null || resolveLimit(r, 3) != null;
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        // 与 listAvailable 同一把尺：角标条数与屏上名单同一回算出来，两处对得齐
        List<THmfSpendLine> rows = this.hmfSpendLineMapper.selectList(new QueryWrapper<THmfSpendLine>()
                .eq("del_flag", 0));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String now = ts(at);
        int n = 0;
        for (THmfSpendLine r : rows) {
            String s = r.getEffStart() == null ? null : ts(r.getEffStart());
            String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
            if (e != null && e.startsWith("0000-00-00")) {
                e = null;
            }
            boolean started = s == null || s.compareTo(now) <= 0;
            boolean notEnded = e == null || e.compareTo(now) > 0;
            if (!started || !notEnded) {
                continue;
            }
            n++;
        }
        return n;
    }

    /**
     * 按代号定位 at 时刻生效的那一条版本：只认在场（启用够得着、交棒还没到）的，
     * 同代号几版并列取启用之日晚的那条；一条都不在场时退而取第一条还没交棒的
     * （尚未启用的顶不上在场那条，只作兜底），连兜底也没有回 null。
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
            String s = r.getEffStart() == null ? null : ts(r.getEffStart());
            String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
            if (e != null && e.startsWith("0000-00-00")) {
                e = null;
            }
            boolean started = s == null || s.compareTo(now) <= 0;
            boolean notEnded = e == null || e.compareTo(now) > 0;
            if (!started || !notEnded) {
                continue;
            }
            if (best == null) {
                best = r;
                continue;
            }
            String bs = best.getEffStart() == null ? "" : ts(best.getEffStart());
            if ((s == null ? "" : s).compareTo(bs) >= 0) {
                continue;
            }
            best = r;
        }
        return best;
    }

    /** 阈值解析：档案为主、明细覆盖；两者皆缺返回 null（调用方按不可用处理），精度统一 2 位 */
    private BigDecimal resolveLimit(THmfSpendLine r, int tier) {
        if (r == null) {
            return null;
        }
        BigDecimal v = null;
        switch (tier) {
            case 1:
                v = r.getTh1Max();
                break;
            case 2:
                v = r.getTh2Max();
                break;
            case 3:
                v = r.getTh3Max();
                break;
            default:
                v = null;
        }
        if (v == null) {
            v = defaultThreshold(r, tier);
        }
        if (v == null) {
            return null;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 档案级默认阈值（明细未维护时的回退来源） */
    private BigDecimal defaultThreshold(THmfSpendLine r, int tier) {
        if (tier == 1) {
            return r.getTh1Max();
        }
        return r.getTh1Max();
    }

    /** 分档：等于上限取高一档 */
    private int levelOf(THmfSpendLine rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) < 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) < 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
