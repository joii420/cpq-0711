package com.cpq.component.service;

import com.cpq.component.dto.DryRunSqlViewResponse;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SqlViewValidatorSpineKeysTest {

    @Inject SqlViewValidator validator;

    @Test
    void spineKeysMacro_validatesAndDoesNotLeakPlaceholders() {
        // 一个简单可 EXPLAIN 的 SELECT，WHERE 用 :spineKeys 过滤一个 VALUES 派生表
        String sql = "SELECT t.part AS hf_part_no, t.parent, t.ver "
                + "FROM (VALUES ('A','P','V1')) AS t(part, parent, ver) "
                + "WHERE :spineKeys(t.part, t.parent, t.ver)";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertTrue(resp.success, "含 :spineKeys 的 SQL 应通过校验, error=" + resp.error);
        List<String> vars = resp.requiredVariables == null ? List.of() : resp.requiredVariables;
        assertFalse(vars.contains("spineKeys"), "required_variables 不应含 spineKeys");
        assertFalse(vars.stream().anyMatch(v -> v.startsWith("__sk")), "required_variables 不应含 __sk* 内部占位符");
    }

    @Test
    void malformedSpineKeys_failsGracefully() {
        String sql = "SELECT 1 AS x WHERE :spineKeys(a, b)"; // 仅 2 实参
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertFalse(resp.success, "实参数量错误应校验失败");
        assertTrue(resp.error != null && resp.error.contains("spineKeys"), "错误信息应点明 spineKeys: " + resp.error);
    }

    @Test
    void reservedPrefix_rejected() {
        String sql = "SELECT 1 AS x WHERE :__skP IS NOT NULL";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertFalse(resp.success, "作者自用 :__sk* 应被拒绝");
        assertTrue(resp.error != null && resp.error.contains("__sk"), "错误应点明 __sk: " + resp.error);
    }
}
