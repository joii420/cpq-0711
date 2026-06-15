package com.cpq.formula.predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConditionPredicateJsonValidateTest {
    private final ObjectMapper M = new ObjectMapper();

    @Test void bad_operand_kind_rejected() throws Exception {
        var json = M.readTree("{\"op\":\"=\",\"lhs\":{\"kind\":\"WRONG\",\"field\":\"x\"},"
            + "\"rhs\":{\"kind\":\"literal\",\"value\":\"y\"}}");
        assertThrows(IllegalArgumentException.class, () -> ConditionPredicateJson.fromJson(json));
    }

    @Test void valid_predicate_accepted() throws Exception {
        var json = M.readTree("{\"op\":\">\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"金额\"},"
            + "\"rhs\":{\"kind\":\"literal\",\"value\":\"1000\"}}");
        assertNotNull(ConditionPredicateJson.fromJson(json));
    }
}
