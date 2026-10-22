package com.cpq.common.exception;

import com.cpq.common.dto.ApiResponse;
import java.sql.SQLException;
import jakarta.ws.rs.core.Response;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionMapperTest {

    private final GlobalExceptionMapper mapper = new GlobalExceptionMapper();

    @Test
    void componentCodeDuplicateReturnsReadableMessage() {
        ApiResponse<?> response = map("component_code_key",
                "ERROR: duplicate key value violates unique constraint \"component_code_key\"\n"
                        + "Detail: Key (code)=(COMP-0001) already exists.");

        assertEquals(409, response.getCode());
        assertEquals("组件编码已存在，请更换组件编码后重试。", response.getMessage());
        assertFalse(response.getMessage().contains("Duplicate value for Key"));
    }

    @Test
    void componentSqlViewDuplicateReturnsReadableMessage() {
        ApiResponse<?> response = map("component_sql_view_component_id_sql_view_name_key",
                "ERROR: duplicate key value violates unique constraint\n"
                        + "Detail: Key (component_id, sql_view_name)=(..., material_view) already exists.");

        assertEquals("该组件的 SQL 视图名称已存在，请修改视图名称后重试。", response.getMessage());
    }

    @Test
    void otherDuplicateReturnsGenericReadableMessage() {
        ApiResponse<?> response = map("uq_customer_code",
                "ERROR: duplicate key value violates unique constraint \"uq_customer_code\"");

        assertEquals("数据已存在，不能重复保存，请检查后重试。", response.getMessage());
        assertFalse(response.getMessage().contains("constraint"));
    }

    @Test
    void otherConstraintFailuresAlsoDoNotExposeDatabaseText() {
        ApiResponse<?> foreignKeyResponse = map("fk_component_directory",
                "ERROR: insert or update on table \"component\" violates foreign key constraint");
        ApiResponse<?> notNullResponse = map("component_name_not_null",
                "ERROR: null value in column \"name\" violates not-null constraint");

        assertEquals("关联数据不存在或仍被使用，请刷新后重试。", foreignKeyResponse.getMessage());
        assertEquals("必填信息缺失，请补充完整后重试。", notNullResponse.getMessage());
    }

    private ApiResponse<?> map(String constraint, String sqlMessage) {
        ConstraintViolationException exception = new ConstraintViolationException(
                "constraint violation", new SQLException(sqlMessage), constraint);
        try (Response response = mapper.handleHibernateConstraint(exception)) {
            assertNotNull(response.getEntity());
            return (ApiResponse<?>) response.getEntity();
        }
    }
}
