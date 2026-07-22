package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 报价数据版本升级 · AC-1（延迟生效）核心机制自测。
 *
 * <p>{@code VersionedGroupSpec.pendingQuotationId} 是 B2「导入写 pending」的地基
 * ——各 {@code Q*Handler} 最终都调用 {@link VersionedV6Writer#writeVersionedGroup}/
 * {@code writeVersionedMasterDetail} 并传本单 id 作为 {@code pendingQuotationId}。
 * 走查确认：既有 {@code VersionedV6WriterTest}（通用写入器测试）未覆盖此参数非 null 的分支；
 * 本任务其余测试（{@code QuoteBackfillFlipRouteTest} 等）均用裸 SQL INSERT 模拟"已导入的
 * pending 行"，同样未验证写入器自身产出 pending 行的正确性。本文件补齐这一环——
 * 直接调用生产 API（而非模拟其结果），验证 AC-1 判定标准逐字：
 * 新行 {@code is_current=false}+{@code pending_quotation_id=本单}；旧组 {@code is_current} 不变；
 * {@code version_no}=旧值+1；{@code pending_supersedes} 指向旧 current 行 id。
 */
@QuarkusTest
class VersionedV6WriterPendingModeTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void pendingWrite_doesNotFlipOfficialGroup_versionBumpedAndSupersedesRecorded() {
        String materialNo = "TESTPEND" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID quotationId = UUID.randomUUID();

        Map<String, Object> groupKey = new LinkedHashMap<>();
        groupKey.put("system_type", "QUOTE");
        groupKey.put("customer_no", "_TESTCUST_");
        groupKey.put("price_type", "PROCESS");
        groupKey.put("code", materialNo);
        groupKey.put("finished_material_no", materialNo);

        List<String> contentColumns = List.of("seq_no", "pricing_price", "unit");

        // ① 首次写入(正式，pendingQuotationId=null)：官方 current 版本
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("seq_no", 1);
        row1.put("pricing_price", new BigDecimal("1.20"));
        row1.put("unit", "元");
        String v0 = writer.writeVersionedGroup(new VersionedGroupSpec(
            "unit_price", "version_no", groupKey, contentColumns, List.of(row1), null, null));

        UUID officialRowId = (UUID) em.createNativeQuery(
                "SELECT id FROM unit_price WHERE finished_material_no = :m AND is_current = true")
            .setParameter("m", materialNo).getSingleResult();

        // ② pending 写入(模拟本单导入)：内容变化(1.20→1.35)，pendingQuotationId 非空
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("seq_no", 1);
        row2.put("pricing_price", new BigDecimal("1.35"));
        row2.put("unit", "元");
        String v1 = writer.writeVersionedGroup(new VersionedGroupSpec(
            "unit_price", "version_no", groupKey, contentColumns, List.of(row2), null, quotationId));

        assertNotEquals(v0, v1, "pending 写入应产生新版本号");
        assertEquals(String.valueOf(Integer.parseInt(v0) + 1), v1, "版本号应为旧值+1");

        // AC-1 断言①：旧组 is_current 不变(未被翻转)
        Object[] oldRow = (Object[]) em.createNativeQuery(
                "SELECT is_current, version_no FROM unit_price WHERE id = :id")
            .setParameter("id", officialRowId).getSingleResult();
        assertEquals(Boolean.TRUE, oldRow[0], "AC-1：旧组 is_current 不应被本单 pending 写入翻转");
        assertEquals(v0, oldRow[1]);

        // AC-1 断言②：新行 is_current=false + pending_quotation_id=本单
        Object[] pendingRow = (Object[]) em.createNativeQuery(
                "SELECT is_current, pending_quotation_id, version_no, pricing_price FROM unit_price " +
                "WHERE finished_material_no = :m AND version_no = :v")
            .setParameter("m", materialNo).setParameter("v", v1).getSingleResult();
        assertEquals(Boolean.FALSE, pendingRow[0], "AC-1：pending 新行 is_current 必须为 false（延迟生效，非立即可见）");
        assertEquals(quotationId, pendingRow[1], "AC-1：pending 新行必须归属本单 pending_quotation_id");
        assertEquals(0, new BigDecimal("1.35").compareTo((BigDecimal) pendingRow[3]),
            "pending 行应落用户导入/编辑的最终值");

        // AC-1 断言③：pending_supersedes 指向被取代的官方 current 行
        @SuppressWarnings("unchecked")
        List<Object> supersedes = em.createNativeQuery(
                "SELECT unnest(pending_supersedes) FROM unit_price WHERE finished_material_no = :m AND version_no = :v")
            .setParameter("m", materialNo).setParameter("v", v1).getResultList();
        assertEquals(1, supersedes.size());
        assertEquals(officialRowId, supersedes.get(0), "pending_supersedes 应精确指向旧官方 current 行 id");

        // 全局唯一现行(is_current=true)行数应仍为 1（他单隔离：pending 不制造第二个"生效"行）
        long officialCurrentCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE finished_material_no = :m AND is_current = true")
            .setParameter("m", materialNo).getSingleResult()).longValue();
        assertEquals(1L, officialCurrentCount, "AC-3 前提：pending 写入不应产生第二个 is_current=true 行");
    }

    @Test
    @TestTransaction
    void pendingWrite_masterDetail_bothLevelsStayPendingWithoutFlippingOfficial() {
        String materialNo = "TESTPENDMD" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID quotationId = UUID.randomUUID();

        Map<String, Object> masterKey = new LinkedHashMap<>();
        masterKey.put("system_type", "QUOTE");
        masterKey.put("customer_no", "_TESTCUST_");
        masterKey.put("material_no", materialNo);
        Map<String, Object> masterFixed = Map.of("bom_type", "MATERIAL");

        List<String> childContentColumns = List.of("component_no", "composition_qty", "issue_unit", "seq_no");

        Map<String, Object> child1 = new LinkedHashMap<>();
        child1.put("component_no", "CHILD-OLD");
        child1.put("composition_qty", new BigDecimal("1.0"));
        child1.put("issue_unit", "EA");
        child1.put("seq_no", 1);

        String v0 = writer.writeVersionedMasterDetail(
            "material_bom", "bom_version", masterKey, masterFixed,
            "material_bom_item", "bom_version", masterKey, childContentColumns, List.of(child1));

        UUID officialMasterId = (UUID) em.createNativeQuery(
                "SELECT id FROM material_bom WHERE material_no = :m AND is_current = true")
            .setParameter("m", materialNo).getSingleResult();

        Map<String, Object> child2 = new LinkedHashMap<>();
        child2.put("component_no", "CHILD-NEW");
        child2.put("composition_qty", new BigDecimal("2.0"));
        child2.put("issue_unit", "EA");
        child2.put("seq_no", 1);

        String v1 = writer.writeVersionedMasterDetail(
            "material_bom", "bom_version", masterKey, masterFixed,
            "material_bom_item", "bom_version", masterKey, childContentColumns, List.of(child2),
            quotationId);

        assertNotEquals(v0, v1);

        // 单列 native 查询 Hibernate 返回裸标量(非 Object[])
        Boolean oldMaster = (Boolean) em.createNativeQuery(
                "SELECT is_current FROM material_bom WHERE id = :id")
            .setParameter("id", officialMasterId).getSingleResult();
        assertEquals(Boolean.TRUE, oldMaster, "AC-1 主子同步：主表官方 current 不应被 pending 写入翻转");

        Object[] pendingMaster = (Object[]) em.createNativeQuery(
                "SELECT is_current, pending_quotation_id FROM material_bom WHERE material_no = :m AND bom_version = :v")
            .setParameter("m", materialNo).setParameter("v", v1).getSingleResult();
        assertEquals(Boolean.FALSE, pendingMaster[0]);
        assertEquals(quotationId, pendingMaster[1]);

        Object[] pendingChild = (Object[]) em.createNativeQuery(
                "SELECT is_current, pending_quotation_id, component_no FROM material_bom_item " +
                "WHERE material_no = :m AND bom_version = :v")
            .setParameter("m", materialNo).setParameter("v", v1).getSingleResult();
        assertEquals(Boolean.FALSE, pendingChild[0], "AC-1 主子同步：子表 pending 行 is_current 应为 false");
        assertEquals(quotationId, pendingChild[1]);
        assertEquals("CHILD-NEW", pendingChild[2]);

        long officialChildCurrentCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom_item WHERE material_no = :m AND is_current = true")
            .setParameter("m", materialNo).getSingleResult()).longValue();
        assertEquals(1L, officialChildCurrentCount, "AC-14：子表官方 current 行数应保持为 1（未被 pending 写入破坏主子同步）");
    }
}
