package com.cpq.component.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CostingTreeSqlValidatorTest {

    @Inject
    CostingTreeSqlValidator validator;

    @Test
    void rejectsMissingProductionPartNosVar() {
        var r = validator.validate("SELECT p AS root_no, p AS material_no, NULL AS bom_version, NULL AS parent_no FROM unnest(ARRAY['x']) p");
        assertFalse(r.ok, r.message);
        assertTrue(r.message.contains("production_part_nos"));
    }

    @Test
    void rejectsMissingColumn() {
        var r = validator.validate("SELECT p AS root_no, p AS material_no FROM unnest(:production_part_nos) p");
        assertFalse(r.ok, r.message);
    }

    @Test
    void acceptsValidRecursiveSql() {
        String sql = "SELECT p AS root_no, p AS material_no, CAST(NULL AS text) AS bom_version, CAST(NULL AS text) AS parent_no FROM unnest(:production_part_nos) p";
        var r = validator.validate(sql);
        assertTrue(r.ok, r.message);
    }
}
