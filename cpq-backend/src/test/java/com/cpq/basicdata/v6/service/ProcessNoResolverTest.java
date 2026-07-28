package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0727：{@link ProcessNoResolver} 纯函数式单测（不依赖 DB/CDI，{@link ProcessNoResolver.Index#of}
 * 直接从内存 {@link ProcessMaster} 列表构造）。
 */
class ProcessNoResolverTest {

    private final ProcessNoResolver resolver = new ProcessNoResolver();

    private static ProcessMaster pm(String no, String name) {
        ProcessMaster p = new ProcessMaster();
        p.processNo = no;
        p.processName = name;
        return p;
    }

    @Test
    void resolve_byProcessNo_hit() {
        ProcessNoResolver.Index idx = ProcessNoResolver.Index.of(List.of(pm("Z100", "焊接")));

        Optional<ProcessNoResolver.Resolved> r = resolver.resolve("Z100", idx);

        assertTrue(r.isPresent());
        assertEquals("Z100", r.get().processNo());
        assertEquals("焊接", r.get().processName());
    }

    @Test
    void resolve_byProcessName_uniqueHit() {
        ProcessNoResolver.Index idx = ProcessNoResolver.Index.of(List.of(pm("Z100", "焊接"), pm("Z200", "铆接")));

        Optional<ProcessNoResolver.Resolved> r = resolver.resolve("焊接", idx);

        assertTrue(r.isPresent());
        assertEquals("Z100", r.get().processNo());
        assertEquals("焊接", r.get().processName());
    }

    @Test
    void resolve_byProcessName_multipleCandidates_takesLowestProcessNo() {
        // 刻意乱序插入，验证结果不依赖列表输入顺序（排序键固定 process_no 升序）。
        ProcessNoResolver.Index idx = ProcessNoResolver.Index.of(
            List.of(pm("Z205", "焊接"), pm("Z100", "焊接")));

        Optional<ProcessNoResolver.Resolved> r = resolver.resolve("焊接", idx);

        assertTrue(r.isPresent());
        assertEquals("Z100", r.get().processNo(), "同名多条应取 process_no 升序第一条");
        assertEquals("焊接", r.get().processName());
    }

    @Test
    void resolve_notRegistered_returnsEmptyAndFailReasonMentionsRegistration() {
        ProcessNoResolver.Index idx = ProcessNoResolver.Index.of(List.of(pm("Z100", "焊接")));

        Optional<ProcessNoResolver.Resolved> r = resolver.resolve("点胶", idx);

        assertTrue(r.isEmpty());
        String reason = ProcessNoResolver.failReason("点胶");
        assertTrue(reason.contains("未在工序主数据中登记"), "失败原因文本须含固定提示语: " + reason);
        assertTrue(reason.contains("点胶"), "失败原因文本须含原始值: " + reason);
    }

    @Test
    void resolve_blankOrNullInput_returnsEmpty_noNpe() {
        ProcessNoResolver.Index idx = ProcessNoResolver.Index.of(List.of(pm("Z100", "焊接")));

        assertTrue(resolver.resolve(null, idx).isEmpty());
        assertTrue(resolver.resolve("   ", idx).isEmpty());
    }

    @Test
    void resolve_processNoTakesPriorityOverProcessName() {
        // 编号段先匹配：若原始值本身恰好等于另一条记录的 process_no，即使它也是某条记录的名称候选，
        // 也应先按编号命中（两段匹配顺序不能颠倒）。
        ProcessNoResolver.Index idx = ProcessNoResolver.Index.of(
            List.of(pm("Z100", "焊接"), pm("焊接", "编号即中文的极端用例")));

        Optional<ProcessNoResolver.Resolved> r = resolver.resolve("焊接", idx);

        assertTrue(r.isPresent());
        assertEquals("焊接", r.get().processNo(), "原始值先按 process_no 精确匹配命中");
        assertEquals("编号即中文的极端用例", r.get().processName());
    }
}
