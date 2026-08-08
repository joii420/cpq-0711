package com.cpq.quotation.dto;

import java.util.Map;

/**
 * task-0806 阶段① API-5：前端上报的单条对账差异。
 * 契约见 dev-docs/task-0806-报价编辑链路优化与前后端对账/api.md §API-5。
 *
 * <p>D2 强制：{@code frontendInputs}/{@code backendInputs} 只带该行行键字段 + 该公式引用到的字段，
 * 不要整行倾倒——服务端不校验这一点（信任前端），只原样落日志 + 存进进程内差异 Map（B1-3）。
 */
public class ReconcileDiffEntry {
    public String componentId;
    public String tabName;
    public String rowKey;
    public String fieldName;
    public Object frontendValue;
    public Object backendValue;
    public Map<String, Object> frontendInputs;
    public Map<String, Object> backendInputs;
}
