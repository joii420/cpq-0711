package com.cpq.importsession.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * V6 导入会话决策表实体。
 * 对应 import_session_decision 表（V159 迁移创建）。
 *
 * <p>复合主键：(importSessionId, decisionType, decisionKey)
 *
 * <p>decision_type 取值：PART_VERSION / CUSTOMER_CONFLICT / ORPHAN
 * <p>decision_key 格式：
 *   PART_VERSION："{customerProductNo}|{hfPartNo}"
 *   CUSTOMER_CONFLICT："{conflictType}|{primaryKey}"
 *   ORPHAN："{sheetCode}|{rowIndex}"
 *
 * <p>decision_value JSONB 示例：
 *   PART_VERSION：{"action":"BUMP","currentVersion":2000,"suggestedVersion":2001,"appliedVersion":null}
 *   CUSTOMER_CONFLICT：{"action":"USE_EXCEL"}
 *   ORPHAN：{"action":"DISCARD"}
 */
@Entity
@Table(name = "import_session_decision")
@IdClass(ImportSessionDecision.PK.class)
public class ImportSessionDecision extends PanacheEntityBase {

    /** 关联的 import_session.id（复合 PK 第一部分） */
    @Id
    @Column(name = "import_session_id", nullable = false)
    public UUID importSessionId;

    /** 决策类型（复合 PK 第二部分） */
    @Id
    @Column(name = "decision_type", nullable = false)
    public String decisionType;

    /** 决策业务键（复合 PK 第三部分） */
    @Id
    @Column(name = "decision_key", nullable = false)
    public String decisionKey;

    /**
     * 决策值 JSONB。
     * 内容按 decisionType 不同而异：
     *   PART_VERSION: {"action":"BUMP/NO_BUMP/NEW","currentVersion":N,"suggestedVersion":N+1,"appliedVersion":null}
     *   CUSTOMER_CONFLICT: {"action":"USE_EXCEL/USE_DB/SKIP"}
     *   ORPHAN: {"action":"LINK_EXISTING/CREATE_NEW/DISCARD","targetPartId":"..."}
     * appliedVersion 在 commit 时由 StagingMerger 回填。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_value", columnDefinition = "jsonb", nullable = false)
    public String decisionValue;

    // ── 静态内部类：复合主键 ──────────────────────────────────────────────────

    /**
     * @IdClass 复合主键。需实现 Serializable + equals + hashCode。
     */
    public static class PK implements Serializable {

        public UUID importSessionId;
        public String decisionType;
        public String decisionKey;

        public PK() {}

        public PK(UUID importSessionId, String decisionType, String decisionKey) {
            this.importSessionId = importSessionId;
            this.decisionType = decisionType;
            this.decisionKey = decisionKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(importSessionId, pk.importSessionId)
                    && Objects.equals(decisionType, pk.decisionType)
                    && Objects.equals(decisionKey, pk.decisionKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(importSessionId, decisionType, decisionKey);
        }
    }
}
