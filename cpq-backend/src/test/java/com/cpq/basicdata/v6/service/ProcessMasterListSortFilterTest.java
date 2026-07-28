package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.ProcessMasterDTO;
import com.cpq.common.dto.PageResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0728 · B2/B3：{@code GET /v6/process-master} 排序 + 过滤，以及 {@code GET /v6/process-master/categories}。
 *
 * <p>自建 7 条 {@code ZT0728P*} 工序（连真实共享库，测完清理），覆盖：
 * <ul>
 *   <li>{@code is_outsource} 三态：true / false / <b>NULL</b>（NULL 行不归入任何一侧）；</li>
 *   <li>{@code process_category} 精确匹配 vs 子串（「制造」不得命中「制造二部」）、NULL 与空串；</li>
 *   <li>关键字 + 两个过滤条件叠加（验 JPQL 裸 OR 的括号问题）。</li>
 * </ul>
 */
@QuarkusTest
class ProcessMasterListSortFilterTest {

    @Inject ProcessMasterReadService service;
    @Inject EntityManager em;

    static final String KW = "zt0728p";     // 关键字匹配走 LOWER(...) LIKE

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM process_master WHERE process_no LIKE 'ZT0728P%'").executeUpdate();
    }

    @Transactional
    void seed() {
        // process_no / process_name / process_category / is_outsource / currency / unit / defect_rate
        insert("ZT0728P1", "钻孔", "制造", Boolean.FALSE, "CNY", "PCS", "0.0100");
        insert("ZT0728P2", "组装", "组装", Boolean.TRUE, "USD", "SET", "0.0200");
        insert("ZT0728P3", "包装", "制造二部", Boolean.FALSE, "CNY", "PCS", "0.0300");
        insert("ZT0728P4", "清洗", null, null, null, null, null);
        insert("ZT0728P5", "电镀", "制造", Boolean.TRUE, "EUR", "KG", "0.0400");
        insert("ZT0728P6", "阿萨", null, null, "CNY", "PCS", "0.0500");
        insert("ZT0728P7", "空分类", "", Boolean.FALSE, "CNY", "PCS", null);   // 空串分类：categories 必须排除
    }

    private void insert(String no, String name, String cat, Boolean outsource,
                        String currency, String unit, String defectRate) {
        em.createNativeQuery("INSERT INTO process_master(process_no, process_name, process_category,"
            + " is_outsource, standard_currency, standard_unit, default_defect_rate, created_at, updated_at)"
            + " VALUES (:no, :name, :cat, :out, :cur, :unit, CAST(:dr AS numeric), now(), now())")
            .setParameter("no", no).setParameter("name", name).setParameter("cat", cat)
            .setParameter("out", outsource).setParameter("cur", currency).setParameter("unit", unit)
            .setParameter("dr", defectRate)
            .executeUpdate();
    }

    @BeforeEach void before() { cleanup(); seed(); }
    @AfterEach  void after()  { cleanup(); }

    // ---------------------------------------------------------------- helpers

    private static List<String> nos(PageResult<ProcessMasterDTO> p) {
        return p.getContent().stream().map(d -> d.processNo).collect(Collectors.toList());
    }

    private PageResult<ProcessMasterDTO> q(String sortBy, String sortOrder, Boolean outsource, String cat) {
        return service.list(0, 50, KW, sortBy, sortOrder, outsource, cat);
    }

    // ------------------------------------------------------------------ 测试

    /** 不传新参数（含旧 3 参重载）→ 默认序 process_no ASC，与改造前一致。 */
    @Test
    void noNewParams_keepsDefaultOrder() {
        List<String> expected = List.of("ZT0728P1", "ZT0728P2", "ZT0728P3", "ZT0728P4",
                                        "ZT0728P5", "ZT0728P6", "ZT0728P7");
        assertEquals(expected, nos(service.list(0, 50, KW)), "旧 3 参重载行为改变");
        assertEquals(expected, nos(q(null, null, null, null)), "不传新参数时行为改变");
        assertEquals(7, service.list(0, 50, KW).getTotalElements());
    }

    /** sortBy=processName 升 / 降序（中文按数据库 collation 排，只断言升降互为逆序 + 稳定）。 */
    @Test
    void sortByProcessName_ascAndDesc() {
        List<String> asc = nos(q("processName", "asc", null, null));
        List<String> desc = nos(q("processName", "DESC", null, null));
        assertEquals(7, asc.size());
        assertEquals(reverse(asc), desc, "降序应是升序的逆序（无并列名称）");
        assertNotEquals(nos(q(null, null, null, null)), asc, "按名称排应不同于按编号排");
    }

    /** 跨页断言：page0 末行与 page1 首行满足排序关系（全表排序而非页内排序）。 */
    @Test
    void sortIsGlobal_acrossPages() {
        List<String> full = nos(service.list(0, 50, KW, "processName", "asc", null, null));
        PageResult<ProcessMasterDTO> p0 = service.list(0, 3, KW, "processName", "asc", null, null);
        PageResult<ProcessMasterDTO> p1 = service.list(1, 3, KW, "processName", "asc", null, null);
        assertEquals(7, p0.getTotalElements());
        assertEquals(full.subList(0, 3), nos(p0));
        assertEquals(full.subList(3, 6), nos(p1));
    }

    /** 非白名单 sortBy → 静默回退默认序，不抛异常、不注入。 */
    @Test
    void illegalSortBy_fallsBackToDefault() {
        List<String> baseline = nos(q(null, null, null, null));
        assertEquals(baseline, nos(q("nonExistingField", "asc", null, null)));
        assertEquals(baseline, nos(q("processNo; DROP TABLE process_master", "desc", null, null)));
        assertEquals(baseline, nos(q("", "desc", null, null)));
    }

    /** 可空列排序 NULLS LAST：升降序都把 process_category 为 NULL 的行放最后。 */
    @Test
    void sortByNullableColumn_nullsLastBothDirections() {
        for (String dir : List.of("asc", "desc")) {
            List<ProcessMasterDTO> rows = q("processCategory", dir, null, null).getContent();
            int firstNull = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).processCategory == null) { firstNull = i; break; }
            }
            assertTrue(firstNull >= 0, "样本里应有 NULL 分类行");
            for (int i = firstNull; i < rows.size(); i++) {
                assertNull(rows.get(i).processCategory,
                    dir + " 序 NULLS LAST 被破坏：NULL 之后又出现非空 @" + i);
            }
        }
    }

    /**
     * {@code isOutsource} 三态语义：{@code = :isOutsource} 而非 {@code IS NOT TRUE} ——
     * NULL 行既不算外协也不算自制，选「全部」时才出现。
     */
    @Test
    void isOutsourceFilter_nullRowsBelongToNeitherSide() {
        List<String> yes = nos(q("processNo", "asc", Boolean.TRUE, null));
        List<String> no = nos(q("processNo", "asc", Boolean.FALSE, null));
        assertEquals(List.of("ZT0728P2", "ZT0728P5"), yes);
        assertEquals(List.of("ZT0728P1", "ZT0728P3", "ZT0728P7"), no);

        long all = q(null, null, null, null).getTotalElements();
        long t = q(null, null, Boolean.TRUE, null).getTotalElements();
        long f = q(null, null, Boolean.FALSE, null).getTotalElements();
        assertEquals(7, all);
        assertEquals(2, t);
        assertEquals(3, f);
        assertTrue(t + f < all, "两侧之和应严格小于全部（差额 = is_outsource IS NULL 的 2 行）");
        assertEquals(all - t - f, 2, "NULL 行数对不上");
        // NULL 行显式不出现在任何一侧
        assertFalse(yes.contains("ZT0728P4") || yes.contains("ZT0728P6"));
        assertFalse(no.contains("ZT0728P4") || no.contains("ZT0728P6"));
    }

    /** processCategory 精确匹配：「制造」不得误命中「制造二部」。 */
    @Test
    void processCategoryFilter_isExactNotLike() {
        assertEquals(List.of("ZT0728P1", "ZT0728P5"), nos(q("processNo", "asc", null, "制造")));
        assertEquals(List.of("ZT0728P3"), nos(q("processNo", "asc", null, "制造二部")));
        assertEquals(List.of(), nos(q(null, null, null, "制")), "子串不该命中");
        assertEquals(2, q(null, null, null, "制造").getTotalElements(), "count 必须与分页同过滤");
        // 空白分类参数 = 不过滤
        assertEquals(7, q(null, null, null, "   ").getTotalElements());
    }

    /** 关键字 + isOutsource + processCategory 三条件叠加（JPQL 裸 OR 必须被括起来）。 */
    @Test
    void allFiltersCombined_areAnded() {
        PageResult<ProcessMasterDTO> r = service.list(0, 50, KW, "processNo", "asc", Boolean.FALSE, "制造");
        assertEquals(List.of("ZT0728P1"), nos(r));
        assertEquals(1, r.getTotalElements());

        // 关键字若被 OR 泄漏，这里会把所有 ZT0728P* 行都带回来
        PageResult<ProcessMasterDTO> r2 = service.list(0, 50, KW, null, null, Boolean.TRUE, "制造");
        assertEquals(List.of("ZT0728P5"), nos(r2));
        assertEquals(1, r2.getTotalElements());
    }

    /** 过滤 + 分页：count 与当页内容同源，不会出现「共 N 条」与实际行数对不上。 */
    @Test
    void countAndPage_stayInSync() {
        PageResult<ProcessMasterDTO> p = service.list(0, 2, KW, "processNo", "asc", Boolean.FALSE, null);
        assertEquals(3, p.getTotalElements());
        assertEquals(2, p.getContent().size());
        assertEquals(2, p.getTotalPages());
        assertEquals(1, service.list(1, 2, KW, "processNo", "asc", Boolean.FALSE, null).getContent().size());
    }

    /** B3：分类去重端点 —— 去重、排除 NULL / 空串、升序、返回 List（非 null）。 */
    @Test
    void listCategories_distinctSortedWithoutBlanks() {
        List<String> cats = service.listCategories();
        assertNotNull(cats);
        assertTrue(cats.containsAll(List.of("制造", "制造二部", "组装")), "缺自建分类: " + cats);
        assertFalse(cats.contains(""), "空串分类必须被排除");
        assertFalse(cats.contains(null), "NULL 分类必须被排除");
        assertEquals(cats.size(), cats.stream().distinct().count(), "分类未去重: " + cats);
        assertEquals(cats.stream().sorted().collect(Collectors.toList()), cats, "分类未按升序返回: " + cats);
    }

    private static List<String> reverse(List<String> in) {
        List<String> out = new java.util.ArrayList<>(in);
        java.util.Collections.reverse(out);
        return out;
    }
}
