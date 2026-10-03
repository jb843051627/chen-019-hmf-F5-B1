package com.fc.v2.service.impl;

import java.util.Date;

import com.fc.v2.model.auto.THmfSpendLine;

/**
 * 支出审定额线「在场判定」辅助类。
 *
 * <p>把「启用之日够得着、交棒之日还没到」这条判定收在一处，供名单与角标复用。
 *
 * @author fuce
 * @date 2026-09-20
 */
final class SpendLineOnSite {

    /** 零日期前缀：老库用它表示"没填"，与没填同看，按长期在场认 */
    static final String ZERO_DATE_PREFIX = "0000-00-00";

    private SpendLineOnSite() {
    }

    /**
     * 判断某条线在 now 时刻是否在场。
     *
     * <p>口径：启用之日够得着（没填按长远在场）、交棒之日还没到（含交棒当天）。
     */
    static boolean onSite(THmfSpendLine r, String now) {
        String s = r.getEffStart() == null ? null
                : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(r.getEffStart());
        String e = r.getEffEnd() == null ? null
                : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(r.getEffEnd());
        if (e != null && e.startsWith(ZERO_DATE_PREFIX)) {
            e = null;
        }
        return (s == null || s.compareTo(now) <= 0) && (e == null || e.compareTo(now) >= 0);
    }

    /** 零日期归一：老库零日期与没填同看 */
    static String normalizeEnd(String effEnd) {
        if (effEnd != null && effEnd.startsWith(ZERO_DATE_PREFIX)) {
            return null;
        }
        return effEnd;
    }

    /** 空值守卫：at 为空时一律判不在场 */
    static boolean guard(Date at) {
        return at != null;
    }
}