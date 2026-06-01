package com.cpq.quotation;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 对账门禁：快照值 vs 真实内容（Task 8 — 验证值而非结构）。
 *
 * <p>删掉了原来只验结构的三个空断言（tabs 数 / rows 格式 / 幂等 tabs 数相同），
 * 换成真比值：
 * <ul>
 *   <li>T1: quote_card_values.tabs[0].baseRows 非空且含 driverRow + basicDataValues 键</li>
 *   <li>T2: quote_card_values 与 snapshot_rows 一致（同一批 expand，baseRows[0].basicDataValues 逐 path 全等）</li>
 *   <li>T3: quote_excel_values.rows[0] 至少含一个非 null 值（ExcelViewService 结果非空）</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SnapshotReconcileTest — Phase 1 值对账门禁")
public class SnapshotReconcileTest {

    @Inject
    CardSnapshotService svc;

    @Inject
    EntityManager em;

    static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 找一条满足条件的报价行：
     * - product_part_no_snapshot 非空
     * - customer_template_id 非空且 components_snapshot 非空 + status=PUBLISHED
     * - 对应报价模板有 driver 组件（data_driver_path 非空）→ 确保 snapshot_rows 非空
     */
    private UUID resolveTestLineItemId() {
        // 必须选「已有非空 snapshot_rows 的行」——「模板配了 driver 组件」不等于「该料号有真实展开数据」
        // （例: 料号 3120012580 配了 driver 但基础数据 expand 0 行）。buildCardValues 读 snapshot_rows
        // 组装 baseRows, 只有 snapshot_rows 非空才能真正验证组装逻辑。
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT li.id FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "JOIN template t1 ON t1.id = q.customer_template_id " +
            "WHERE t1.components_snapshot IS NOT NULL " +
            "  AND EXISTS (" +
            "    SELECT 1 FROM quotation_line_component_data d " +
            "    WHERE d.line_item_id = li.id " +
            "      AND d.snapshot_rows IS NOT NULL " +
            "      AND jsonb_typeof(d.snapshot_rows) = 'array' " +
            "      AND jsonb_array_length(d.snapshot_rows) > 0) " +
            "LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @BeforeEach
    @Transactional
    void clearColumns() {
        em.createNativeQuery(
            "UPDATE quotation_line_item SET quote_card_values=NULL, quote_excel_values=NULL, " +
            "costing_card_values=NULL, costing_excel_values=NULL, card_snapshot_at=NULL " +
            "WHERE id IN (" +
            "  SELECT li.id FROM quotation_line_item li " +
            "  JOIN quotation q ON q.id = li.quotation_id " +
            "  JOIN template t1 ON t1.id = q.customer_template_id " +
            "  WHERE li.product_part_no_snapshot IS NOT NULL " +
            "    AND t1.components_snapshot IS NOT NULL AND t1.status='PUBLISHED' LIMIT 5)"
        ).executeUpdate();
    }

    // -----------------------------------------------------------------------
    // T1: quote_card_values.tabs[0].baseRows 非空，含 driverRow + basicDataValues 键
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("T1: quote_card_values.tabs 中至少一个 tab 有非空 baseRows（真实展开值）")
    void quoteCardSnapshot_baseRowsNonEmpty() throws Exception {
        UUID lineId = resolveTestLineItemId();
        Assumptions.assumeTrue(lineId != null, "需要已有 driver 组件的产品行");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li);

        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT quote_card_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();

        assertFalse(rows.isEmpty(), "应有查询结果");
        String qcv = rows.get(0) != null ? rows.get(0).toString() : null;
        assertNotNull(qcv, "quote_card_values 必须已写入");

        JsonNode json = MAPPER.readTree(qcv);
        JsonNode tabs = json.path("tabs");
        assertTrue(tabs.isArray() && tabs.size() > 0, "tabs 必须非空数组");

        // 找第一个有 baseRows 的 tab
        boolean foundNonEmptyTab = false;
        for (JsonNode tab : tabs) {
            JsonNode baseRows = tab.path("baseRows");
            if (baseRows.isArray() && baseRows.size() > 0) {
                foundNonEmptyTab = true;
                // 验证每行有 driverRow 和 basicDataValues 键
                JsonNode firstRow = baseRows.get(0);
                assertTrue(firstRow.has("driverRow"),
                    "baseRows[0] 必须含 driverRow 键，实际内容: " + firstRow);
                assertTrue(firstRow.has("basicDataValues"),
                    "baseRows[0] 必须含 basicDataValues 键，实际内容: " + firstRow);
                break;
            }
        }
        assertTrue(foundNonEmptyTab,
            "至少一个 tab 的 baseRows 必须非空（driver 组件必须有 expand 结果）。" +
            "quote_card_values=" + qcv);
    }

    // -----------------------------------------------------------------------
    // T2: baseRows[].basicDataValues 与 snapshot_rows 一致（同批 expand）
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("T2: quote_card_values.baseRows 与 snapshot_rows 值一致（同一批 expand）")
    void quoteCardSnapshot_baseRowsMatchSnapshotRows() throws Exception {
        UUID lineId = resolveTestLineItemId();
        Assumptions.assumeTrue(lineId != null, "需要已有 driver 组件的产品行");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li);

        // 取一个「有非空 snapshot_rows 的组件」的 component_id + snapshot_rows
        // （必须按 component_id 精确配对——snapshot_rows 与 card_values tab 必须是同一组件）
        @SuppressWarnings("unchecked")
        var snapQ = em.createNativeQuery(
            "SELECT component_id, snapshot_rows FROM quotation_line_component_data " +
            "WHERE line_item_id = :id AND snapshot_rows IS NOT NULL " +
            "  AND jsonb_typeof(snapshot_rows)='array' AND jsonb_array_length(snapshot_rows) > 0 " +
            "LIMIT 1")
            .setParameter("id", lineId)
            .getResultList();
        Assumptions.assumeTrue(!snapQ.isEmpty(), "未找到 snapshot_rows 非空的组件，跳过");
        Object[] snapRow0 = (Object[]) snapQ.get(0);
        String snapCompId = snapRow0[0].toString();
        String snapRowsJson = snapRow0[1].toString();

        // 单独取 quote_card_values
        @SuppressWarnings("unchecked")
        var cardRows = em.createNativeQuery(
            "SELECT quote_card_values FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineId)
            .getResultList();
        assertFalse(cardRows.isEmpty());
        String cardValJson = cardRows.get(0) != null ? cardRows.get(0).toString() : null;
        assertNotNull(cardValJson, "quote_card_values 必须已写入");

        JsonNode snapRows = MAPPER.readTree(snapRowsJson);
        Assumptions.assumeTrue(snapRows.isArray() && snapRows.size() > 0,
            "snapshot_rows 为空数组，跳过值对账");

        JsonNode cardJson = MAPPER.readTree(cardValJson);

        // 在 tabs 里按 component_id 精确找对应 tab 的 baseRows（不是"第一个非空"）
        JsonNode matchingBaseRows = null;
        for (JsonNode tab : cardJson.path("tabs")) {
            if (snapCompId.equals(tab.path("componentId").asText(""))) {
                matchingBaseRows = tab.path("baseRows");
                break;
            }
        }
        assertNotNull(matchingBaseRows,
            "quote_card_values 中找不到 componentId=" + snapCompId + " 的 tab，card_values=" + cardValJson);

        // 精确对账：snapshot_rows 与同组件 card_values.baseRows 行数一致
        assertEquals(snapRows.size(), matchingBaseRows.size(),
            "baseRows 行数必须与 snapshot_rows 行数完全一致 compId=" + snapCompId);

        // 逐行比 basicDataValues
        for (int i = 0; i < snapRows.size(); i++) {
            JsonNode snapRow = snapRows.get(i);
            JsonNode cardRow = matchingBaseRows.get(i);

            JsonNode snapBdvRow = snapRow.path("basicDataValues");
            JsonNode cardBdvRow = cardRow.path("basicDataValues");

            assertTrue(cardRow.has("basicDataValues"),
                "baseRows[" + i + "] 必须含 basicDataValues 键");

            // 逐 path 比较（数字 BigDecimal.compareTo，字符串 equals）
            Iterator<Map.Entry<String, JsonNode>> fields = snapBdvRow.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String path = entry.getKey();
                JsonNode snapVal = entry.getValue();
                JsonNode cardVal = cardBdvRow.path(path);

                assertFalse(cardVal.isMissingNode(),
                    "basicDataValues 中缺少 path='" + path + "' (row " + i + ")");

                // 比较逻辑：数字用 compareTo，其他用 toString equals
                if (snapVal.isNumber() && cardVal.isNumber()) {
                    BigDecimal sv = new BigDecimal(snapVal.asText());
                    BigDecimal cv = new BigDecimal(cardVal.asText());
                    assertEquals(0, sv.compareTo(cv),
                        "basicDataValues['" + path + "'] row=" + i +
                        " expected=" + sv + " actual=" + cv);
                } else {
                    // null / string / boolean 用 toString
                    String svStr = snapVal.isNull() ? null : snapVal.asText();
                    String cvStr = cardVal.isNull() ? null : cardVal.asText();
                    assertEquals(svStr, cvStr,
                        "basicDataValues['" + path + "'] row=" + i +
                        " expected='" + svStr + "' actual='" + cvStr + "'");
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // T3: quote_excel_values.rows[0] 含至少一个非 null 值
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("T3: quote_excel_values.rows[0] 含至少一个非 null 值（ExcelViewService 真实计算）")
    void quoteExcelSnapshot_rowsContainValues() throws Exception {
        UUID lineId = resolveTestLineItemId();
        Assumptions.assumeTrue(lineId != null, "需要已有产品行");

        QuotationLineItem li = QuotationLineItem.findById(lineId);
        svc.snapshotLineValues(li);

        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery(
            "SELECT li.quote_excel_values, q.customer_template_id " +
            "FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "WHERE li.id = :id")
            .setParameter("id", lineId)
            .getResultList();

        assertFalse(rows.isEmpty());
        Object[] row = (Object[]) rows.get(0);
        String qev = row[0] != null ? row[0].toString() : null;
        assertNotNull(qev, "quote_excel_values 必须已写入");

        JsonNode json = MAPPER.readTree(qev);
        assertTrue(json.has("rows"), "quote_excel_values 必须含 'rows' 键");
        assertTrue(json.path("rows").isArray(), "quote_excel_values.rows 必须是数组");

        // 检查模板是否有 excel_view_config
        @SuppressWarnings("unchecked")
        var tmplRows = em.createNativeQuery(
            "SELECT excel_view_config FROM template WHERE id = :tid")
            .setParameter("tid", row[1])
            .getResultList();

        boolean hasExcelConfig = !tmplRows.isEmpty() && tmplRows.get(0) != null
            && !tmplRows.get(0).toString().isBlank()
            && !"[]".equals(tmplRows.get(0).toString().trim());

        if (hasExcelConfig) {
            // 模板有 excel_view_config → rows 必须非空且含非 null 值
            JsonNode rowsNode = json.path("rows");
            assertTrue(rowsNode.size() > 0,
                "excel_view_config 已配置的模板，rows 必须非空。quote_excel_values=" + qev);

            JsonNode firstRow = rowsNode.get(0);
            assertTrue(firstRow.isObject(), "rows[0] 必须是 object");

            boolean hasAnyValue = false;
            Iterator<Map.Entry<String, JsonNode>> fields = firstRow.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!entry.getValue().isNull() && !entry.getValue().asText("").isBlank()) {
                    hasAnyValue = true;
                    break;
                }
            }
            assertTrue(hasAnyValue,
                "rows[0] 中至少一列应有非 null/非空值。rows[0]=" + firstRow);
        }
        // 若模板无 excel_view_config，rows=[] 是合法的，仅验证结构（有 rows key）即可
    }
}
