package com.cpq.common;

import com.cpq.basicdata.v6.dto.CreateMaterialMasterRequest;
import com.cpq.basicdata.v6.dto.ProcessMasterUpsertRequest;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.PartRequest;
import com.cpq.costing.dto.ComparisonExportRequest;
import com.cpq.customer.dto.CreateCustomerRequest;
import com.cpq.elementprice.pricetable.CreatePriceRequest;
import com.cpq.elementprice.pricetable.UpdatePriceRequest;
import com.cpq.elementprice.strategy.StrategyUpsertRequest;
import com.cpq.globalvariable.GlobalVariableResource;
import com.cpq.priceadjust.dto.PriceAdjustSettingsDTO;
import com.cpq.priceadjust.dto.PutStrategyRequest;
import com.cpq.pricing.dto.CreatePricingStrategyRequest;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecimalRequestContractTest {

    private static final ObjectMapper MAPPER = DecimalJacksonCustomizer.newMapper();
    private static final String DECIMAL = "98765431.123456789012";

    private record Contract(Class<?> type, String field) {
    }

    @Test
    void precisionDtoFieldsAcceptStringsAndRejectJsonNumbers() throws Exception {
        List<Contract> contracts = List.of(
                new Contract(PutStrategyRequest.class, "costDiffThreshold"),
                new Contract(CreatePricingStrategyRequest.class, "baseDiscount"),
                new Contract(CreatePricingStrategyRequest.class, "minOrderAmount"),
                new Contract(CreatePricingStrategyRequest.RuleRequest.class, "thresholdAmount"),
                new Contract(CreatePricingStrategyRequest.RuleRequest.class, "discountRate"),
                new Contract(CreatePriceRequest.class, "price"),
                new Contract(UpdatePriceRequest.class, "price"),
                new Contract(StrategyUpsertRequest.class, "factor"),
                new Contract(StrategyUpsertRequest.class, "premium"),
                new Contract(MaterialRecipeUpsertRequest.ElementUpsert.class, "defaultPct"),
                new Contract(MaterialRecipeUpsertRequest.ElementUpsert.class, "minPct"),
                new Contract(MaterialRecipeUpsertRequest.ElementUpsert.class, "maxPct"),
                new Contract(ElementOverride.class, "pct"),
                new Contract(PartRequest.class, "unitWeightGrams"),
                new Contract(CreateMaterialMasterRequest.class, "unitWeight"),
                new Contract(ProcessMasterUpsertRequest.class, "defaultDefectRate"),
                new Contract(CreateCustomerRequest.class, "creditLimit"),
                new Contract(PriceAdjustSettingsDTO.class, "subtotalGuardThreshold"),
                new Contract(GlobalVariableResource.UpsertEntryRequest.class, "value"),
                new Contract(ComparisonExportRequest.Cell.class, "quote"),
                new Contract(ComparisonExportRequest.Cell.class, "costing"),
                new Contract(SaveDraftRequest.class, "finalDiscountRate"),
                new Contract(SaveDraftRequest.LineItemDraft.class, "subtotal"),
                new Contract(SaveDraftRequest.LineItemDraft.class, "discountBaseAmount"),
                new Contract(SaveDraftRequest.LineItemDraft.class, "discountRateApplied"),
                new Contract(SaveDraftRequest.LineItemDraft.class, "lineDiscountAmount"),
                new Contract(SaveDraftRequest.LineItemDraft.class, "lineUnitPrice"),
                new Contract(SaveDraftRequest.LineItemDraft.class, "lineFinalPrice"),
                new Contract(SaveDraftRequest.LineItemDraft.class, "lineTotalAmount"),
                new Contract(SaveDraftRequest.ComponentDataDraft.class, "subtotal"));

        for (Contract contract : contracts) {
            Field field = contract.type().getField(contract.field());
            JsonDeserialize annotation = field.getAnnotation(JsonDeserialize.class);
            assertEquals(DecimalStringDeserializer.class, annotation.using(),
                    contract.type().getSimpleName() + "." + contract.field());

            Object dto = MAPPER.readValue(
                    "{\""+contract.field()+"\":\""+DECIMAL+"\"}", contract.type());
            assertEquals(new BigDecimal(DECIMAL), field.get(dto),
                    contract.type().getSimpleName() + "." + contract.field());

            assertThrows(Exception.class, () -> MAPPER.readValue(
                            "{\""+contract.field()+"\":"+DECIMAL+"}", contract.type()),
                    contract.type().getSimpleName() + "." + contract.field());
        }
    }

    @Test
    void recursiveMapValidatorRejectsPrecisionNumbersButAllowsStructuralIntegers() {
        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("amount", DECIMAL);
        valid.put("rowIndex", 3);
        valid.put("rows", List.of(Map.of("rate", "0.123456789012", "sortOrder", 2)));
        DecimalRequestValidator.rejectNumericTokens(valid, "bindings");

        var ex = assertThrows(RuntimeException.class, () ->
                DecimalRequestValidator.rejectNumericTokens(
                        Map.of("inputs", Map.of("amount", new BigDecimal(DECIMAL))), "bindings"));
        assertTrue(ex.getMessage().contains("bindings.inputs.amount"));
        assertTrue(ex.getMessage().contains(DECIMAL));

        assertThrows(RuntimeException.class, () ->
                DecimalRequestValidator.rejectNumericTokens(Map.of("rowIndex", 1.5d), "bindings"));
    }

    @Test
    void recursiveMapValidatorReportsBigDecimalsInPlainNotation() {
        var small = assertThrows(RuntimeException.class, () ->
                DecimalRequestValidator.rejectNumericTokens(
                        Map.of("rate", new BigDecimal("0.000000000001")), "driverRow"));
        assertTrue(small.getMessage().contains("driverRow.rate"));
        assertTrue(small.getMessage().contains("0.000000000001"));
        assertFalse(small.getMessage().contains("1E-12"));

        var large = assertThrows(RuntimeException.class, () ->
                DecimalRequestValidator.rejectNumericTokens(
                        Map.of("amount", new BigDecimal(DECIMAL)), "bindings"));
        assertTrue(large.getMessage().contains("bindings.amount"));
        assertTrue(large.getMessage().contains(DECIMAL));
    }

    @Test
    void decimalStringNormalizerRecursesAndRejectsScientificNotation() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("amount", DECIMAL);
        original.put("integer", "42");
        original.put("category", "STANDARD");
        original.put("rows", List.of(Map.of(
                "rate", "0.000000000001",
                "enabled", true,
                "note", "001")));

        Map<String, Object> normalized = DecimalRequestValidator.normalizeDecimalStrings(original);

        assertEquals(new BigDecimal(DECIMAL), normalized.get("amount"));
        assertEquals(new BigDecimal("42"), normalized.get("integer"));
        assertEquals("STANDARD", normalized.get("category"));
        List<?> rows = assertInstanceOf(List.class, normalized.get("rows"));
        Map<?, ?> row = assertInstanceOf(Map.class, rows.get(0));
        assertEquals(new BigDecimal("0.000000000001"), row.get("rate"));
        assertEquals(true, row.get("enabled"));
        assertEquals("001", row.get("note"));
        assertEquals(DECIMAL, original.get("amount"));

        var scientific = assertThrows(RuntimeException.class, () ->
                DecimalRequestValidator.normalizeDecimalStrings(
                        Map.of("rows", List.of(Map.of("rate", "-2E+4"))), "bindings"));
        assertTrue(scientific.getMessage().contains("bindings.rows[0].rate"));
        assertTrue(scientific.getMessage().contains("-2E+4"));
    }

    @Test
    void embeddedSnapshotValidatorRejectsNumericTokensWithoutLosingLegacyLiteral() throws Exception {
        String historical = "{\"rows\":[{\"row_index\":1,\"amount\":"+DECIMAL+"}]}";
        assertEquals(new BigDecimal(DECIMAL),
                MAPPER.readTree(historical).path("rows").path(0).path("amount").decimalValue());

        var ex = assertThrows(RuntimeException.class, () ->
                DecimalRequestValidator.rejectNumericJsonTokens(historical, "quoteExcelValues"));
        assertTrue(ex.getMessage().contains("quoteExcelValues.rows[0].amount"));
        assertTrue(ex.getMessage().contains(DECIMAL));

        DecimalRequestValidator.rejectNumericJsonTokens(
                "{\"rows\":[{\"row_index\":1,\"amount\":\""+DECIMAL+"\"}]}",
                "quoteExcelValues");

        DecimalRequestValidator.rejectNumericJsonTokens(
                "[{\"序号\":1,\"_项次\":2,\"annualVolume\":5,\"数量\":\"5\",\"金额\":\""+DECIMAL+"\"}]",
                "lineItems[0].componentData[0].rowData");
        assertThrows(RuntimeException.class, () -> DecimalRequestValidator.rejectNumericJsonTokens(
                "[{\"annualVolume\":5.5}]", "lineItems[0].componentData[0].rowData"));
    }

    @Test
    void p1ToP4MapEntryPointsHaveExplicitValidationInventory() throws Exception {
        String formula = Files.readString(Path.of(
                "src/main/java/com/cpq/formula/resource/FormulaEvaluateResource.java"));
        assertTrue(formula.contains("rejectNumericTokens(req.bindings"));
        assertTrue(formula.contains("rejectNumericTokens(req.driverRow"));

        String quotation = Files.readString(Path.of(
                "src/main/java/com/cpq/quotation/resource/QuotationResource.java"));
        for (String marker : List.of(
                "validateDraftDecimals(request)",
                "rejectNumericTokens(body.get(\"value\")",
                "rejectNumericTokens(d.frontendValue",
                "rejectNumericTokens(d.backendValue",
                "rejectNumericTokens(d.frontendInputs",
                "rejectNumericTokens(d.backendInputs",
                "rejectNumericTokens(req != null ? req.columns",
                "rejectNumericJsonTokens(component.rowData",
                "rejectNumericTokens(process.paramValues")) {
            assertTrue(quotation.contains(marker), marker);
        }
    }
}
