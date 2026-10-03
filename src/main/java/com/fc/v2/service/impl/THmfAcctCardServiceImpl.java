package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.THmfAcctCardMapper;
import com.fc.v2.mapper.auto.THmfArchMapper;
import com.fc.v2.model.auto.THmfAcctCard;
import com.fc.v2.model.auto.THmfArch;
import com.fc.v2.service.ITHmfAcctCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 分户账立户单Service业务层处理
 *
 * 口径（新建与编辑同一把尺）：
 * 底册归属只认底册 id 查回来的那一条——在册状态、底册代号、三线上限同一处取，
 * 不查两遍、不各信一段；底册查无或已迁出（status=1）的单子立不下也改不成。
 * 面积折档照底册三线上限：等于上限计入上一层（<90→1，90..<144→2，144..<300→3，≥300→4）。
 * 新建与编辑都走 {@link #gradeOf} 同一处折档，递进来的面积为负（含零）本就该拦下，
 * 不许照样判出个档位；面积缺栏不硬折（档次留空），不拿 0 档凑。
 * 清单照调用方给的条件查，分页也由调用方统一起，不在服务层另塞一道 status=0、
 * 另起一页——不然改了状态回清单照样看不见。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class THmfAcctCardServiceImpl extends ServiceImpl<THmfAcctCardMapper, THmfAcctCard> implements ITHmfAcctCardService {

    /** 进展：待核对（新立单的缺省状态） */
    private static final int STATUS_PENDING = 0;
    /** 底册情形：已迁出 */
    private static final int ARCH_MOVED_OUT = 1;

    @Autowired
    private THmfArchMapper hmfArchMapper;

    @Override
    public THmfAcctCard selectTHmfAcctCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<THmfAcctCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<THmfAcctCard> selectTHmfAcctCardList(Wrapper<THmfAcctCard> queryWrapper) {
        // 条件与分页都听调用方的（控制器已统一 startPage），不在此另塞 status=0 把别状态的单藏掉。
        return this.baseMapper.selectList(queryWrapper);
    }

    @Override
    public int insertTHmfAcctCard(THmfAcctCard record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getBillNo());
        THmfArch refArch = activeArch(record.getSiteId());
        if (refArch == null) {
            // 底册查无 / 已迁出 / 已删：端点归属钉不死，这张单立不下
            return 0;
        }
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<THmfAcctCard>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        BigDecimal qty = record.getQty();
        if (qty != null && qty.compareTo(BigDecimal.ZERO) <= 0) {
            // 面积为负（含零）是进门就该拦下的数，不许照样折出个档位
            return 0;
        }
        // 底册代号照查回来的底册同源写出，三线上限也取自这一条
        record.setSiteNo(refArch.getSiteNo());
        record.setGradeLevel(gradeOf(refArch, qty));

        record.setStatus(STATUS_PENDING);
        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTHmfAcctCard(THmfAcctCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        THmfAcctCard stored = this.baseMapper.selectById(record.getId());
        if (stored == null || (stored.getDelFlag() != null && stored.getDelFlag() != 0)) {
            return 0;
        }

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<THmfAcctCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        // 归属与折档与新建同一把尺：底册改没改都只查这一回，缺传的栏照库存的补齐再判。
        Integer siteId = record.getSiteId() != null ? record.getSiteId() : stored.getSiteId();
        THmfArch refArch = activeArch(siteId);
        if (refArch == null) {
            return 0;
        }
        BigDecimal qty = record.getQty() != null ? record.getQty() : stored.getQty();
        if (qty != null && qty.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        record.setGradeLevel(gradeOf(refArch, qty));

        record.setUpdateTime(new Date());
        // 按主键落：MP 默认只更新非空字段，没递的栏照库存；del_flag 已在 stored 把关。
        return this.baseMapper.updateById(record);
    }

    @Override
    public int deleteTHmfAcctCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTHmfAcctCardById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 同一把尺

    /** 在册底册唯一读法：只认未删、未迁出的那一条，状态/代号/三线都从它出。 */
    private THmfArch activeArch(Integer siteId) {
        if (siteId == null) {
            return null;
        }
        THmfArch arch = this.hmfArchMapper.selectOne(new QueryWrapper<THmfArch>()
                .eq("id", siteId).eq("del_flag", 0));
        if (arch == null) {
            return null;
        }
        if (arch.getStatus() != null && arch.getStatus() == ARCH_MOVED_OUT) {
            return null;
        }
        return arch;
    }

    /**
     * 照底册三线上限折收费档次（新建、编辑共用这一处）：
     * 等于上限计入上一层（schema 钉死：90→2，144→3，300→4），故各层用严格小于判。
     * 面积缺栏回 null（不硬折 0 档）；某条上限缺栏视为够不着，顺着下一条界判。
     */
    private Integer gradeOf(THmfArch arch, BigDecimal qty) {
        if (arch == null || qty == null) {
            return null;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) < 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) < 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
