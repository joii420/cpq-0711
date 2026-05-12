package com.cpq.partversion.dto;

/**
 * 料号版本管理 B1: 导入预览阶段, 每个 (customer_product_no, hf_part_no) 的版本信息.
 *
 * <p>每次 Excel 导入预览时, BasicDataImportServiceV5 会扫描 ParsedBasicData
 * 涉及的料号集合, 为每个料号生成一个 PartVersionPreviewDTO 返给前端.
 * 前端 UI 展示当前/建议版本, 并允许用户选择 BUMP (升版) / NO_CHANGE (不动) /
 * SKIP (跳过该料号写入).
 *
 * <p>confirm 阶段, 前端把决策 Map<(cpn,hf), Action> 传回后端,
 * BasicDataImportServiceV5 按决策路由写库.
 */
public record PartVersionPreviewDTO(
        String customerProductNo,
        String hfPartNo,
        int currentVersion,
        int suggestedNewVersion,
        Action defaultAction,
        String diffSummary
) {
    public enum Action {
        /** 升版到 suggestedNewVersion (默认动作) */
        BUMP,
        /** 不升版 (写入数据时 part_version = currentVersion) */
        NO_CHANGE,
        /** 跳过该料号, 本次 Excel 涉及该 hf_part_no 的所有行都不写库 */
        SKIP
    }

    public static PartVersionPreviewDTO forBump(String cpn, String hf, int current) {
        return new PartVersionPreviewDTO(cpn, hf, current, current + 1, Action.BUMP, null);
    }
}
