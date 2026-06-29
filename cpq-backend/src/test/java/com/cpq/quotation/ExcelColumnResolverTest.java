package com.cpq.quotation;

import com.cpq.quotation.service.ExcelColumnResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAIN JUnit (NO @QuarkusTest) — pure logic of Task 3.1 Excel column resolver.
 * Covers mergeColumnOverrides (copy only present keys, no null clobber) and
 * isLegacyArrayConfig (bare array old format vs object w/ excel_component_id).
 */
class ExcelColumnResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> col(String colKey, String title, boolean hidden) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("col_key", colKey);
        m.put("title", title);
        m.put("hidden", hidden);
        return m;
    }

    @Test
    void mergeColumnOverrides_copiesOnlyPresentKeys_titlePreserved() {
        List<Map<String, Object>> base = new ArrayList<>();
        base.add(col("A", "T", false));

        Map<String, Object> ov = new LinkedHashMap<>();
        ov.put("col_key", "A");
        ov.put("hidden", true);
        List<Map<String, Object>> overrides = List.of(ov);

        List<Map<String, Object>> result = ExcelColumnResolver.mergeColumnOverrides(base, overrides);

        assertEquals(1, result.size());
        Map<String, Object> a = result.get(0);
        assertEquals("A", a.get("col_key"));
        // title preserved from base (override did not contain it)
        assertEquals("T", a.get("title"));
        // hidden overridden
        assertEquals(Boolean.TRUE, a.get("hidden"));
    }

    @Test
    void mergeColumnOverrides_doesNotClobberWithMissingKey() {
        List<Map<String, Object>> base = new ArrayList<>();
        Map<String, Object> b = col("B", "Title B", false);
        b.put("formula", "=A1*2");
        base.add(b);

        // override only flips hidden; formula must survive
        Map<String, Object> ov = new LinkedHashMap<>();
        ov.put("col_key", "B");
        ov.put("hidden", true);

        List<Map<String, Object>> result =
                ExcelColumnResolver.mergeColumnOverrides(base, List.of(ov));
        Map<String, Object> r = result.get(0);
        assertEquals("=A1*2", r.get("formula"));
        assertEquals("Title B", r.get("title"));
        assertEquals(Boolean.TRUE, r.get("hidden"));
    }

    @Test
    void mergeColumnOverrides_overrideDisplayFormatDecimals_reachesMergedColumn() {
        // B5: lock the contract that an override's display_format.decimals (set via
        // ExcelViewConfigTab) survives the merge so BOTH on-screen getExcelView and
        // exportExcelView (which both consume getEffectiveColumns) see the configured precision.
        List<Map<String, Object>> base = new ArrayList<>();
        base.add(col("PRICE", "Price", false)); // base has NO display_format

        Map<String, Object> df = new LinkedHashMap<>();
        df.put("type", "NUMBER");
        df.put("decimals", 4);
        Map<String, Object> ov = new LinkedHashMap<>();
        ov.put("col_key", "PRICE");
        ov.put("display_format", df);

        List<Map<String, Object>> result =
                ExcelColumnResolver.mergeColumnOverrides(base, List.of(ov));

        Map<String, Object> r = result.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedDf = (Map<String, Object>) r.get("display_format");
        assertNotNull(mergedDf, "override display_format must be present on merged column");
        assertEquals(4, mergedDf.get("decimals"));
        assertEquals("NUMBER", mergedDf.get("type"));
        assertEquals("Price", r.get("title")); // base title untouched
    }

    @Test
    void mergeColumnOverrides_overrideDisplayFormat_replacesBaseDisplayFormat() {
        // When an override supplies display_format, it wholly replaces the base column's
        // display_format (shallow merge by top-level key) — the override's decimals wins.
        List<Map<String, Object>> base = new ArrayList<>();
        Map<String, Object> b = col("AMT", "Amount", false);
        Map<String, Object> baseDf = new LinkedHashMap<>();
        baseDf.put("type", "NUMBER");
        baseDf.put("decimals", 2);
        b.put("display_format", baseDf);
        base.add(b);

        Map<String, Object> ovDf = new LinkedHashMap<>();
        ovDf.put("type", "NUMBER");
        ovDf.put("decimals", 6);
        Map<String, Object> ov = new LinkedHashMap<>();
        ov.put("col_key", "AMT");
        ov.put("display_format", ovDf);

        List<Map<String, Object>> result =
                ExcelColumnResolver.mergeColumnOverrides(base, List.of(ov));

        @SuppressWarnings("unchecked")
        Map<String, Object> mergedDf = (Map<String, Object>) result.get(0).get("display_format");
        assertEquals(6, mergedDf.get("decimals"), "override decimals must win over base");
    }

    @Test
    void mergeColumnOverrides_overrideWithoutDisplayFormat_preservesBaseDecimals() {
        // An override that only flips hidden must NOT wipe a base column's display_format.decimals.
        List<Map<String, Object>> base = new ArrayList<>();
        Map<String, Object> b = col("FEE", "Fee", false);
        Map<String, Object> baseDf = new LinkedHashMap<>();
        baseDf.put("type", "NUMBER");
        baseDf.put("decimals", 3);
        b.put("display_format", baseDf);
        base.add(b);

        Map<String, Object> ov = new LinkedHashMap<>();
        ov.put("col_key", "FEE");
        ov.put("hidden", true); // no display_format key

        List<Map<String, Object>> result =
                ExcelColumnResolver.mergeColumnOverrides(base, List.of(ov));

        @SuppressWarnings("unchecked")
        Map<String, Object> mergedDf = (Map<String, Object>) result.get(0).get("display_format");
        assertNotNull(mergedDf, "base display_format must survive an override that omits it");
        assertEquals(3, mergedDf.get("decimals"));
        assertEquals(Boolean.TRUE, result.get(0).get("hidden"));
    }

    @Test
    void mergeColumnOverrides_nullOrEmptyOverrides_returnsBaseUnchanged() {
        List<Map<String, Object>> base = new ArrayList<>();
        base.add(col("A", "T", false));
        assertEquals(base, ExcelColumnResolver.mergeColumnOverrides(base, null));
        assertEquals(base, ExcelColumnResolver.mergeColumnOverrides(base, List.of()));
    }

    @Test
    void mergeColumnOverrides_unmatchedOverrideIgnored() {
        List<Map<String, Object>> base = new ArrayList<>();
        base.add(col("A", "T", false));
        Map<String, Object> ov = new LinkedHashMap<>();
        ov.put("col_key", "ZZZ"); // no base match
        ov.put("hidden", true);
        List<Map<String, Object>> result =
                ExcelColumnResolver.mergeColumnOverrides(base, List.of(ov));
        assertEquals(1, result.size());
        assertEquals(Boolean.FALSE, result.get(0).get("hidden")); // unchanged
    }

    @Test
    void isLegacyArrayConfig_distinguishesArrayVsObject() throws Exception {
        Object arr = MAPPER.readValue("[{\"col_key\":\"A\"}]", Object.class);
        Object obj = MAPPER.readValue(
                "{\"version\":2,\"excel_component_id\":\"x\",\"column_overrides\":[]}", Object.class);
        assertTrue(ExcelColumnResolver.isLegacyArrayConfig(arr));
        assertFalse(ExcelColumnResolver.isLegacyArrayConfig(obj));
        // null is not a legacy array
        assertFalse(ExcelColumnResolver.isLegacyArrayConfig(null));
    }
}
