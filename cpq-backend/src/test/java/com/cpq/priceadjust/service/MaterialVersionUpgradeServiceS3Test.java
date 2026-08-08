package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B0 · S3a（driver 行 → snapshot_rows）+ S3b/S4b（row_data 手动行改新价/非手动行按本版价
 * 覆盖·删值必撤锁）+ S4a（quote_card_values.editRows 同口径覆盖）单元测试。
 *
 * <p>repair-0807（FR-1/FR-2）：S3a/S3b 写价的同时写 {@code __priceLocked}/{@code __priceVersion}
 * 锁标记；S4b 由「删键」改「用本版价覆盖」，解不出价时删值必须同时撤锁（AC-5，反模式 #47「只删值
 * 不删锁=死格」）。本文件在原有场景基础上补齐这套新口径的断言（原先"非 manual 行价格键被删除"的
 * 断言按新口径改为"被覆盖为新价"，属口径变更，非回归——需求文档 §7 风险 1）。
 *
 * <p>自建 quotation + line_item + component + component_data 全套测试数据，测后清理。
 */
@QuarkusTest
class MaterialVersionUpgradeServiceS3Test {

    @Inject
    MaterialVersionUpgradeService svc;
    @Inject
    EntityManager em;

    private UUID quotationId, lineItemId, componentId, versionId;

    @AfterEach
    @Transactional
    void cleanup() {
        if (versionId != null) {
            em.createNativeQuery("DELETE FROM element_price_version_item WHERE version_id=:id").setParameter("id", versionId).executeUpdate();
            em.createNativeQuery("DELETE FROM element_price_version WHERE id=:id").setParameter("id", versionId).executeUpdate();
        }
        if (lineItemId != null) {
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id=:id").setParameter("id", lineItemId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE id=:id").setParameter("id", lineItemId).executeUpdate();
        }
        if (quotationId != null) {
            em.createNativeQuery("DELETE FROM quotation WHERE id=:id").setParameter("id", quotationId).executeUpdate();
        }
        if (componentId != null) {
            em.createNativeQuery("DELETE FROM component WHERE id=:id").setParameter("id", componentId).executeUpdate();
        }
    }

    /**
     * 核心场景：一个组件的 snapshot_rows 含两行同为 Ag（不同材质），一行 Cu，一行无价元素 Ni；
     * row_data 含一条 _origin=manual 的 Ag 行、一条非 manual 的驱动行快照（不应被 S3b 动）。
     * 断言：① 两条 Ag 行都改，且带锁标记；② Cu 行改，且带锁标记；③ Ni（不在版本明细里）不动、
     * 无锁标记；④ 非价格键（材质/项次）不变；⑤ manual 行按元素值命中改价+带锁标记；
     * ⑥ 非 manual 的 row_data 条目按 FR-2 被【覆盖为新价】+带锁标记（不再是删除）；⑦ row_version 递增。
     */
    @Test
    @Transactional
    void upgradeComponentRows_multiRowSameElement_andManualRow() throws Exception {
        setUpFixture();

        Map<String, ElementPrice> versionPrices = new LinkedHashMap<>();
        versionPrices.put("Ag", new ElementPrice(new BigDecimal("999999.0000"), "EUR"));
        versionPrices.put("Cu", new ElementPrice(new BigDecimal("7777.0000"), "EUR"));
        // 故意不给 Ni 价格 —— 版本明细里没有它，验证"对不上就不动"

        // 直接调包内可见方法（同包）——注意：CDI @ApplicationScoped bean 注入的是客户端代理，
        // 代理只会正确委派非 private 方法；对 private 方法用反射 setAccessible 会绕过代理直接落到
        // 代理实例本身（其 @Inject 字段未初始化，em 为 null）。故 upgradeComponentRows /
        // RowUpdateOutcome 定为包内可见（非 private），与 S1/S2 的 loadVersionPrices /
        // locatePriceBearingComponents 同一模式，直接调用即可正确委派到真实 bean 实例。
        var pbc = new com.cpq.priceadjust.dto.UpgradeResult.PriceBearingComponent(
            componentId.toString(), "TEST-S3", "材料成本", "元素", "元素单价", "货币");

        MaterialVersionUpgradeService.RowUpdateOutcome outcome =
            svc.upgradeComponentRows(lineItemId, pbc, versionPrices, "V26080702");

        int rowsChanged = outcome.rowsChanged;
        boolean conflict = outcome.conflict;

        assertFalse(conflict, "首次写入不应冲突");
        // 2 条 Ag(snapshot_rows,S3a) + 1 条 Cu(snapshot_rows,S3a) + 1 条 manual Ag(row_data,S3b)
        // + 1 条非 manual Ag(row_data,S4b 覆盖) = 5
        assertEquals(5, rowsChanged,
            "两条 Ag(snapshot_rows,S3a) + 一条 Cu(snapshot_rows,S3a) + 一条 manual Ag(row_data,S3b 改新价)" +
            " + 一条非 manual Ag(row_data,S4b 用本版价覆盖)");

        // 重新读库校验
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT snapshot_rows, row_data, row_version FROM quotation_line_component_data " +
                "WHERE line_item_id=:lid AND component_id=:cid")
            .setParameter("lid", lineItemId).setParameter("cid", componentId)
            .getSingleResult();
        String snapshotRows = (String) row[0];
        String rowData = (String) row[1];
        long rowVersion = ((Number) row[2]).longValue();

        assertEquals(1L, rowVersion, "row_version 应从 0 递增到 1");

        com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();
        var snapArr = M.readTree(snapshotRows);
        int agHit = 0;
        for (var r : snapArr) {
            var dr = r.path("driverRow");
            String el = dr.path("元素").asText("");
            if ("Ag".equals(el)) {
                assertEquals(999999.0, dr.path("元素单价").asDouble(), 0.01, "Ag 行单价应被改写");
                assertEquals("EUR", dr.path("货币").asText(), "货币键应同步写入（element_currency_field=货币）");
                assertTrue(dr.path("__priceLocked").asBoolean(false), "repair-0807 FR-1：Ag 行应写 __priceLocked=true");
                assertEquals("V26080702", dr.path("__priceVersion").asText(), "repair-0807 FR-1：Ag 行 __priceVersion 应=目标版本号");
                agHit++;
            } else if ("Cu".equals(el)) {
                assertEquals(7777.0, dr.path("元素单价").asDouble(), 0.01, "Cu 行单价应被改写");
                assertTrue(dr.path("__priceLocked").asBoolean(false), "repair-0807 FR-1：Cu 行应写 __priceLocked=true");
                assertEquals("V26080702", dr.path("__priceVersion").asText());
            } else if ("Ni".equals(el)) {
                assertEquals(50.0, dr.path("元素单价").asDouble(), 0.01,
                    "Ni 不在版本明细里，单价应保持手工原值 50，不被清 0 或误改");
                assertFalse(dr.has("__priceLocked"), "Ni 不在版本明细里，不应被打锁标记");
            }
            // 非价格键必须逐字不变
            assertEquals("固定材质", dr.path("_材质").asText(), "非价格字段(材质)不应被 S3a 触碰");
        }
        assertEquals(2, agHit, "同一元素多行(BOM 闭包同元素不同材质) 必须逐行全改，不能只改第一条命中就停");

        var rowDataArr = M.readTree(rowData);
        boolean manualHit = false, nonManualHit = false;
        for (var r : rowDataArr) {
            if ("manual".equals(r.path("_origin").asText(""))) {
                // S3b：手动行按元素值命中，直接改新价 + 锁标记
                assertEquals("Ag", r.path("元素").asText());
                assertEquals(999999.0, r.path("元素单价").asDouble(), 0.01, "手动行按元素值命中应改价");
                assertTrue(r.path("__priceLocked").asBoolean(false), "repair-0807 FR-1：手动行应写 __priceLocked=true");
                assertEquals("V26080702", r.path("__priceVersion").asText());
                manualHit = true;
            } else {
                // repair-0807 FR-2：非 manual 行 = 驱动行 autosave 持久化的当前值，元素命中版本明细
                // 且解出价 → 价格键必须被【覆盖为新价】（不再是删除，根因 B 修复），元素字段本身
                // 不受影响（验收 #34），且带锁标记。
                assertEquals(999999.0, r.path("元素单价").asDouble(), 0.01,
                    "非 manual 的 row_data 条目命中版本明细后，价格键应被 S4b 覆盖为新价（FR-2，不再删除）");
                assertEquals("EUR", r.path("货币").asText());
                assertTrue(r.path("__priceLocked").asBoolean(false), "repair-0807 FR-1：非 manual 行也应写 __priceLocked=true");
                assertEquals("V26080702", r.path("__priceVersion").asText());
                assertEquals("Ag", r.path("元素").asText(), "S4b 不得清元素字段（验收 #34）");
                nonManualHit = true;
            }
        }
        assertTrue(manualHit, "manual 行必须被找到并处理");
        assertTrue(nonManualHit, "非 manual 行必须被找到并覆盖新价（S4b）");
    }

    /**
     * AC-5：元素∈本版明细但解不出价（{@code ep.price==null}）时，row_data 非手动行的价格键必须被
     * 【删除】，且 {@code __priceLocked}/{@code __priceVersion} 必须【一并被删】——只删值不删锁=
     * 死格（反模式 #47）。用手工构造的 versionPrices（price=null）直接调用 upgradeComponentRows
     * 验证，不依赖 loadVersionPrices 的 SQL 过滤（生产路径该方法会把 price IS NULL 的元素排除在
     * map 之外，此处以直调方式覆盖 FR-2 表格第三行的边界场景，见 backtask T2 表注）。
     */
    @Test
    @Transactional
    void upgradeComponentRows_noPriceElement_deletesValueAndUnlocksBothMarks() throws Exception {
        componentId = UUID.randomUUID();
        String fieldsJson = "[{\"name\":\"元素\",\"field_type\":\"INPUT_TEXT\"}]";
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, element_code_field, " +
                "element_price_field, element_currency_field) " +
                "VALUES (:id, 'S3-AC5测试组件', :code, CAST(:fields AS jsonb), '[]', '元素', '元素单价', '货币')")
            .setParameter("id", componentId).setParameter("code", "TEST-S3-AC5-" + componentId)
            .setParameter("fields", fieldsJson)
            .executeUpdate();

        UUID anyCustomerId = (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
        UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :no, :cust, 'S3-AC5测试单', :rep, 'DRAFT', now(), now())")
            .setParameter("id", quotationId).setParameter("no", "TEST-S3-AC5-" + quotationId)
            .setParameter("cust", anyCustomerId).setParameter("rep", anyUserId)
            .executeUpdate();

        lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, subtotal, created_at) " +
                "VALUES (:id, :qid, 0, now())")
            .setParameter("id", lineItemId).setParameter("qid", quotationId)
            .executeUpdate();
        // 非 manual 行，元素=Ni，带陈旧价格键 + 陈旧锁标记（模拟"上一版曾锁过"的死格前状态）。
        String rowDataJson = "[{\"元素\":\"Ni\",\"元素单价\":123.45,\"货币\":\"CNY\"," +
            "\"__priceLocked\":true,\"__priceVersion\":\"V26080601\",\"row_index\":0}]";
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, snapshot_rows, row_data, row_version, created_at) " +
                "VALUES (gen_random_uuid(), :lid, :cid, '[]'::jsonb, CAST(:rd AS jsonb), 0, now())")
            .setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("rd", rowDataJson)
            .executeUpdate();

        var pbc = new com.cpq.priceadjust.dto.UpgradeResult.PriceBearingComponent(
            componentId.toString(), "TEST-S3-AC5", "材料成本", "元素", "元素单价", "货币");
        Map<String, ElementPrice> versionPrices = new LinkedHashMap<>();
        versionPrices.put("Ni", new ElementPrice(null, null)); // 元素∈明细，但本版无价（current_price NULL）

        MaterialVersionUpgradeService.RowUpdateOutcome outcome =
            svc.upgradeComponentRows(lineItemId, pbc, versionPrices, "V26080702");
        assertFalse(outcome.conflict);
        assertEquals(1, outcome.rowsChanged);

        String rowData = (String) em.createNativeQuery(
                "SELECT row_data FROM quotation_line_component_data WHERE line_item_id=:lid AND component_id=:cid")
            .setParameter("lid", lineItemId).setParameter("cid", componentId).getSingleResult();
        JsonNode arr = new ObjectMapper().readTree(rowData);
        JsonNode niRow = arr.get(0);
        assertFalse(niRow.has("元素单价"), "AC-5：解不出价时价格键必须被删除");
        assertFalse(niRow.has("货币"), "AC-5：解不出价时货币键必须被删除");
        assertFalse(niRow.has("__priceLocked"), "AC-5：删值必须同时撤锁（__priceLocked），反模式 #47");
        assertFalse(niRow.has("__priceVersion"), "AC-5：删值必须同时撤锁（__priceVersion），反模式 #47");
        assertEquals("Ni", niRow.path("元素").asText(), "元素字段本身不得被清");
    }

    /**
     * D-9（代码评审 P0 修复，2026-08-07）：driverRow（snapshot_rows）侧同款场景——元素∈本版明细但
     * 解不出价（{@code ep.price==null}）时，driverRow 的价格键/货币键/{@code __priceLocked}/
     * {@code __priceVersion} 必须【四者全删】。
     *
     * <p>原实现的 bug：只有价格键的写受 {@code ep.price != null} 保护，货币键与两个锁标记在这层
     * 判断之外无条件执行——ep.price==null 时价格键保留【旧值】，却被打上【本次新版本】的
     * {@code __priceLocked=true} + {@code __priceVersion}，产出"旧价穿新版徽标"（根因 A 的镜像，
     * 比死格更危险：死格至少是空的，这个显示一个看起来权威的错数字）。且前端 priceLocked 判据
     * {@code driverRow?.__priceLocked ?? rawRow?.__priceLocked} 里 driverRow 命中 true 就短路，
     * S4b 在 row_data 侧撤的锁在渲染层完全不起作用——AC-5「该格可编辑」在 driver 行上因此不成立。
     *
     * <p>🚨 <b>要害断言在 {@code assertNotEquals(versionLabel, ...)}</b>：只验"锁没了"会漏掉
     * "徽标被刷新成新版号"这个形态——必须显式断言 {@code __priceVersion} 不存在（而不仅仅是"不等于
     * 旧版号"），否则一个"__priceVersion 被静默改写成新版号但 __priceLocked 被删"的半吊子修复
     * 也能骗过一个只查 {@code assertFalse(has(__priceLocked))} 的弱断言。
     */
    @Test
    @Transactional
    void upgradeComponentRows_driverRow_noPriceElement_deletesValueAndUnlocksBothMarks() throws Exception {
        componentId = UUID.randomUUID();
        String fieldsJson = "[{\"name\":\"元素\",\"field_type\":\"INPUT_TEXT\"}]";
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, element_code_field, " +
                "element_price_field, element_currency_field) " +
                "VALUES (:id, 'S3-D9测试组件', :code, CAST(:fields AS jsonb), '[]', '元素', '元素单价', '货币')")
            .setParameter("id", componentId).setParameter("code", "TEST-S3-D9-" + componentId)
            .setParameter("fields", fieldsJson)
            .executeUpdate();

        UUID anyCustomerId = (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
        UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :no, :cust, 'S3-D9测试单', :rep, 'DRAFT', now(), now())")
            .setParameter("id", quotationId).setParameter("no", "TEST-S3-D9-" + quotationId)
            .setParameter("cust", anyCustomerId).setParameter("rep", anyUserId)
            .executeUpdate();

        lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, subtotal, created_at) " +
                "VALUES (:id, :qid, 0, now())")
            .setParameter("id", lineItemId).setParameter("qid", quotationId)
            .executeUpdate();
        // driverRow 元素=Ni，带陈旧价格键 + 陈旧锁标记（模拟"上一版曾锁过"的死格前状态）——
        // 这正是复现 D-9 的关键：若 bug 未修，本行会被刷成「旧价 123.45 + 新版号 V26080702」。
        String snapshotRowsJson = "[{\"driverRow\":{\"元素\":\"Ni\",\"元素单价\":123.45,\"货币\":\"CNY\"," +
            "\"__priceLocked\":true,\"__priceVersion\":\"V26080601\"}}]";
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, snapshot_rows, row_data, row_version, created_at) " +
                "VALUES (gen_random_uuid(), :lid, :cid, CAST(:sr AS jsonb), '[]'::jsonb, 0, now())")
            .setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("sr", snapshotRowsJson)
            .executeUpdate();

        var pbc = new com.cpq.priceadjust.dto.UpgradeResult.PriceBearingComponent(
            componentId.toString(), "TEST-S3-D9", "材料成本", "元素", "元素单价", "货币");
        Map<String, ElementPrice> versionPrices = new LinkedHashMap<>();
        versionPrices.put("Ni", new ElementPrice(null, null)); // 元素∈明细，但本版无价（current_price NULL）
        String newVersionLabel = "V26080702";

        MaterialVersionUpgradeService.RowUpdateOutcome outcome =
            svc.upgradeComponentRows(lineItemId, pbc, versionPrices, newVersionLabel);
        assertFalse(outcome.conflict);
        assertEquals(1, outcome.rowsChanged);

        String snapshotRows = (String) em.createNativeQuery(
                "SELECT snapshot_rows FROM quotation_line_component_data WHERE line_item_id=:lid AND component_id=:cid")
            .setParameter("lid", lineItemId).setParameter("cid", componentId).getSingleResult();
        JsonNode arr = new ObjectMapper().readTree(snapshotRows);
        JsonNode driverRow = arr.get(0).path("driverRow");
        assertFalse(driverRow.has("元素单价"), "D-9：driverRow 解不出价时价格键必须被删除（不是保留旧值）");
        assertFalse(driverRow.has("货币"), "D-9：driverRow 解不出价时货币键必须被删除");
        assertFalse(driverRow.has("__priceLocked"), "D-9：driverRow 删值必须同时撤锁（__priceLocked）");
        // 🚨 要害断言：不是"__priceVersion 不等于新版号"这种弱断言，而是【键本身不存在】——
        // 否则一个"把 __priceVersion 悄悄留成旧版号"的半吊子修复也能通过弱断言。
        assertFalse(driverRow.has("__priceVersion"),
            "D-9 要害断言：__priceVersion 键必须不存在，不得被刷新成新版号 " + newVersionLabel
                + "（旧价配新徽标是根因 A 的镜像，比死格更危险）");
        assertEquals("Ni", driverRow.path("元素").asText(), "元素字段本身不得被清");
    }

    /**
     * AC-17（幂等）：连续两次对同一目标版本调用 {@code upgradeComponentRows}（同一 {@code versionPrices}
     * + 同一 {@code versionLabel}），第二次的 {@code snapshot_rows}/{@code row_data} 内容必须与第一次
     * 逐字节相等——覆盖式写入（FR-2）天然幂等，不应因重复升版产生漂移（{@code row_version} 递增不计入
     * "内容"比较范围，那是乐观锁机制的正常行为）。
     */
    @Test
    @Transactional
    void upgradeComponentRows_appliedTwice_isIdempotent() throws Exception {
        setUpFixture();
        Map<String, ElementPrice> versionPrices = new LinkedHashMap<>();
        versionPrices.put("Ag", new ElementPrice(new BigDecimal("999999.0000"), "EUR"));
        versionPrices.put("Cu", new ElementPrice(new BigDecimal("7777.0000"), "EUR"));
        var pbc = new com.cpq.priceadjust.dto.UpgradeResult.PriceBearingComponent(
            componentId.toString(), "TEST-S3", "材料成本", "元素", "元素单价", "货币");

        svc.upgradeComponentRows(lineItemId, pbc, versionPrices, "V26080702");
        Object[] firstRow = (Object[]) em.createNativeQuery(
                "SELECT snapshot_rows, row_data FROM quotation_line_component_data " +
                "WHERE line_item_id=:lid AND component_id=:cid")
            .setParameter("lid", lineItemId).setParameter("cid", componentId).getSingleResult();
        String firstSnapshot = (String) firstRow[0];
        String firstRowData = (String) firstRow[1];

        svc.upgradeComponentRows(lineItemId, pbc, versionPrices, "V26080702");
        Object[] secondRow = (Object[]) em.createNativeQuery(
                "SELECT snapshot_rows, row_data FROM quotation_line_component_data " +
                "WHERE line_item_id=:lid AND component_id=:cid")
            .setParameter("lid", lineItemId).setParameter("cid", componentId).getSingleResult();
        String secondSnapshot = (String) secondRow[0];
        String secondRowData = (String) secondRow[1];

        assertEquals(firstSnapshot, secondSnapshot, "AC-17：连续两次同版本升版，snapshot_rows 必须逐字节相等");
        assertEquals(firstRowData, secondRowData, "AC-17：连续两次同版本升版，row_data 必须逐字节相等");
    }

    /**
     * S4a：quote_card_values.editRows 里价格承载 tab 的价格键，元素命中版本明细且解出价 →
     * 【覆盖为新价】（repair-0807 FR-2，不再删除）；🔒 但同一 editRow 里若还手改过「毛重」，毛重
     * 必须原样保留；🔒 元素字段本身不得被清（验收 #34）；元素不在版本明细里的 editRow 不动。
     */
    @Test
    @Transactional
    void cleanEditRowOverrides_overridesPriceKey_keepsOtherManualEdits() {
        // 复用类字段 componentId/quotationId/lineItemId（而非局部变量）——这样现有 @AfterEach
        // 的清理逻辑能自动覆盖本测试新建的数据，不需要另写一套清理。
        componentId = UUID.randomUUID();
        String fieldsJson = "[{\"name\":\"元素\",\"field_type\":\"INPUT_TEXT\"}]";
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, element_code_field, " +
                "element_price_field, element_currency_field) " +
                "VALUES (:id, 'S4a测试组件', :code, CAST(:fields AS jsonb), '[]', '元素', '元素单价', NULL)")
            .setParameter("id", componentId).setParameter("code", "TEST-S4A-" + componentId)
            .setParameter("fields", fieldsJson)
            .executeUpdate();

        UUID anyCustomerId = (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
        UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :no, :cust, 'S4a测试单', :rep, 'DRAFT', now(), now())")
            .setParameter("id", quotationId).setParameter("no", "TEST-S4A-" + quotationId)
            .setParameter("cust", anyCustomerId).setParameter("rep", anyUserId)
            .executeUpdate();

        lineItemId = UUID.randomUUID();
        // quote_card_values：一个价格承载 tab，含 3 条 editRow ——
        //   row1：元素=Ag(在版本明细里)，手改过 元素单价 + 毛重 → 单价应被覆盖为新价，毛重应保留
        //   row2：元素=Foo(不在版本明细里) → 整条不动
        //   row3：没有元素字段（历史遗留/未合并过 row_data 的编辑）→ 对不上，不动
        String cardValuesJson = "{\"tabs\":[{\"componentId\":\"" + componentId + "\",\"componentCode\":\"TEST-S4A\"," +
            "\"tabName\":\"材料成本\",\"editRows\":[" +
            "{\"rowKey\":\"r1\",\"values\":{\"元素\":\"Ag\",\"元素单价\":999,\"毛重\":88.8}}," +
            "{\"rowKey\":\"r2\",\"values\":{\"元素\":\"Foo\",\"元素单价\":5}}," +
            "{\"rowKey\":\"r3\",\"values\":{\"损耗率\":1.2}}" +
            "]}]}";
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, subtotal, quote_card_values, created_at) " +
                "VALUES (:id, :qid, 0, CAST(:cv AS jsonb), now())")
            .setParameter("id", lineItemId).setParameter("qid", quotationId).setParameter("cv", cardValuesJson)
            .executeUpdate();

        com.cpq.quotation.entity.QuotationLineItem li =
            com.cpq.quotation.entity.QuotationLineItem.findById(lineItemId);
        assertNotNull(li);

        var pbc = new com.cpq.priceadjust.dto.UpgradeResult.PriceBearingComponent(
            componentId.toString(), "TEST-S4A", "材料成本", "元素", "元素单价", null);
        Map<String, ElementPrice> versionPrices = new LinkedHashMap<>();
        versionPrices.put("Ag", new ElementPrice(new BigDecimal("999999.0000"), "EUR"));
        // 注意：Foo 故意不给价，验证"元素不在版本明细里 → 不动"

        int cleaned = svc.cleanEditRowOverrides(li, List.of(pbc), versionPrices);
        assertEquals(1, cleaned, "只有 row1(元素=Ag，在版本明细里) 应被改动");

        JsonNode root;
        try {
            root = new ObjectMapper().readTree(li.quoteCardValues);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        JsonNode editRows = root.path("tabs").get(0).path("editRows");

        JsonNode r1 = findByRowKey(editRows, "r1");
        assertEquals(999999.0, r1.path("values").path("元素单价").asDouble(), 0.01,
            "repair-0807 FR-2：row1 单价键必须被覆盖为本版新价（不再删除）");
        assertEquals(88.8, r1.path("values").path("毛重").asDouble(), 0.01, "row1 毛重必须原样保留（验收 #29）");
        assertEquals("Ag", r1.path("values").path("元素").asText(), "row1 元素字段不得被清（验收 #34）");

        JsonNode r2 = findByRowKey(editRows, "r2");
        assertEquals(5.0, r2.path("values").path("元素单价").asDouble(), 0.01,
            "row2 元素=Foo 不在版本明细里，整条不动");

        JsonNode r3 = findByRowKey(editRows, "r3");
        assertEquals(1.2, r3.path("values").path("损耗率").asDouble(), 0.01, "row3 无元素字段，对不上不动");
    }

    /** S4a：元素∈明细但解不出价时，editRow 价格键仍走原「删键」分支（editRow.values 不承载锁标记）。 */
    @Test
    @Transactional
    void cleanEditRowOverrides_noPriceElement_stillRemovesKey() {
        componentId = UUID.randomUUID();
        String fieldsJson = "[{\"name\":\"元素\",\"field_type\":\"INPUT_TEXT\"}]";
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, element_code_field, " +
                "element_price_field, element_currency_field) " +
                "VALUES (:id, 'S4a-AC5测试组件', :code, CAST(:fields AS jsonb), '[]', '元素', '元素单价', NULL)")
            .setParameter("id", componentId).setParameter("code", "TEST-S4A-AC5-" + componentId)
            .setParameter("fields", fieldsJson)
            .executeUpdate();

        UUID anyCustomerId = (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
        UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :no, :cust, 'S4a-AC5测试单', :rep, 'DRAFT', now(), now())")
            .setParameter("id", quotationId).setParameter("no", "TEST-S4A-AC5-" + quotationId)
            .setParameter("cust", anyCustomerId).setParameter("rep", anyUserId)
            .executeUpdate();

        lineItemId = UUID.randomUUID();
        String cardValuesJson = "{\"tabs\":[{\"componentId\":\"" + componentId + "\",\"componentCode\":\"TEST-S4A-AC5\"," +
            "\"tabName\":\"材料成本\",\"editRows\":[" +
            "{\"rowKey\":\"r1\",\"values\":{\"元素\":\"Ni\",\"元素单价\":123.45}}" +
            "]}]}";
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, subtotal, quote_card_values, created_at) " +
                "VALUES (:id, :qid, 0, CAST(:cv AS jsonb), now())")
            .setParameter("id", lineItemId).setParameter("qid", quotationId).setParameter("cv", cardValuesJson)
            .executeUpdate();

        com.cpq.quotation.entity.QuotationLineItem li =
            com.cpq.quotation.entity.QuotationLineItem.findById(lineItemId);

        var pbc = new com.cpq.priceadjust.dto.UpgradeResult.PriceBearingComponent(
            componentId.toString(), "TEST-S4A-AC5", "材料成本", "元素", "元素单价", null);
        Map<String, ElementPrice> versionPrices = new LinkedHashMap<>();
        versionPrices.put("Ni", new ElementPrice(null, null)); // 元素∈明细但本版无价

        int cleaned = svc.cleanEditRowOverrides(li, List.of(pbc), versionPrices);
        assertEquals(1, cleaned);

        JsonNode root;
        try {
            root = new ObjectMapper().readTree(li.quoteCardValues);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        JsonNode r1 = findByRowKey(root.path("tabs").get(0).path("editRows"), "r1");
        assertFalse(r1.path("values").has("元素单价"), "解不出价时价格键仍应被删除");
    }

    private JsonNode findByRowKey(JsonNode editRows, String rowKey) {
        for (JsonNode er : editRows) {
            if (rowKey.equals(er.path("rowKey").asText())) return er;
        }
        throw new AssertionError("未找到 rowKey=" + rowKey);
    }

    @Transactional
    void setUpFixture() {
        componentId = UUID.randomUUID();
        // 🔒 fields 必须真的定义「元素」这个字段（S3a 靠 FormulaCalculator.resolveRowByFieldName
        // 按字段定义解析 driverRow，不是靠猜列名）。为聚焦测试"同元素多行"+"字段名解析"这两个行为，
        // 这里用最简单的 INPUT_TEXT 直接匹配（driverRow["元素"] 命中即返回，resolveRowByFieldName
        // 内部优先级第一档），不模拟真实生产 "_元素"(SQL 原始列) + BASIC_DATA default_source 那层间接
        // 寻址（那层已在 §S2 真实数据验证里跑通，此处只做单元级别的行为隔离测试）。
        String fieldsJson = "[{\"name\":\"元素\",\"field_type\":\"INPUT_TEXT\"}]";
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, element_code_field, " +
                "element_price_field, element_currency_field) " +
                "VALUES (:id, 'S3测试组件', :code, CAST(:fields AS jsonb), '[]', '元素', '元素单价', '货币')")
            .setParameter("id", componentId).setParameter("code", "TEST-S3-" + componentId)
            .setParameter("fields", fieldsJson)
            .executeUpdate();

        // customer_id/sales_rep_id 有 FK 约束，动态取库里已有的任意一条（不依赖硬编码 UUID，跨环境稳）
        UUID anyCustomerId = (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
        UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();

        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :no, :cust, 'S3测试单', :rep, 'DRAFT', now(), now())")
            .setParameter("id", quotationId).setParameter("no", "TEST-S3-" + quotationId)
            .setParameter("cust", anyCustomerId).setParameter("rep", anyUserId)
            .executeUpdate();

        lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, subtotal, created_at) " +
                "VALUES (:id, :qid, 0, now())")
            .setParameter("id", lineItemId).setParameter("qid", quotationId)
            .executeUpdate();

        String snapshotRowsJson =
            "[" +
            "{\"driverRow\":{\"元素\":\"Ag\",\"_材质\":\"固定材质\",\"元素单价\":50}},"  +
            "{\"driverRow\":{\"元素\":\"Cu\",\"_材质\":\"固定材质\",\"元素单价\":10}}," +
            "{\"driverRow\":{\"元素\":\"Ni\",\"_材质\":\"固定材质\",\"元素单价\":50}}," +
            "{\"driverRow\":{\"元素\":\"Ag\",\"_材质\":\"固定材质\",\"元素单价\":50}}" +
            "]";
        String rowDataJson =
            "[" +
            "{\"元素\":\"Ag\",\"材质\":\"固定材质\",\"元素单价\":118478,\"_origin\":\"manual\"}," +
            "{\"元素\":\"Ag\",\"材质\":\"固定材质\",\"元素单价\":118478,\"row_index\":0}" +
            "]";
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, snapshot_rows, row_data, row_version, created_at) " +
                "VALUES (gen_random_uuid(), :lid, :cid, CAST(:sr AS jsonb), CAST(:rd AS jsonb), 0, now())")
            .setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("sr", snapshotRowsJson).setParameter("rd", rowDataJson)
            .executeUpdate();
    }
}
