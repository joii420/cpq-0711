package com.cpq.component.service;

import com.cpq.component.dto.DryRunSqlViewResponse;
import com.cpq.datasource.sqlview.SqlViewExecutor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0725 根因 2 —— {@link SqlViewValidator}（dry-run/校验通路）的注释屏蔽自测。
 *
 * <p>backtask T1「站点 5：SqlViewValidator 共 4 处」覆盖：
 * <ul>
 *   <li>{@code :122} 裸 sqlTemplate 检查 {@code :hfPartNo}</li>
 *   <li>{@code :129} 裸检查 {@code :__sk}</li>
 *   <li>{@code :135} 裸检查 {@code :__vf}</li>
 *   <li>{@code :66} NAMED_PARAM 提取正则（经 {@link SqlViewValidator#extractNamedParams}）</li>
 * </ul>
 * 验收点 4：注释里写 {@code :hfPartNo}/{@code :__sk}/{@code :__vf} 不再导致保存被拒。
 * 验收点 5：dry-run 通路（本类）与执行通路（{@link SqlViewExecutor}）对同一 SQL 的占位符清单完全一致。
 */
@QuarkusTest
class SqlViewValidatorCommentMaskingTest {

    @Inject SqlViewValidator validator;

    @Inject SqlViewExecutor executor;

    @Test
    void commentContainingHfPartNo_doesNotFailValidation() {
        String sql = "SELECT 1 AS dummy\n" +
            "-- 说明：请勿使用 :hfPartNo 标量占位符，应改用 :hfPartNos 数组形式\n";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertTrue(resp.success, () -> "注释里出现 :hfPartNo 不应导致保存被拒，实际失败原因: " + resp.error);
    }

    @Test
    void commentContainingSkReservedPrefix_doesNotFailValidation() {
        String sql = "SELECT 1 AS dummy\n" +
            "-- 说明：:__sk 前缀是 spineKeys 宏内部保留占位符，禁止自定义\n";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertTrue(resp.success, () -> "注释里出现 :__sk 不应导致保存被拒，实际失败原因: " + resp.error);
    }

    @Test
    void commentContainingVfReservedPrefix_doesNotFailValidation() {
        String sql = "SELECT 1 AS dummy\n" +
            "-- 说明：:__vf 前缀是 versionFilter 宏内部保留占位符，禁止自定义\n";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertTrue(resp.success, () -> "注释里出现 :__vf 不应导致保存被拒，实际失败原因: " + resp.error);
    }

    @Test
    void blockCommentContainingAllThreeReservedTokens_doesNotFailValidation() {
        String sql = "SELECT 1 AS dummy\n" +
            "/* 多行说明：\n" +
            "   :hfPartNo 标量占位符已禁用\n" +
            "   :__sk 与 :__vf 是宏内部保留前缀\n" +
            " */\n";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertTrue(resp.success, () -> "块注释里出现三个保留 token 不应导致保存被拒，实际失败原因: " + resp.error);
    }

    @Test
    void realHfPartNoScalarInBody_stillRejected_notOverMasked() {
        // 反向断言：真正在正文（非注释）里用 :hfPartNo 标量占位符，仍必须被拒绝——
        // 证明本次修复只是"不误伤注释"，不是把检查整体关掉。
        String sql = "SELECT * FROM material_master WHERE material_no = :hfPartNo";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertFalse(resp.success, "正文里真实使用 :hfPartNo 标量占位符仍应被拒绝");
    }

    @Test
    void realSkPrefixInBody_stillRejected_notOverMasked() {
        String sql = "SELECT * FROM material_master WHERE material_no = :__skCustom";
        DryRunSqlViewResponse resp = validator.validate(sql);
        assertFalse(resp.success, "正文里真实使用 :__sk 前缀仍应被拒绝");
    }

    @Test
    void dryRunPath_andExecutorPath_extractSameNamedParams_forCommentHeavySql() {
        String sql =
            "-- 顶部说明：:customerCode 出现在注释里，不应被两条通路中的任何一条识别为占位符\n" +
            "SELECT mm.material_no AS hf_part_no\n" +
            "/* 块注释：:partNo 同样不应被识别 */\n" +
            "FROM material_master mm\n" +
            "WHERE mm.material_no = ANY(:hfPartNos)\n" +
            "  AND mm.material_no <> ':literalNotAToken'\n";

        List<String> fromValidator = validator.extractNamedParams(sql);
        List<String> fromExecutor = executor.extractNamedParams(sql);

        assertEquals(fromExecutor, fromValidator,
            "dry-run 通路（SqlViewValidator）与执行通路（SqlViewExecutor）对同一 SQL 的占位符清单必须完全一致");
        assertEquals(List.of("hfPartNos"), fromValidator,
            "注释/字面量内的 token 均不计入，只剩正文里真实的 1 个 :hfPartNos");
    }
}
