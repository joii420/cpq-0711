package com.cpq.component.service;

import com.cpq.component.dto.RowKeyCandidatesResponse.Candidate;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ResolveRowKeyCandidatesTest {

    private Map<String,Object> f(String name, String type, String basicPath) {
        return Map.of("name", name, "field_type", type,
            "basic_data_path", basicPath == null ? "" : basicPath);
    }
    private Candidate byName(List<Candidate> cs, String n) {
        return cs.stream().filter(c -> n.equals(c.fieldName)).findFirst().orElseThrow();
    }

    @Test
    void inputTextEligibleWhenNoCollision() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo",
            List.of(f("material", "INPUT_TEXT", null)),
            Set.of("child_no", "elem"));
        Candidate c = byName(cs, "material");
        assertTrue(c.eligible);
        assertEquals("material", c.resolvedColumn);
        assertEquals("input", c.source);
    }

    @Test
    void inputNumberEligible() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo", List.of(f("seq", "INPUT_NUMBER", null)), Set.of("child_no"));
        assertTrue(byName(cs, "seq").eligible);
        assertEquals("input", byName(cs, "seq").source);
    }

    @Test
    void inputCollidingWithDriverColumnRejected() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo", List.of(f("elem", "INPUT_TEXT", null)), Set.of("child_no", "elem"));
        Candidate c = byName(cs, "elem");
        assertFalse(c.eligible);
        assertTrue(c.reason != null && c.reason.contains("撞名"));
    }

    @Test
    void formulaFieldStillIneligible() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo", List.of(f("amount", "FORMULA", null)), Set.of("child_no"));
        assertFalse(byName(cs, "amount").eligible);
    }

    @Test
    void driverBackedBasicDataStillEligibleWithDriverSource() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo",
            List.of(f("childNo", "BASIC_DATA", "$v_demo.child_no")),
            Set.of("child_no", "elem"));
        Candidate c = byName(cs, "childNo");
        assertTrue(c.eligible);
        assertEquals("child_no", c.resolvedColumn);
        assertEquals("driver", c.source);
    }
}
