package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.entity.MaterialMaster;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 料号解析器（规则单一归处，决策 #1~#4/#10/#11）。
 *
 * <p>只负责「解析出料号号码」：料号有值直接返回（并登记 QUOTE 映射，通道②）；料号空+名称有值
 * 则按名称匹配料号表（同名取 material_no 升序第一条），匹配不到则经 {@link QuoteMaterialNoAllocator}
 * 铸造报价料号（{@code XXXX-YYMMNNNNNN}，取消 9 字头生成路径）；都空抛
 * {@link MaterialNoUnresolvableException}。<b>不写 material_master</b>（由调用方 upsert）。
 */
@ApplicationScoped
public class MaterialNoResolver {

    @Inject MaterialMasterRepository repo;
    @Inject QuoteMaterialNoAllocator allocator;

    /** 单次导入的事务级状态：调用方在 merge() 入口 new 一个，贯穿 §3/§12 两循环。 */
    public static final class BatchState {
        final Map<String, String> nameToNo = new HashMap<>();
        /** 发号所需：客户号 + 年月（YYMM），由各 handler 在 new BatchState 时注入。 */
        public String customerNo;
        public String yyMm;
    }

    /**
     * 解析最终落库料号。
     * @throws MaterialNoUnresolvableException 当料号与名称都为空（isBlank）
     */
    public String resolve(String materialNo, String materialName, BatchState state) {
        String no = trimToNull(materialNo);
        if (no != null) {
            allocator.ensureRegistered(state.customerNo, no);
            return no;
        }

        String name = trimToNull(materialName);
        if (name == null) {
            throw new MaterialNoUnresolvableException("料号与名称均为空，无法解析/生成料号");
        }

        String cached = state.nameToNo.get(name);
        if (cached != null) return cached;

        Optional<MaterialMaster> existing = repo.findFirstByMaterialName(name);
        if (existing.isPresent()) {
            String existingNo = existing.get().materialNo;
            state.nameToNo.put(name, existingNo);
            return existingNo;
        }

        String minted = allocator.mintAndRegister(state.customerNo, state.yyMm);
        state.nameToNo.put(name, minted);
        return minted;
    }

    /**
     * 仅匹配不生成（更新型 sheet 用，如 §5 元素回收折扣）。
     * 料号有值→trim 返回；料号空+名称有值→按名匹配料号表（含 {@link BatchState#nameToNo} 缓存），
     * 命中返回其料号，未命中返回 {@code null}；料号与名称都空→返回 {@code null}。**绝不生成 9 字头。**
     */
    public String resolveMatchOnly(String materialNo, String materialName, BatchState state) {
        String no = trimToNull(materialNo);
        if (no != null) return no;

        String name = trimToNull(materialName);
        if (name == null) return null;

        String cached = state.nameToNo.get(name);
        if (cached != null) return cached;

        Optional<MaterialMaster> existing = repo.findFirstByMaterialName(name);
        if (existing.isPresent()) {
            String existingNo = existing.get().materialNo;
            state.nameToNo.put(name, existingNo);
            return existingNo;
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
