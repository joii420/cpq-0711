package com.cpq.component;

import com.cpq.component.service.ComponentService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: 组件行键（rowKeyFields）校验规则。
 * 对应报价单整份快照 Phase 1 设计 §5.1。
 */
@QuarkusTest
@DisplayName("RowKeyValidationTest — 组件行键校验（Phase 1 §5.1）")
public class RowKeyValidationTest {

    @Inject
    ComponentService svc;

    // -----------------------------------------------------------------------
    // T1: 含可编辑字段的多行组件未声明 rowKeyFields → 新建路径必须被拦截
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T1: 多行可编辑组件无 rowKeyFields → hard=true 抛异常含 'rowKeyFields'")
    void multiRowEditableComponent_withoutRowKey_isRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                svc.validateRowKeyConfig(
                        "$cz_view",
                        "[{\"name\":\"jgf\",\"field_type\":\"INPUT_NUMBER\"}]",
                        null,
                        true));
        assertTrue(ex.getMessage().contains("rowKeyFields"),
                "异常消息应含 'rowKeyFields'，实际: " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // T2: 声明了合法 rowKeyFields（字段名存在于 fields）→ 通过
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T2: 多行可编辑组件有合法 rowKeyFields → 通过")
    void multiRowEditableComponent_withValidRowKey_passes() {
        assertDoesNotThrow(() ->
                svc.validateRowKeyConfig(
                        "$cz_view",
                        "[{\"name\":\"material_no\",\"field_type\":\"BASIC_DATA\"},{\"name\":\"jgf\",\"field_type\":\"INPUT_NUMBER\"}]",
                        "[\"material_no\"]",
                        true));
    }

    // -----------------------------------------------------------------------
    // T3(方案A): rowKeyFields 引用 driverRow 底层列(不在 fields 中文名里)→ 接受
    //   行键与 fields 展示名属不同命名空间(driverRow 是 expand 运行期 key), 配置期不校验存在性。
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T3: rowKeyFields 引用 driverRow 底层列(非 fields 名)→ 接受")
    void rowKeyWithDriverRowColumn_notInFields_isAccepted() {
        assertDoesNotThrow(() ->
                svc.validateRowKeyConfig(
                        "$cz_view",
                        "[{\"name\":\"jgf\",\"field_type\":\"INPUT_NUMBER\"}]",
                        "[\"child_hf_part_no\",\"material_code\"]",
                        true));
    }

    // T3b: rowKeyFields 含空字符串 key → hard=true 抛异常
    @Test
    @DisplayName("T3b: rowKeyFields 含空 key → hard=true 抛异常")
    void rowKeyWithBlankKey_isRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                svc.validateRowKeyConfig(
                        "$cz_view",
                        "[{\"name\":\"jgf\",\"field_type\":\"INPUT_NUMBER\"}]",
                        "[\"\"]",
                        true));
        assertTrue(ex.getMessage().contains("空 key"),
                "异常消息应提示空 key，实际: " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // T4: 豁免场景 — 无 dataDriverPath（单行/固定）或无可编辑字段（纯只读）
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T4: 无 dataDriverPath 或无可编辑字段 → 豁免，不抛")
    void readonlyOrSingleRowComponent_withoutRowKey_passes() {
        // 无 dataDriverPath（单行/固定）→ 豁免
        assertDoesNotThrow(() ->
                svc.validateRowKeyConfig(
                        null,
                        "[{\"name\":\"jgf\",\"field_type\":\"INPUT_NUMBER\"}]",
                        null,
                        true));

        // 有 driver 但无可编辑字段（纯只读 BASIC_DATA）→ 豁免
        assertDoesNotThrow(() ->
                svc.validateRowKeyConfig(
                        "$cz_view",
                        "[{\"name\":\"price\",\"field_type\":\"BASIC_DATA\"}]",
                        null,
                        true));
    }

    // -----------------------------------------------------------------------
    // T5: 哨兵 ["__seq_no__"] → 显式豁免，直接通过
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T5: rowKeyFields=[\"__seq_no__\"] 哨兵 → 豁免，不抛")
    void sentinelRowKey_passesAsExplicitExemption() {
        assertDoesNotThrow(() ->
                svc.validateRowKeyConfig(
                        "$fee_view",
                        "[{\"name\":\"fee\",\"field_type\":\"INPUT_NUMBER\"}]",
                        "[\"__seq_no__\"]",
                        true));
    }

    // -----------------------------------------------------------------------
    // T6: 更新路径 hard=false → 即使违规也不抛，仅 LOG.warn
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T6: update 路径 hard=false → 违规不抛，仅告警")
    void update_softValidation_doesNotThrow_evenWhenInvalid() {
        assertDoesNotThrow(() ->
                svc.validateRowKeyConfig(
                        "$cz_view",
                        "[{\"name\":\"jgf\",\"field_type\":\"INPUT_NUMBER\"}]",
                        null,
                        false));
    }
}
