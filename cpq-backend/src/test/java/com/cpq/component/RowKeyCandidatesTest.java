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
    void inputFieldNoPath_notEligible() {
        var fields = List.of(field("人工工时", "INPUT_NUMBER", null));
        var cols = Set.of("child_hf_part_no");
        List<Candidate> r = ComponentDriverService.resolveRowKeyCandidates("$x", fields, cols);
        assertFalse(r.get(0).eligible);
        assertNull(r.get(0).resolvedColumn);
        assertTrue(r.get(0).reason.contains("无 driver 列"));
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
