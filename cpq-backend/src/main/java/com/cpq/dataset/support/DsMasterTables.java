package com.cpq.dataset.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * api.md §8 {@code GET lookup/{masterType}} 的 masterType → 现有共享主数据表映射
 * （闸门 A0 · D-16「主数据仍共享，不拆」）。<b>只读</b>，本任务对这 5 张表零写入。
 *
 * <p>⚠️ <b>与 {@code MasterDataChecker} 分工</b>，不要混：
 * <ul>
 *   <li>{@code MasterDataChecker}（B-6，后端 #2）管<b>存在性校验</b>，只认 R-7 明列的四类
 *       （element / process / recipe / customer），刻意<b>不含</b> {@code material} ——
 *       料号指向本数据集自己的物料表，Phase 1 零写库时它还没落库，按 material_master 校验会误拒。
 *       保存端校验一律走它，🚫 不在本类另起一套（两套判定必然漂移）。</li>
 *   <li>本类只管<b>下拉候选</b>：需要额外的名称列，且需要 {@code material}（组成料号 / 投入料号要能搜）。</li>
 * </ul>
 */
public final class DsMasterTables {

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        DEFS.put("material", new Def("material_master", "material_no",  "material_name", null));
        DEFS.put("process",  new Def("process_master",  "process_no",   "process_name",  null));
        DEFS.put("element",  new Def("element",         "element_code", "element_name",  "status = 'ACTIVE'"));
        DEFS.put("recipe",   new Def("material_recipe", "code",         "name",          null));
        DEFS.put("customer", new Def("customer",        "code",         "name",          null));
    }

    private DsMasterTables() {}

    public static Def get(String masterType) {
        return masterType == null ? null : DEFS.get(masterType);
    }

    public record Def(String table, String codeColumn, String nameColumn, String extraFilter) {}
}
