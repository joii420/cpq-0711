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
        String sql = "SELECT p AS root_no, p AS material_no, CAST(NULL AS text) AS bom_version, CAST(NULL AS text) AS parent_no, p AS node_path FROM unnest(:production_part_nos) p";
        var r = validator.validate(sql);
        assertTrue(r.ok, r.message);
    }

    /**
     * 核价树版本切换（task-0713 B3）依赖递归 SQL 里的 {@code :versionFilter} 宏；保存期 dry-run
     * 必须先按 {@link com.cpq.datasource.sqlview.VersionFilterMacro#expandForValidation} 展开
     * （展开为 {@code (is_current列)}），否则 {@code :versionFilter(...)} 原文直接发给 PG →
     * {@code syntax error at or near ":"} → 含宏的树配置一律存不进去。
     *
     * <p>实证背景：{@code cpq_db_0724} 里 active 的「核价BOM树-PRICING口径v1(versionFilter 版本感知)」
     * 就含本宏且运行期正常，但走本校验器必被拒 —— 存量配置改一个字都保存不了。
     * 对照组：页签视图校验器 {@code SqlViewValidator:151-152} 早已正确调用两个 expandForValidation。
     */
    @Test
    void acceptsVersionFilterMacroInRecursiveSql() {
        String sql = "SELECT p AS root_no, p AS material_no, CAST(NULL AS text) AS bom_version, "
                + "CAST(NULL AS text) AS parent_no, p AS node_path "
                + "FROM unnest(:production_part_nos) p WHERE :versionFilter(TRUE, 'v1', p)";
        var r = validator.validate(sql);
        assertTrue(r.ok, r.message);
    }

    /**
     * 宏语法写错（实参数量不对/缺括号）时 {@code expandForValidation} 抛
     * {@link IllegalArgumentException}；保存端点必须收到「校验失败 + 原因」的 Result，
     * 而不是让异常冒泡成 500。
     */
    @Test
    void rejectsMalformedVersionFilterMacroWithoutThrowing() {
        String sql = "SELECT p AS root_no, p AS material_no, CAST(NULL AS text) AS bom_version, "
                + "CAST(NULL AS text) AS parent_no, p AS node_path "
                + "FROM unnest(:production_part_nos) p WHERE :versionFilter(TRUE)";
        var r = validator.validate(sql);
        assertFalse(r.ok, r.message);
        assertTrue(r.message.contains("versionFilter"), "报错须点名 versionFilter，实得: " + r.message);
    }

    /**
     * 防「修复过头」护栏：展开宏之后，五列必需性等既有契约一律不得被放行。
     */
    @Test
    void stillRejectsMissingColumnWhenMacroPresent() {
        String sql = "SELECT p AS root_no, p AS material_no "
                + "FROM unnest(:production_part_nos) p WHERE :versionFilter(TRUE, 'v1', p)";
        var r = validator.validate(sql);
        assertFalse(r.ok, r.message);
        assertTrue(r.message.contains("缺输出列"), "应报缺列，实得: " + r.message);
    }
}
