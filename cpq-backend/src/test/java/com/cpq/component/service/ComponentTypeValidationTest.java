package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentTypeValidationTest {
    @Test
    void rejectsUnknownComponentType() {
        var ex = assertThrows(BusinessException.class,
            () -> ComponentService.assertValidComponentType("WEIRD"));
        assertTrue(ex.getMessage().contains("component_type"));
    }
    @Test
    void acceptsExcel() {
        assertDoesNotThrow(() -> ComponentService.assertValidComponentType("EXCEL"));
    }
}
