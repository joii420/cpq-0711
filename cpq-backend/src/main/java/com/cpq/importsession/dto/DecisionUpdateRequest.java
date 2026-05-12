package com.cpq.importsession.dto;

import java.util.List;

/**
 * PUT /sessions/{id}/decisions 请求体。
 * 前端 Step 2 用户切换 BUMP/NO_BUMP 或处理冲突/孤儿行时（debounce 500ms）发送。
 * 单次请求可携带多条决策更新（批量 upsert，幂等）。
 */
public class DecisionUpdateRequest {

    /** 决策条目列表（可批量，前端一次 debounce 可带多条） */
    public List<DecisionEntry> decisions;

    /**
     * 单条决策更新条目。
     * 与 import_session_decision 表的复合 PK 对应：
     *   (importSessionId 来自 URL path, decisionType, decisionKey) 三元组定位行。
     */
    public static class DecisionEntry {
        /**
         * 决策类型，取值：PART_VERSION / CUSTOMER_CONFLICT / ORPHAN。
         */
        public String decisionType;

        /**
         * 决策业务键，格式因 decisionType 不同而异：
         *   PART_VERSION: "{customerProductNo}|{hfPartNo}"
         *   CUSTOMER_CONFLICT: "{conflictType}|{primaryKey}"
         *   ORPHAN: "{sheetCode}|{rowIndex}"
         */
        public String decisionKey;

        /**
         * 决策值（原始 JSON 字符串，直接写入 decision_value JSONB 列）。
         * 示例：
         *   PART_VERSION BUMP: {"action":"BUMP","currentVersion":2000,"suggestedVersion":2001}
         *   PART_VERSION NO_BUMP: {"action":"NO_BUMP","currentVersion":2000}
         *   CUSTOMER_CONFLICT: {"action":"USE_EXCEL"}
         *   ORPHAN: {"action":"DISCARD"}
         */
        public String decisionValueJson;
    }
}
