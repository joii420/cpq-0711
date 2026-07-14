package com.cpq.configure.dto;

import java.math.BigDecimal;

/**
 * 组合工艺候选 DTO — task-0712 B6（架构决策 2-2A 定稿）。
 *
 * <p>数据源收敛到工序库 {@code process_master WHERE process_category='ASSEMBLY'}
 * （现网实值，4 行：总装配/部件装配/螺栓连接/焊接装配），不再读 {@code composite_process_def}
 * （该表保留给 v0.4 configurator，选配侧解绑）。
 *
 * <p><b>标识锚点 = {@code process_master.process_no}</b>（本 DTO 的 {@link #code}），
 * 与候选端点 / 前端选择值 / 指纹 CPROC / {@code capacity.process_no} /
 * {@code quotation_line_composite_process.def_code} 五处一致（AP-44 精神，PR 自检硬项）。
 *
 * <p>放弃 {@code param_schema} 参数化（业务已确认 2026-07-14）：无 icon/paramSchema 字段。
 */
public class CompositeProcessCandidateDTO {
    /** process_master.process_no（工序编号，如 MRO-AS-0001）。 */
    public String code;
    /** process_master.process_name（如 总装配）。 */
    public String name;
    /** process_master.standard_currency；ASSEMBLY 现网 4 行全空，落库侧兜底 CNY（本 DTO 原样透传，不兜底）。 */
    public String currency;
    /** process_master.standard_unit；ASSEMBLY 现网 4 行全空。 */
    public String unit;
    /** process_master.default_defect_rate；ASSEMBLY 现网 4 行全空。 */
    public BigDecimal defectRate;
}
