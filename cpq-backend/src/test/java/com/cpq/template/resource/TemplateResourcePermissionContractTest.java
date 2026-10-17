package com.cpq.template.resource;

import com.cpq.common.security.RoleAllowed;
import jakarta.ws.rs.DELETE;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Guards the user-approved role contract for published-template deletion. */
class TemplateResourcePermissionContractTest {

    @Test
    void deleteEndpoint_allTemplateManagementRolesAreAllowed() {
        var deleteMethod = Arrays.stream(TemplateResource.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DELETE.class))
                .findFirst()
                .orElseThrow();

        assertArrayEquals(
                new String[]{"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"},
                deleteMethod.getAnnotation(RoleAllowed.class).value()
        );
    }
}
