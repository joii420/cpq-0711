package com.cpq.priceadjust.service;

import com.cpq.common.PrecisionPolicy;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.CustomerPriceAdjustElement;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.LineDiscountService;
import com.cpq.quotation.service.QuotationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-045/046: ordinary and material-upgrade writes share exact 12-decimal results and line scope. */
@QuarkusTest
class MaterialVersionUpgradePrecisionParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TARGET_MATERIAL = "TC045-TARGET";
    private static final String OTHER_MATERIAL = "TC046-OTHER";
    private static final BigDecimal TARGET_PRICE = new BigDecimal("0.083826");
    private static final BigDecimal FIXED_AMOUNT = new BigDecimal("0.000000000001");
    private static final BigDecimal EXPECTED_SUBTOTAL = new BigDecimal("0.083826000001");
    private static final BigDecimal INITIAL_HEADER_TOTAL = new BigDecimal("100.123456789012");
    private static final BigDecimal TAX_SENTINEL = new BigDecimal("9.876543210123");

    @Inject MaterialVersionUpgradeService upgradeService;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject LineDiscountService lineDiscountService;
    @Inject QuotationService quotationService;
    @Inject EntityManager em;

    private String customerNo;
    private UUID customerId;
    private UUID strategyId;
    private UUID componentId;
    private UUID subtotalComponentId;
    private UUID templateId;
    private UUID versionId;
    private UUID ordinaryQuotationId;
    private UUID upgradeQuotationId;
    private UUID ordinaryLineId;
    private UUID ordinaryNonTargetLineId;
    private UUID ordinaryPartLineId;
    private UUID targetLineId;
    private UUID nonTargetLineId;
    private UUID partLineId;
    private UUID salesRepId;
    private final List<UUID> componentDataIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (customerNo == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            exec("DELETE FROM costing_order_version_override WHERE costing_order_id IN "
                    + "(SELECT id FROM costing_order WHERE quotation_id IN (:q1,:q2))",
                "q1", ordinaryQuotationId, "q2", upgradeQuotationId);
            exec("DELETE FROM costing_order WHERE quotation_id IN (:q1,:q2)",
                "q1", ordinaryQuotationId, "q2", upgradeQuotationId);
            exec("DELETE FROM quotation_price_revision WHERE quotation_id IN (:q1,:q2)",
                "q1", ordinaryQuotationId, "q2", upgradeQuotationId);
            exec("DELETE FROM quotation_line_component_data WHERE line_item_id IN "
                    + "(SELECT id FROM quotation_line_item WHERE quotation_id IN (:q1,:q2))",
                "q1", ordinaryQuotationId, "q2", upgradeQuotationId);
            exec("DELETE FROM quotation_line_item WHERE quotation_id IN (:q1,:q2)",
                "q1", ordinaryQuotationId, "q2", upgradeQuotationId);
            exec("DELETE FROM quotation_view_structure WHERE quotation_id IN (:q1,:q2)",
                "q1", ordinaryQuotationId, "q2", upgradeQuotationId);
            exec("DELETE FROM quotation WHERE id IN (:q1,:q2)",
                "q1", ordinaryQuotationId, "q2", upgradeQuotationId);
            exec("DELETE FROM element_price_version_item WHERE version_id=:id", "id", versionId);
            exec("DELETE FROM element_price_version WHERE id=:id", "id", versionId);
            exec("DELETE FROM customer_price_adjust_element WHERE strategy_id=:id", "id", strategyId);
            exec("DELETE FROM customer_price_adjust_strategy WHERE id=:id", "id", strategyId);
            exec("DELETE FROM template WHERE id=:id", "id", templateId);
            exec("DELETE FROM component WHERE id=:id", "id", subtotalComponentId);
            exec("DELETE FROM component WHERE id=:id", "id", componentId);
            exec("DELETE FROM customer WHERE id=:id", "id", customerId);
        });
    }

    @Test
    void ordinaryWriteAndRealUpgrade_matchAtTwelveDecimals_withoutExpandingLineScope() throws Exception {
        seedFixture();

        QuarkusTransaction.requiringNew().run(() -> {
            Quotation ordinaryQuote = Quotation.findById(ordinaryQuotationId);
            Quotation upgradeQuote = Quotation.findById(upgradeQuotationId);
            prepareOrdinaryLine(ordinaryLineId, ordinaryQuote);
            prepareOrdinaryLine(ordinaryNonTargetLineId, ordinaryQuote);
            prepareOrdinaryLine(nonTargetLineId, upgradeQuote);
        });
        QuarkusTransaction.requiringNew().run(() ->
            quotationService.submit(ordinaryQuotationId, salesRepId));
        LineAmounts ordinary = readAmounts(ordinaryLineId);
        BigDecimal submittedTotal = readHeaderTotal(ordinaryQuotationId);

        LineFingerprint targetBefore = readFingerprint(targetLineId, componentId);
        LineFingerprint nonTargetBefore = readFingerprint(nonTargetLineId, componentId);
        LineFingerprint partBefore = readFingerprint(partLineId, componentId);
        assertSnapshotFingerprintsPresent(nonTargetBefore, "non-target");
        assertSnapshotFingerprintsPresent(partBefore, "PART");

        UpgradeResult result = upgradeService.upgrade(targetLineId, versionId, false);
        assertEquals(UpgradeResult.Status.SUCCESS, result.status,
            "TC-045 fixture 必须命中真实 upgrade SUCCESS，不允许 SKIPPED 充当证据: " + result.message);

        LineAmounts upgraded = readAmounts(targetLineId);
        assertAmountsEqual(ordinary, upgraded);
        assertDecimalEquals(EXPECTED_SUBTOTAL, result.newSubtotal, "upgrade result.newSubtotal");

        LineFingerprint targetAfter = readFingerprint(targetLineId, componentId);
        LineFingerprint nonTargetAfter = readFingerprint(nonTargetLineId, componentId);
        LineFingerprint partAfter = readFingerprint(partLineId, componentId);

        assertEquals(targetBefore.rowVersion + 1, targetAfter.rowVersion,
            "TC-046 target qlcd row_version 必须且只能递增一次");
        assertNotEquals(targetBefore.componentXmin, targetAfter.componentXmin,
            "TC-046 target qlcd xmin 必须变化");
        assertNotEquals(targetBefore.snapshotMd5, targetAfter.snapshotMd5,
            "TC-046 target snapshot_rows 必须写入版本价");
        assertEquals(targetBefore.rowDataMd5, targetAfter.rowDataMd5,
            "空 row_data 不应被升版凭空改写");
        assertNotEquals(targetBefore.amounts, targetAfter.amounts,
            "TC-046 target 六个派生金额必须随新价重算");

        assertEquals(nonTargetBefore, nonTargetAfter,
            "TC-046 其他料号 SIMPLE 行金额与 qlcd row_version/xmin/md5 必须逐项不变");
        assertEquals(partBefore, partAfter,
            "TC-046 同料号 PART 行金额与 qlcd row_version/xmin/md5 必须逐项不变");

        assertHeaderAndRevisionEvidence(upgraded, submittedTotal);
    }

    private void prepareOrdinaryLine(UUID lineId, Quotation quotation) {
        QuotationLineItem line = QuotationLineItem.findById(lineId);
        cardSnapshotService.snapshotQuoteSideOnly(line, quotation, null, false);
        lineDiscountService.recompute(line);
    }

    private void assertSnapshotFingerprintsPresent(LineFingerprint fingerprint, String lineKind) {
        assertNotNull(fingerprint.quoteCardMd5, lineKind + " quote_card_values fixture");
        assertNotNull(fingerprint.costingCardMd5, lineKind + " costing_card_values fixture");
        assertNotNull(fingerprint.quoteExcelMd5, lineKind + " quote_excel_values fixture");
        assertNotNull(fingerprint.costingExcelMd5, lineKind + " costing_excel_values fixture");
    }

    private void assertAmountsEqual(LineAmounts expected, LineAmounts actual) {
        assertDecimalEquals(expected.subtotal, actual.subtotal, "subtotal");
        assertDecimalEquals(expected.lineUnitPrice, actual.lineUnitPrice, "lineUnitPrice");
        assertDecimalEquals(expected.discountBaseAmount, actual.discountBaseAmount, "discountBaseAmount");
        assertDecimalEquals(expected.lineFinalPrice, actual.lineFinalPrice, "lineFinalPrice");
        assertDecimalEquals(expected.lineDiscountAmount, actual.lineDiscountAmount, "lineDiscountAmount");
        assertDecimalEquals(expected.lineTotalAmount, actual.lineTotalAmount, "lineTotalAmount");
        for (BigDecimal value : actual.values()) {
            assertEquals(12, value.scale(), "TC-045 金额落库必须保留 numeric(26,12): " + value);
        }
    }

    private void assertHeaderAndRevisionEvidence(LineAmounts upgraded, BigDecimal submittedTotal) throws Exception {
        Object[] header = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT original_amount,total_amount,tax_amount,status FROM quotation WHERE id=:id")
            .setParameter("id", upgradeQuotationId).getSingleResult());
        BigDecimal expectedTotal = PrecisionPolicy.roundForCalculation(
            upgraded.lineTotalAmount.add(readAmounts(nonTargetLineId).lineTotalAmount));
        assertNotEquals(expectedTotal, expectedTotal.setScale(4, PrecisionPolicy.ROUNDING),
            "TC-045 fixture must expose a regression to four-decimal header aggregation");
        assertDecimalEquals(expectedTotal, submittedTotal,
            "QuotationService.submit totalAmount write point");
        assertDecimalEquals(INITIAL_HEADER_TOTAL, (BigDecimal) header[0],
            "non-DRAFT upgrade must not replace originalAmount");
        assertDecimalEquals(expectedTotal, (BigDecimal) header[1], "quotation.totalAmount");
        assertDecimalEquals(TAX_SENTINEL, (BigDecimal) header[2], "quotation.taxAmount must remain unchanged");
        assertEquals("SUBMITTED", header[3], "upgrade fixture must stay outside the DRAFT overwrite path");

        Object[] initial = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT sealed,quote_total_amount,snapshot_rows::text FROM quotation_price_revision "
                    + "WHERE quotation_id=:qid AND based_version_id IS NULL")
            .setParameter("qid", upgradeQuotationId).getSingleResult());
        assertEquals(Boolean.TRUE, initial[0]);
        assertDecimalEquals(INITIAL_HEADER_TOTAL, (BigDecimal) initial[1], "initial revision quoteTotalAmount");
        JsonNode initialRows = MAPPER.readTree((String) initial[2]);
        assertEquals("0.040000", initialRows.path(targetLineId.toString())
            .path(componentId.toString()).get(0).path("driverRow").path("price").asText(),
            "初版审计快照必须冻结升版前价格");

        Object[] current = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT sealed,quote_total_amount,upgraded_material_nos::text,quote_card_values::text,"
                    + "snapshot_rows::text FROM quotation_price_revision "
                    + "WHERE quotation_id=:qid AND based_version_id=:vid")
            .setParameter("qid", upgradeQuotationId).setParameter("vid", versionId).getSingleResult());
        assertEquals(Boolean.TRUE, current[0]);
        assertDecimalEquals(expectedTotal, (BigDecimal) current[1], "current revision quoteTotalAmount");
        JsonNode materialNos = MAPPER.readTree((String) current[2]);
        assertEquals(1, materialNos.size());
        assertEquals(TARGET_MATERIAL, materialNos.get(0).asText());
        JsonNode quoteSnapshot = MAPPER.readTree((String) current[3]);
        JsonNode rowSnapshot = MAPPER.readTree((String) current[4]);
        for (UUID lineId : List.of(targetLineId, nonTargetLineId, partLineId)) {
            assertTrue(quoteSnapshot.has(lineId.toString()), "本期报价快照必须覆盖整单 line=" + lineId);
            assertTrue(rowSnapshot.has(lineId.toString()), "本期行快照必须覆盖整单 line=" + lineId);
        }
        assertEquals("0.083826", rowSnapshot.path(targetLineId.toString())
            .path(componentId.toString()).get(0).path("driverRow").path("price").asText(),
            "本期审计快照必须冻结升版后 12 位价格");
    }

    private void seedFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            customerNo = "TC045-" + UUID.randomUUID().toString().substring(0, 8);
            customerId = UUID.randomUUID();
            exec("INSERT INTO customer (id,code,name) VALUES (:id,:code,'TC-045 precision customer')",
                "id", customerId, "code", customerNo);

            CustomerPriceAdjustStrategy strategy = new CustomerPriceAdjustStrategy();
            strategy.customerNo = customerNo;
            strategy.enabled = true;
            strategy.persist();
            strategyId = strategy.id;
            CustomerPriceAdjustElement element = new CustomerPriceAdjustElement();
            element.strategyId = strategyId;
            element.elementCode = "Ag";
            element.persist();

            componentId = UUID.randomUUID();
            subtotalComponentId = UUID.randomUUID();
            String fields = "[{\"name\":\"element\",\"field_type\":\"INPUT_TEXT\"},"
                + "{\"name\":\"price\",\"field_type\":\"INPUT_NUMBER\","
                + "\"is_amount\":true,\"is_subtotal\":true},"
                + "{\"name\":\"fixedAmount\",\"field_type\":\"INPUT_NUMBER\","
                + "\"is_amount\":true,\"is_subtotal\":true}]";
            exec("INSERT INTO component (id,name,code,component_type,fields,formulas,element_code_field,"
                    + "element_price_field,element_currency_field) VALUES (:id,'TC-045 element','TC045-ELEMENT',"
                    + "'NORMAL',CAST(:fields AS jsonb),'[]','element','price','currency')",
                "id", componentId, "fields", fields);
            String subtotalFormulas = "[{\"expression\":[{\"type\":\"component_subtotal\","
                + "\"component_code\":\"TC045-ELEMENT\",\"value\":\"__amount_total__\"}]}]";
            exec("INSERT INTO component (id,name,code,component_type,fields,formulas) "
                    + "VALUES (:id,'TC-045 subtotal','TC045-SUBTOTAL','SUBTOTAL','[]',CAST(:formulas AS jsonb))",
                "id", subtotalComponentId, "formulas", subtotalFormulas);

            templateId = UUID.randomUUID();
            exec("INSERT INTO template (id,template_series_id,name,version,status,template_kind,customer_id,"
                    + "components_snapshot,product_attributes,subtotal_formula,template_sql_views_snapshot,"
                    + "formulas,created_at,updated_at,published_at) VALUES (:id,:series,'TC-045 template','v1',"
                    + "'PUBLISHED','QUOTATION',:customerId,CAST(:snapshot AS jsonb),'[]','[]','{}','[]',"
                    + "now(),now(),now())",
                "id", templateId, "series", UUID.randomUUID(), "customerId", customerId,
                "snapshot", frozenTabs());
            insertFrozenTemplateTab(componentId, 1, "Element cost", "TC-045 element",
                "TC045-ELEMENT", "NORMAL", fields, "[]", "element", "price", "currency");
            insertFrozenTemplateTab(subtotalComponentId, 2, "Total", "TC-045 subtotal",
                "TC045-SUBTOTAL", "SUBTOTAL", "[]", subtotalFormulas, null, null, null);

            salesRepId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
            ordinaryQuotationId = insertQuotation("TC045-ORD-", salesRepId, BigDecimal.ZERO, "DRAFT");
            upgradeQuotationId = insertQuotation("TC045-UPG-", salesRepId, INITIAL_HEADER_TOTAL, "SUBMITTED");
            insertFrozenStructure(ordinaryQuotationId);
            insertFrozenStructure(upgradeQuotationId);

            ordinaryLineId = insertLine(ordinaryQuotationId, TARGET_MATERIAL, "SIMPLE", null,
                TARGET_PRICE, "ordinary");
            ordinaryNonTargetLineId = insertLine(ordinaryQuotationId, OTHER_MATERIAL, "SIMPLE", null,
                new BigDecimal("21.000000000001"), "ordinary-non-target");
            ordinaryPartLineId = insertLine(ordinaryQuotationId, TARGET_MATERIAL, "PART", ordinaryLineId,
                new BigDecimal("31.000000000001"), "ordinary-part");
            targetLineId = insertLine(upgradeQuotationId, TARGET_MATERIAL, "SIMPLE", null,
                new BigDecimal("0.040000"), "target");
            nonTargetLineId = insertLine(upgradeQuotationId, OTHER_MATERIAL, "SIMPLE", null,
                new BigDecimal("21.000000000001"), "non-target");
            partLineId = insertLine(upgradeQuotationId, TARGET_MATERIAL, "PART", targetLineId,
                new BigDecimal("31.000000000001"), "part");

            versionId = UUID.randomUUID();
            exec("INSERT INTO element_price_version (id,customer_no,version_no,base_date,status,trigger_type,created_at) "
                    + "VALUES (:id,:customerNo,'TC045V1',CURRENT_DATE,'PENDING','MANUAL',now())",
                "id", versionId, "customerNo", customerNo);
            exec("INSERT INTO element_price_version_item (version_id,element_code,current_price,currency) "
                    + "VALUES (:id,'Ag',CAST(:price AS numeric),'CNY')",
                "id", versionId, "price", TARGET_PRICE.toPlainString());
        });
    }

    private void insertFrozenTemplateTab(UUID cid, int sortOrder, String tabName,
                                         String componentName, String componentCode,
                                         String componentType, String fields, String formulas,
                                         String elementCodeField, String elementPriceField,
                                         String elementCurrencyField) {
        UUID templateComponentId = UUID.randomUUID();
        exec("INSERT INTO template_component (id,template_id,component_id,sort_order,tab_name) "
                + "VALUES (:id,:templateId,:componentId,:sortOrder,:tabName)",
            "id", templateComponentId, "templateId", templateId, "componentId", cid,
            "sortOrder", sortOrder, "tabName", tabName);
        exec("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id,"
                + "sort_order,tab_name,component_name,component_code,component_type,fields,formulas,"
                + "element_code_field,element_price_field,element_currency_field) "
                + "VALUES (:templateId,:templateComponentId,:componentId,:sortOrder,:tabName,"
                + ":componentName,:componentCode,:componentType,CAST(:fields AS jsonb),"
                + "CAST(:formulas AS jsonb),:elementCodeField,:elementPriceField,:elementCurrencyField)",
            "templateId", templateId, "templateComponentId", templateComponentId,
            "componentId", cid, "sortOrder", sortOrder, "tabName", tabName,
            "componentName", componentName, "componentCode", componentCode,
            "componentType", componentType, "fields", fields, "formulas", formulas,
            "elementCodeField", elementCodeField, "elementPriceField", elementPriceField,
            "elementCurrencyField", elementCurrencyField);
    }

    private UUID insertQuotation(String prefix, UUID salesRepId, BigDecimal totalAmount, String status) {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,customer_template_id,"
                + "original_amount,total_amount,final_discount_rate,tax_amount,created_at,updated_at) "
                + "VALUES (:id,:no,:customerId,'TC-045 parity',:salesRepId,:status,:templateId,"
                + "CAST(:total AS numeric),CAST(:total AS numeric),87.65,CAST(:tax AS numeric),now(),now())",
            "id", id, "no", prefix + id, "customerId", customerId, "salesRepId", salesRepId,
            "status", status, "templateId", templateId, "total", totalAmount.toPlainString(),
            "tax", TAX_SENTINEL.toPlainString());
        return id;
    }

    private UUID insertLine(UUID quotationId, String materialNo, String compositeType, UUID parentLineId,
                            BigDecimal snapshotPrice, String marker) {
        UUID lineId = UUID.randomUUID();
        String snapshotSentinel = "{\"marker\":\"" + marker
            + "\",\"value\":\"98765431.123456789012\"}";
        exec("INSERT INTO quotation_line_item (id,quotation_id,template_id,product_part_no_snapshot,"
                + "composite_type,parent_line_item_id,annual_volume,discount_source,discount_rate_applied,"
                + "subtotal,line_unit_price,discount_base_amount,line_final_price,line_discount_amount,"
                + "line_total_amount,quote_card_values,costing_card_values,quote_excel_values,"
                + "costing_excel_values,created_at) VALUES (:id,:qid,:templateId,:materialNo,:compositeType,"
                + ":parentLineId,7,'SUBTOTAL',12.34,CAST(:subtotal AS numeric),1.000000000001,"
                + "2.000000000001,3.000000000001,4.000000000001,5.000000000001,"
                + "CAST(:snapshot AS jsonb),CAST(:snapshot AS jsonb),CAST(:snapshot AS jsonb),"
                + "CAST(:snapshot AS jsonb),now())",
            "id", lineId, "qid", quotationId, "templateId", templateId, "materialNo", materialNo,
            "compositeType", compositeType, "parentLineId", parentLineId,
            "subtotal", snapshotPrice.toPlainString(), "snapshot", snapshotSentinel);
        UUID componentDataId = UUID.randomUUID();
        componentDataIds.add(componentDataId);
        String rows = "[{\"driverRow\":{\"element\":\"Ag\",\"price\":\""
            + snapshotPrice.toPlainString() + "\",\"fixedAmount\":\""
            + FIXED_AMOUNT.toPlainString() + "\",\"currency\":\"CNY\"},\"basicDataValues\":{}}]";
        String rowData = "[{\"marker\":\"" + marker + "\"}]";
        exec("INSERT INTO quotation_line_component_data (id,line_item_id,component_id,snapshot_rows,row_data,"
                + "row_version,subtotal,created_at) VALUES (:id,:lineId,:componentId,CAST(:rows AS jsonb),"
                + "CAST(:rowData AS jsonb),5,CAST(:subtotal AS numeric),now())",
            "id", componentDataId, "lineId", lineId, "componentId", componentId,
            "rows", rows, "rowData", rowData, "subtotal", snapshotPrice.toPlainString());
        return lineId;
    }

    private void insertFrozenStructure(UUID quotationId) {
        exec("INSERT INTO quotation_view_structure (quotation_id,view_kind,structure) "
                + "VALUES (:qid,'QUOTE_CARD',CAST(:structure AS jsonb))",
            "qid", quotationId, "structure", "{\"tabs\":" + frozenTabs() + "}");
    }

    private String frozenTabs() {
        return "[{\"componentId\":\"" + componentId + "\",\"componentCode\":\"TC045-ELEMENT\","
            + "\"tabName\":\"Element cost\",\"componentType\":\"NORMAL\",\"sortOrder\":1,"
            + "\"fields\":[{\"name\":\"element\",\"fieldType\":\"INPUT_TEXT\"},"
            + "{\"name\":\"price\",\"fieldType\":\"INPUT_NUMBER\",\"isAmount\":true,"
            + "\"isSubtotal\":true},{\"name\":\"fixedAmount\",\"fieldType\":\"INPUT_NUMBER\","
            + "\"isAmount\":true,\"isSubtotal\":true}],\"formulas\":[],\"formula_assignments\":[]},"
            + "{\"componentId\":\"" + subtotalComponentId + "\",\"componentCode\":\"TC045-SUBTOTAL\","
            + "\"tabName\":\"Total\",\"componentType\":\"SUBTOTAL\",\"sortOrder\":2,"
            + "\"fields\":[],\"formula_assignments\":[],\"formulas\":[{\"expression\":[{"
            + "\"type\":\"component_subtotal\",\"component_code\":\"TC045-ELEMENT\","
            + "\"value\":\"__amount_total__\"}]}]}]";
    }

    private LineAmounts readAmounts(UUID lineId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT subtotal,line_unit_price,discount_base_amount,line_final_price,"
                        + "line_discount_amount,line_total_amount FROM quotation_line_item WHERE id=:id")
                .setParameter("id", lineId).getSingleResult();
            return new LineAmounts((BigDecimal) row[0], (BigDecimal) row[1], (BigDecimal) row[2],
                (BigDecimal) row[3], (BigDecimal) row[4], (BigDecimal) row[5]);
        });
    }

    private LineFingerprint readFingerprint(UUID lineId, UUID cid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT cd.row_version,cd.xmin::text,md5(cd.snapshot_rows::text),md5(cd.row_data::text),"
                        + "li.xmin::text,md5(row_to_json(li)::text),"
                        + "md5(li.quote_card_values::text),md5(li.costing_card_values::text),"
                        + "md5(li.quote_excel_values::text),md5(li.costing_excel_values::text),"
                        + "li.subtotal,li.line_unit_price,li.discount_base_amount,li.line_final_price,"
                        + "li.line_discount_amount,li.line_total_amount "
                        + "FROM quotation_line_item li JOIN quotation_line_component_data cd "
                        + "ON cd.line_item_id=li.id AND cd.component_id=:cid WHERE li.id=:id")
                .setParameter("id", lineId).setParameter("cid", cid).getSingleResult();
            return new LineFingerprint(((Number) row[0]).longValue(), (String) row[1], (String) row[2],
                (String) row[3], (String) row[4], (String) row[5], (String) row[6], (String) row[7],
                (String) row[8], (String) row[9],
                new LineAmounts((BigDecimal) row[10], (BigDecimal) row[11],
                    (BigDecimal) row[12], (BigDecimal) row[13], (BigDecimal) row[14], (BigDecimal) row[15]));
        });
    }

    private BigDecimal readHeaderTotal(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> (BigDecimal) em.createNativeQuery(
                "SELECT total_amount FROM quotation WHERE id=:id")
            .setParameter("id", quotationId).getSingleResult());
    }

    private static void assertDecimalEquals(BigDecimal expected, BigDecimal actual, String field) {
        assertNotNull(actual, field + " must not be null");
        assertEquals(0, expected.compareTo(actual), field + ": expected=" + expected + ", actual=" + actual);
    }

    private void exec(String sql, Object... nameValuePairs) {
        var query = em.createNativeQuery(sql);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
        }
        query.executeUpdate();
    }

    private record LineAmounts(BigDecimal subtotal, BigDecimal lineUnitPrice,
                               BigDecimal discountBaseAmount, BigDecimal lineFinalPrice,
                               BigDecimal lineDiscountAmount, BigDecimal lineTotalAmount) {
        List<BigDecimal> values() {
            return List.of(subtotal, lineUnitPrice, discountBaseAmount, lineFinalPrice,
                lineDiscountAmount, lineTotalAmount);
        }
    }

    private record LineFingerprint(long rowVersion, String componentXmin, String snapshotMd5,
                                   String rowDataMd5, String lineXmin, String wholeLineMd5,
                                   String quoteCardMd5, String costingCardMd5,
                                   String quoteExcelMd5, String costingExcelMd5,
                                   LineAmounts amounts) {}
}
