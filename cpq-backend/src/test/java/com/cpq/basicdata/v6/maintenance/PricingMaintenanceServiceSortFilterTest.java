package com.cpq.basicdata.v6.maintenance;

import com.cpq.basicdata.v6.maintenance.dto.PartListPage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0728 · B1：{@code GET /pricing-basic-data/parts} 排序 + 配置状态过滤单测。
 *
 * <p>用 {@code ZT0728*} 前缀料号自建三个固定形态的数据点（连真实共享库，测完清理）：
 * <ul>
 *   <li><b>ZT0728A</b>：16 个版本组全配齐（{@code configuredCount == totalSheets}）+ 有主档（品名/规格/尺寸齐全）；</li>
 *   <li><b>ZT0728B</b>：1 个版本组 + 有主档；</li>
 *   <li><b>ZT0728C</b>：2 个版本组 + <b>无主档</b>（品名 NULL，用来验 NULLS LAST）。</li>
 * </ul>
 * 三者都能被关键字 {@code ZT0728} 命中，故「关键字 + configured」两条件同时生效的断言是确定性的：
 * 若 {@code kwClause} 的裸 OR 没加括号（{@code A OR B AND C} → {@code A OR (B AND C)}），
 * {@code configured=true} 会把 B/C 也放进来 —— 本测试的 {@code keywordAndConfigured_bothApply} 直接打死这个坑。
 */
@QuarkusTest
class PricingMaintenanceServiceSortFilterTest {

    @Inject PricingMaintenanceService service;
    @Inject PricingSheetRegistry registry;
    @Inject EntityManager em;

    static final String A = "ZT0728A";     // 配齐（16/16）
    static final String B = "ZT0728B";     // 未配齐（1/16）
    static final String C = "ZT0728C";     // 未配齐（2/16）、无主档
    static final String KW = "ZT0728";
    static final String PROC = "ZT0728P";

    // ------------------------------------------------------------------ 数据

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code LIKE 'ZT0728%' OR finished_material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM element_bom WHERE material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM capacity WHERE material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM labor_rate WHERE material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM production_energy WHERE material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM auxiliary_energy WHERE material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM tooling_cost WHERE material_no LIKE 'ZT0728%'").executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE 'ZT0728%'").executeUpdate();
    }

    @Transactional
    void seed() {
        // 主档：A/B 有，C 没有（C 的 material_name 恒 NULL → NULLS LAST 素材）
        em.createNativeQuery("INSERT INTO material_master(material_no, material_name, specification, dimension)"
            + " VALUES ('ZT0728A','ZT品名-Alpha','SPEC-A','DIM-1'), ('ZT0728B','ZT品名-Beta','SPEC-B','DIM-2')")
            .executeUpdate();

        // ---- A：8 个 FEE 版本组（unit_price）+ 8 个非 FEE 版本组 = 16/16 全配齐 ----
        for (String pt : List.of("CONSUMABLE", "PACKAGING", "SELF_PROCESS", "FINISHED_OTHER",
                                 "PLATING", "OUTSOURCE_PROCESS")) {
            em.createNativeQuery("INSERT INTO unit_price(system_type, price_type, version_no, code, is_current)"
                + " VALUES ('PRICING', :pt, '2000', :code, TRUE)")
                .setParameter("pt", pt).setParameter("code", A).executeUpdate();
        }
        // INCOMING_* 两组的锚点列是 finished_material_no（不是 code）
        for (String pt : List.of("INCOMING_PROCESS", "INCOMING_OTHER")) {
            em.createNativeQuery("INSERT INTO unit_price(system_type, price_type, version_no, code,"
                + " finished_material_no, is_current) VALUES ('PRICING', :pt, '2000', 'ZT0728A-IN', :fin, TRUE)")
                .setParameter("pt", pt).setParameter("fin", A).executeUpdate();
        }
        em.createNativeQuery("INSERT INTO material_bom(system_type, customer_no, bom_type, bom_version, material_no, is_current)"
            + " VALUES ('PRICING','_GLOBAL_','MATERIAL','2000', :m, TRUE)").setParameter("m", A).executeUpdate();
        em.createNativeQuery("INSERT INTO element_bom(system_type, customer_no, bom_type, material_no, characteristic, is_current)"
            + " VALUES ('PRICING','_GLOBAL_','MATERIAL', :m, 'RECIPE', TRUE)").setParameter("m", A).executeUpdate();
        em.createNativeQuery("INSERT INTO capacity(system_type, material_no, process_no, resource_group_no, production_type, calc_version, is_current)"
            + " VALUES ('PRICING', :m, :p, 'ZT-RG', 'UNIT', '2000', TRUE)")
            .setParameter("m", A).setParameter("p", PROC).executeUpdate();
        em.createNativeQuery("INSERT INTO labor_rate(system_type, material_no, version_no, process_no, standard_labor_rate, is_current)"
            + " VALUES ('PRICING', :m, '2000', :p, 1.0, TRUE)")
            .setParameter("m", A).setParameter("p", PROC).executeUpdate();
        for (String pt : List.of("DEPRECIATION", "ENERGY")) {
            em.createNativeQuery("INSERT INTO production_energy(system_type, price_type, material_no, process_no, calc_version, is_current)"
                + " VALUES ('PRICING', :pt, :m, :p, '2000', TRUE)")
                .setParameter("pt", pt).setParameter("m", A).setParameter("p", PROC).executeUpdate();
        }
        em.createNativeQuery("INSERT INTO auxiliary_energy(system_type, material_no, process_no, calc_version, is_current)"
            + " VALUES ('PRICING', :m, :p, '2000', TRUE)")
            .setParameter("m", A).setParameter("p", PROC).executeUpdate();
        em.createNativeQuery("INSERT INTO tooling_cost(system_type, material_no, process_no, seq_no, tooling_no, tooling_unit_price, calc_version, is_current)"
            + " VALUES ('PRICING', :m, :p, 1, 'ZT-T1', 1.0, '2000', TRUE)")
            .setParameter("m", A).setParameter("p", PROC).executeUpdate();

        // ---- B：1 组；C：2 组 ----
        em.createNativeQuery("INSERT INTO unit_price(system_type, price_type, version_no, code, is_current)"
            + " VALUES ('PRICING','CONSUMABLE','2000', :code, TRUE)").setParameter("code", B).executeUpdate();
        for (String pt : List.of("CONSUMABLE", "PACKAGING")) {
            em.createNativeQuery("INSERT INTO unit_price(system_type, price_type, version_no, code, is_current)"
                + " VALUES ('PRICING', :pt, '2000', :code, TRUE)")
                .setParameter("pt", pt).setParameter("code", C).executeUpdate();
        }
    }

    @BeforeEach void before() { cleanup(); seed(); }
    @AfterEach  void after()  { cleanup(); }

    // ---------------------------------------------------------------- helpers

    private static List<String> nos(PartListPage p) {
        return p.items.stream().map(i -> i.materialNo).collect(Collectors.toList());
    }

    private PartListPage kw(String sortBy, String sortOrder, Boolean configured) {
        return service.listParts(KW, 1, 50, sortBy, sortOrder, configured);
    }

    // ------------------------------------------------------------------ 测试

    /** 不传新参数 → 行为与改造前一致：默认序 = updatedAt DESC NULLS LAST, materialNo ASC。 */
    @Test
    void noNewParams_keepsDefaultOrder() {
        PartListPage p = service.listParts(null, 1, 200, null, null, null);
        assertTrue(p.total >= 3, "至少含本测试自建的 3 个料号，实际 total=" + p.total);

        List<PartListPage.PartListItem> items = p.items;
        for (int i = 1; i < items.size(); i++) {
            var prev = items.get(i - 1);
            var cur = items.get(i);
            if (prev.lastUpdatedAt == null) {
                assertNull(cur.lastUpdatedAt, "NULLS LAST 被破坏：非空排在了 NULL 之后 @" + i);
            }
            if (prev.lastUpdatedAt != null && cur.lastUpdatedAt != null) {
                int cmp = prev.lastUpdatedAt.compareTo(cur.lastUpdatedAt);
                assertTrue(cmp >= 0, "默认序应按 lastUpdatedAt 降序 @" + i);
                if (cmp == 0) {
                    assertTrue(prev.materialNo.compareTo(cur.materialNo) <= 0, "同值应按 materialNo 升序兜底 @" + i);
                }
            }
        }
        // 幂等：同参数两次调用结果一致
        assertEquals(nos(p), nos(service.listParts(null, 1, 200, null, null, null)));
    }

    /**
     * 铁律：不传新参数时 SQL 与改造前<b>逐字相同</b> —— 直接按字节比对 WHERE 子句拼装结果。
     * 「黄金串」是改造前 {@code kwClause} 的原文，任何多一个空格 / 多一层括号都会挂。
     */
    @Test
    void legacyWhereClause_isByteIdentical() {
        assertEquals("", PricingMaintenanceService.andWhere(List.of()),
            "无条件时不该产出 WHERE");
        assertEquals(" WHERE a.mno ILIKE :kw OR COALESCE(mm.material_name,'') ILIKE :kw",
            PricingMaintenanceService.andWhere(
                List.of("a.mno ILIKE :kw OR COALESCE(mm.material_name,'') ILIKE :kw")),
            "只有关键字条件时必须与改造前逐字相同");
        assertEquals(" WHERE (x = 1) AND (y = 2)",
            PricingMaintenanceService.andWhere(List.of("x = 1", "y = 2")),
            "多条件时每个 predicate 必须各自加括号");
    }

    /** sortBy=materialNo 升 / 降序。 */
    @Test
    void sortByMaterialNo_ascAndDesc() {
        List<String> asc = nos(kw("materialNo", "asc", null));
        assertEquals(List.of(A, B, C), asc);

        List<String> desc = nos(kw("materialNo", "DESC", null));   // 大小写不敏感
        assertEquals(List.of(C, B, A), desc);
    }

    /** 跨页断言：page1 末行与 page2 首行满足排序关系（证明是全表排序而非页内排序）。 */
    @Test
    void sortIsGlobal_acrossPages() {
        PartListPage p1 = service.listParts(KW, 1, 2, "materialNo", "asc", null);
        PartListPage p2 = service.listParts(KW, 2, 2, "materialNo", "asc", null);
        assertEquals(3, p1.total);
        assertEquals(3, p2.total);
        assertEquals(2, p1.items.size());
        assertEquals(1, p2.items.size());
        String lastOfP1 = p1.items.get(p1.items.size() - 1).materialNo;
        String firstOfP2 = p2.items.get(0).materialNo;
        assertTrue(lastOfP1.compareTo(firstOfP2) <= 0,
            "跨页排序断裂: page1 末=" + lastOfP1 + " > page2 首=" + firstOfP2);

        // 降序方向同样成立
        String lastDesc = service.listParts(KW, 1, 2, "materialNo", "desc", null).items.get(1).materialNo;
        String firstDesc = service.listParts(KW, 2, 2, "materialNo", "desc", null).items.get(0).materialNo;
        assertTrue(lastDesc.compareTo(firstDesc) >= 0);
    }

    /** 可空列排序一律 NULLS LAST：升降序都把「无主档 → 品名 NULL」的 C 放最后。 */
    @Test
    void sortByNullableColumn_nullsLastBothDirections() {
        assertEquals(C, kw("materialName", "asc", null).items.get(2).materialNo, "升序时 NULL 应在最后");
        assertEquals(C, kw("materialName", "desc", null).items.get(2).materialNo, "降序时 NULL 仍应在最后");
    }

    /** 非白名单 sortBy（含注入串）→ 静默忽略、回退默认序，且不抛异常。 */
    @Test
    void illegalSortBy_fallsBackToDefault() {
        List<String> baseline = nos(kw(null, null, null));
        assertEquals(baseline, nos(kw("unknownField", "asc", null)));
        assertEquals(baseline, nos(kw("a.mno; DROP TABLE unit_price", "desc", null)));
        assertEquals(baseline, nos(kw("", "asc", null)));
        // sortOrder 非法值按 asc 处理
        assertEquals(nos(kw("materialNo", "asc", null)), nos(kw("materialNo", "sideways", null)));
    }

    /** configured=true / false 的 total 之和 == 不传时 total（严格二分，不重不漏）。 */
    @Test
    void configuredFilter_partitionsTotal() {
        long all = service.listParts(null, 1, 1, null, null, null).total;
        long yes = service.listParts(null, 1, 1, null, null, Boolean.TRUE).total;
        long no = service.listParts(null, 1, 1, null, null, Boolean.FALSE).total;
        assertEquals(all, yes + no, "configured 二分不完备: all=" + all + " true=" + yes + " false=" + no);
        assertTrue(yes >= 1, "自建的 ZT0728A 应被算作已配齐");
    }

    /** 每一行都真的满足所声明的配置状态（count 与 page 用同一份 predicate 的间接证据）。 */
    @Test
    void configuredFilter_everyRowMatches() {
        PartListPage yes = service.listParts(null, 1, 200, null, null, Boolean.TRUE);
        for (var it : yes.items) {
            assertTrue(it.configuredCount >= it.totalSheets,
                "configured=true 混入未配齐行: " + it.materialNo + " " + it.configuredCount + "/" + it.totalSheets);
        }
        assertEquals(yes.total, yes.items.size(), "total 与实际行数不符（count/page predicate 不同步）");

        PartListPage no = service.listParts(null, 1, 200, null, null, Boolean.FALSE);
        for (var it : no.items) {
            assertTrue(it.configuredCount < it.totalSheets,
                "configured=false 混入已配齐行: " + it.materialNo);
        }
        assertEquals(no.total, no.items.size());
    }

    /**
     * <b>括号坑专项</b>（backtask §B1 步骤4）：关键字 + configured 两条件必须同时生效。
     *
     * <p>关键字条件是裸 {@code a.mno ILIKE :kw OR name ILIKE :kw}；若直接 AND 拼 configured 而不加括号，
     * SQL 会被解析成 {@code mno ILIKE kw OR (name ILIKE kw AND a.c >= n)} —— 三个 ZT0728* 料号
     * 的 mno 全部命中关键字，于是 {@code configured=true} 会错误返回 3 条。
     */
    @Test
    void keywordAndConfigured_bothApply() {
        PartListPage yes = kw(null, null, Boolean.TRUE);
        assertEquals(List.of(A), nos(yes), "关键字 + configured=true 应只剩已配齐的 A（否则就是 OR/AND 括号坑）");
        assertEquals(1, yes.total, "count 查询也必须带同一份过滤");

        PartListPage no = kw("materialNo", "asc", Boolean.FALSE);
        assertEquals(List.of(B, C), nos(no));
        assertEquals(2, no.total);

        // 关键字命中「品名」一侧时同样成立：ZT品名- 只命中 A / B（C 无主档）
        PartListPage byName = service.listParts("ZT品名-", 1, 50, "materialNo", "asc", null);
        assertEquals(List.of(A, B), nos(byName));
        assertEquals(List.of(A), nos(service.listParts("ZT品名-", 1, 50, null, null, Boolean.TRUE)));
        assertEquals(List.of(B), nos(service.listParts("ZT品名-", 1, 50, null, null, Boolean.FALSE)));
    }

    /** totalSheets 与 registry 同源（configured 阈值不能写死数字）。 */
    @Test
    void totalSheets_comesFromRegistry() {
        var items = kw("materialNo", "asc", null).items;
        int expected = registry.all().size();
        for (var it : items) assertEquals(expected, it.totalSheets);
        assertEquals(expected, items.get(0).configuredCount, "A 应恰好配齐 " + expected + " 组");
    }

    /** 各排序字段都能跑通（白名单 6 项全覆盖），且不影响 total。 */
    @Test
    void allWhitelistedSortFields_work() {
        long total = kw(null, null, null).total;
        List<String> fields = new ArrayList<>(
            List.of("materialName", "materialNo", "specification", "dimension", "configuredCount", "lastUpdatedAt"));
        for (String f : fields) {
            for (String dir : List.of("asc", "desc")) {
                PartListPage p = kw(f, dir, null);
                assertEquals(total, p.total, "排序不应改变 total: " + f + "/" + dir);
                assertEquals(3, p.items.size(), f + "/" + dir);
            }
        }
        // configuredCount 降序：A(16) 必在最前
        assertEquals(A, kw("configuredCount", "desc", null).items.get(0).materialNo);
        assertEquals(A, kw("configuredCount", "asc", null).items.get(2).materialNo);
        // 稳定次序键：configuredCount 相同的 B(1)/C(2) 不会乱序
        List<String> byCount = nos(kw("configuredCount", "asc", null));
        assertEquals(List.of(B, C, A), byCount);
    }

    /** 排序结果确实单调（以 specification 为例，含 NULL 行）。 */
    @Test
    void sortedResultIsMonotonic() {
        List<String> asc = kw("specification", "asc", null).items.stream()
            .map(i -> i.specification).collect(Collectors.toList());
        List<String> nonNull = asc.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList());
        List<String> sorted = nonNull.stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
        assertEquals(sorted, nonNull, "specification 升序不单调: " + asc);
        assertNull(asc.get(asc.size() - 1), "NULL 应排最后");
    }
}
