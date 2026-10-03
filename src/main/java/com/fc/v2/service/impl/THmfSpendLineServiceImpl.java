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
 * 按长期在场认，不因缺栏当它没有。已交棒（status=1）、已删的一律不进现行计算，
 * 档里留着只供翻。并列在场取让线顺位大的那条，顺位也同取代号靠后的，
 * 随机那一套不认，两次算给回两条不同的线即算错。一条也够不着时不硬凑结论：
 * 求值回 0、名单给空，不拿邻近那条的数凑一个。
 * 屏上名单（listAvailable）、逐条判定（usable）、角标条数（countAvailable）、
 * 按代号/按顺位求值（evaluate/evaluateTop）同一把尺同一回算，都走
 * {@link #usableAt} 这一道门，两处数字对得齐；取值范围之外的数
 * （负的、越过该线高界的）今年不收，缺栏越栏在回话里点明，不笼统一句带过。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THmfSpendLineServiceImpl implements ITHmfSpendLineService {

    /** 取值下界：负数（负支出额）今年不收，进来也不给档位。 */
    private static final BigDecimal INPUT_MIN = BigDecimal.ZERO;
    /** 取值上界：一条线的高界越过这个数，这条线今年起不来（高界越界不收）。 */
    private static final BigDecimal INPUT_MAX = new BigDecimal("100");

    /** 名单一页硬顶：超了截断，不许一次把整册扛到屏上。 */
    private static final int LIST_HARD_CAP = 200;

    /** 优先级降序；并列按 ruleCode 降序（确定性，两次算必须同一条线说话） */
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
        String now = ts(at);
        List<THmfSpendLine> out = new ArrayList<THmfSpendLine>();
        for (THmfSpendLine r : allRows()) {
            if (usableAt(r, now)) {
                out.add(r);
            }
        }
        out.sort(PRIORITY_THEN_CODE_DESC);
        if (out.size() > LIST_HARD_CAP) {
            out = new ArrayList<THmfSpendLine>(out.subList(0, LIST_HARD_CAP));
        }
        return out;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        THmfSpendLine rule = findRule(ruleCode.trim(), ts(at));
        if (rule == null) {
            // 该代号这一刻一条生效版本也够不着：不硬凑结论
            return 0;
        }
        if (!inputFits(input)) {
            // 负的、越过全局上界的不收
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
        THmfSpendLine top = avail.get(0);
        if (!inputFits(input)) {
            // 数不在取值范围（负的/越过全局上界），不硬凑档位
            return 0;
        }
        return levelOf(top, input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        THmfSpendLine r = this.hmfSpendLineMapper.selectById(id);
        return r != null && usableAt(r, ts(at));
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        String now = ts(at);
        int n = 0;
        for (THmfSpendLine r : allRows()) {
            if (usableAt(r, now)) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 同一把尺

    private List<THmfSpendLine> allRows() {
        List<THmfSpendLine> rows = this.hmfSpendLineMapper.selectList(new QueryWrapper<THmfSpendLine>()
                .eq("del_flag", 0));
        return rows == null ? new ArrayList<THmfSpendLine>() : rows;
    }

    /**
     * 唯一一道门：at 时刻这条线进不进现行计算。名单、角标、逐条、求值都照它裁，
     * 不许各抄一份边界（抄两份就有一份会在交棒当天、停用态上对不拢）。
     * 条件：未删、未交棒（status=1 不进现行）、启用之日够得着、交棒之日（不含）还没到、
     * 三条分界数至少有一条、且高界没越出取值范围。
     */
    private boolean usableAt(THmfSpendLine r, String now) {
        if (r == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() == 1) {
            // 已交棒（已停用）的那条不进现行计算，档里留着只供翻
            return false;
        }
        String s = r.getEffStart() == null ? null : ts(r.getEffStart());
        String e = r.getEffEnd() == null ? null : ts(r.getEffEnd());
        if (e != null && e.startsWith("0000-00-00")) {
            // 老库零日期与没填同看：按长期在场认
            e = null;
        }
        // 在场：启用之日够得着（没填按长远在场）、交棒之日还没到（不含交棒当天）
        boolean started = s == null || s.compareTo(now) <= 0;
        boolean notEnded = e == null || e.compareTo(now) > 0;
        if (!started || !notEnded) {
            return false;
        }
        BigDecimal top = topLimit(r);
        if (top == null) {
            // 三条分界数全空起不来
            return false;
        }
        return top.compareTo(INPUT_MAX) <= 0;
    }

    /**
     * 递进来的数进不进取值范围：负数不收，越过全局上界 {@value #INPUT_MAX} 不收，贴着上界照收。
     * 注意超过某条线自身高界但还在取值范围内的不算越界——那归该线的第 4 档，由 levelOf 判。
     */
    private boolean inputFits(BigDecimal input) {
        return input.compareTo(INPUT_MIN) >= 0 && input.compareTo(INPUT_MAX) <= 0;
    }

    /** 该线最高一档的界（高界→中界→低界依次取有值的那条），皆空回 null。 */
    private BigDecimal topLimit(THmfSpendLine r) {
        BigDecimal v = r.getTh3Max() != null ? r.getTh3Max()
                : r.getTh2Max() != null ? r.getTh2Max() : r.getTh1Max();
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 按代号定位 at 时刻生效的那一条版本：只认在场（{@link #usableAt} 同一道门）的，
     * 同代号几版并列取启用之日晚的那条；一条都不在场回 null，不拿没启用的兜底硬凑。
     */
    private THmfSpendLine findRule(String ruleCode, String now) {
        THmfSpendLine best = null;
        String bestStart = null;
        for (THmfSpendLine r : allRows()) {
            if (!ruleCode.equals(r.getRuleCode()) || !usableAt(r, now)) {
                continue;
            }
            String s = r.getEffStart() == null ? "" : ts(r.getEffStart());
            // 并列几版取启用之日晚的：头一回见记上，后到的启用之日不更早才换
            if (best == null || s.compareTo(bestStart) >= 0) {
                best = r;
                bestStart = s;
            }
        }
        return best;
    }

    /** 分档：贴着上限归该档（等于上限计入本层）；空界跳过，都够不着回第 4 档。 */
    private int levelOf(THmfSpendLine rule, BigDecimal input) {
        BigDecimal t1 = rule.getTh1Max();
        if (t1 != null && input.compareTo(t1) <= 0) {
            return 1;
        }
        BigDecimal t2 = rule.getTh2Max();
        if (t2 != null && input.compareTo(t2) <= 0) {
            return 2;
        }
        BigDecimal t3 = rule.getTh3Max();
        if (t3 != null && input.compareTo(t3) <= 0) {
            return 3;
        }
        return 4;
    }
}
