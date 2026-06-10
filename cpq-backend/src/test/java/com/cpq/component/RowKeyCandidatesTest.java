package com.cpq.component;

import com.cpq.component.dto.RowKeyCandidatesResponse.Candidate;
import com.cpq.component.service.ComponentDriverService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RowKeyCandidatesTest {

    private Map<String, Object> field(String name, String type, String basicPath) {
        return Map.of(
            "name", name,
            "field_type", type,
            "basic_data_path", basicPath == null ? "" : basicPath
        );
    }

    @Test
    void basicDataLeafInDriverColumns_isEligible() {
        var fields = List.of(field("子件", "BASIC_DATA", "{up_view[price_type='MATERIAL'].子件}"));
        var cols = Set.of("子件", "工序代码", "hf_part_no");
        List<Candidate> r = ComponentDriverService.resolveRowKeyCandidates("$up_view", fields, cols);
        assertEquals(1, r.size());
        assertTrue(r.get(0).eligible);
        assertEquals("子件", r.get(0).resolvedColumn);
        assertNull(r.get(0).reason);
    }

    @Test
    void leafNotInDriverColumns_notEligible() {
        var fields = List.of(field("材料重量", "BASIC_DATA", "{mat_part.unit_weight}"));
        var cols = Set.of("child_hf_part_no", "material_code");
        List<Candidate> r = ComponentDriverService.resolveRowKeyCandidates("$x", fields, cols);
        assertFalse(r.get(0).eligible);
        assertEquals("unit_weight", r.get(0).resolvedColumn);
        assertTrue(r.get(0).reason.contains("不取自 driver 行"));
    }

    @Test
    void inputFieldNoPath_eligibleAsInputSource() {
        // 2026-06-10 放开：INPUT_TEXT/INPUT_NUMBER 无 driver 列也可作行键（取手填值），source=input。
        var fields = List.of(field("人工工时", "INPUT_NUMBER", null));
        var cols = Set.of("child_hf_part_no");   // 字段名未撞 driver 列
        List<Candidate> r = ComponentDriverService.resolveRowKeyCandidates("$x", fields, cols);
        assertTrue(r.get(0).eligible);
        assertEquals("人工工时", r.get(0).resolvedColumn);
        assertEquals("input", r.get(0).source);
        assertNull(r.get(0).reason);
    }

    @Test
    void inputFieldCollidingDriverColumn_notEligible() {
        // 撞名：输入字段名 == 某 driver 列名 → 取值歧义，从源头排除。
        var fields = List.of(field("child_hf_part_no", "INPUT_TEXT", null));
        var cols = Set.of("child_hf_part_no", "material_code");
        List<Candidate> r = ComponentDriverService.resolveRowKeyCandidates("$x", fields, cols);
        assertFalse(r.get(0).eligible);
        assertTrue(r.get(0).reason.contains("撞名"));
    }

    @Test
    void noDeclaredColumns_notEligibleWithViewHint() {
        var fields = List.of(field("子件", "BASIC_DATA", "{up_view.子件}"));
        List<Candidate> r = ComponentDriverService.resolveRowKeyCandidates("$up_view", fields, Set.of());
        assertFalse(r.get(0).eligible);
        assertEquals("子件", r.get(0).resolvedColumn);
        assertTrue(r.get(0).reason.contains("SQL 视图"));
    }
}
