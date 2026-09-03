package com.cpq.dataset.service;

/**
 * api.md §1 的 {@code reason} <b>封闭集</b>。
 *
 * <p>🚫 禁止在别处直接写字面量：前端 F-9 的 {@code <ValidationErrorTable>} 与测试都按这些
 * 文案逐字断言（AC-6 ~ AC-10、AC-34、AC-40），改一个字就是契约变更，必须走
 * {@code task-docs.md §4} 四步，不能自行改。
 */
public final class DatasetValidationReasons {

    public static final String REQUIRED_EMPTY = "必填项为空";
    public static final String AXIS_EMPTY = "轴列不可为空";
    public static final String MASTER_MISSING = "主数据不存在";
    /**
     * D-19（2026-09-03 用户裁决，开工后契约变更）：报价「客户料号」sheet 的<b>客户编号严格校验</b> ——
     * 不在 {@code customer.code} 中就整份拒收（AC-45 / AC-46）。
     * 文案与 {@link #MASTER_MISSING} 刻意区分：让用户直接看出「要先去客户管理里建档」。
     * <p>由后端 #2 在 B-6 落地时追加（api.md §1 封闭集已同步更新）。
     */
    public static final String CUSTOMER_NOT_REGISTERED = "客户编号未在客户档案中登记";
    /**
     * D-24（2026-09-03 用户裁决）：带版本 sheet 的<b>每个轴值</b>必须已在同数据集的<b>物料表</b>登记，
     * 否则整份拒收（AC-52）。
     *
     * <p>为什么必须拦：维护页列表的数据源就是物料表（api.md §3），轴值没登记 ⇒ 数据落了库
     * 但<b>在界面上完全不可见</b>（列表无该行 → 抽屉打不开 → 9/17 个 tab 一个都看不到）。
     * 这类「导入成功但看不见」比直接报错危险得多。
     * <p>由后端 #2 在 B-6 落地（api.md §1 封闭集已同步更新）。
     */
    public static final String AXIS_NOT_REGISTERED = "轴值未在物料表登记";
    public static final String NOT_A_NUMBER = "不是合法数值";
    public static final String NOT_AN_INTEGER = "不是合法整数";

    private DatasetValidationReasons() {}

    /** {@code 超出长度上限 {n}}（AC-40：🚫 不允许静默截断）。 */
    public static String tooLong(int max) {
        return "超出长度上限 " + max;
    }

    /** {@code sheet「{名}」不属于{数据集中文名}数据集}（AC-34）。 */
    public static String sheetNotInDataset(String sheetName, String datasetName) {
        return "sheet「" + sheetName + "」不属于" + datasetName + "数据集";
    }

    /** {@code 表头列名与规范不一致：缺少「{列名}」}。 */
    public static String headerMissing(String label) {
        return "表头列名与规范不一致：缺少「" + label + "」";
    }
}
