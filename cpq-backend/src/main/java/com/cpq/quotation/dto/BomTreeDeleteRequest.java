package com.cpq.quotation.dto;

import java.util.UUID;

/**
 * task-0721 B7：删除影响面预览（api.md §4）与执行删除（api.md §5）共用请求体。
 * 执行删除额外携带 {@code previewToken}（预览接口未使用该字段）。
 */
public class BomTreeDeleteRequest {
    public UUID componentId;
    /** PRUNE=剪枝(整枝) | ROW=行删除。 */
    public String mode;
    /** PRUNE=被剪节点 __nodeId；ROW=被删行所在节点 __nodeId。 */
    public String nodeId;
    /** mode=ROW 时必填：该行的 rowKey（= __nodeId 前缀的完整 effKey，与卡片渲染同源）。 */
    public String rowKey;
    /** 仅执行删除接口使用：预览接口返回的令牌，防止预览与执行之间数据漂移。 */
    public String previewToken;
}
