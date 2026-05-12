package com.cpq.importsession.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V6 导入会话主表实体。
 * 对应 import_session 表（V159 迁移创建）。
 *
 * <p>生命周期：
 *   PENDING → COMMITTED（commit 成功）
 *   PENDING → CANCELLED（用户主动取消 DELETE /sessions/{id}）
 *   PENDING → EXPIRED（24h 后由 scheduled job 清理）
 */
@Entity
@Table(name = "import_session")
public class ImportSession extends PanacheEntityBase {

    /** 主键 UUID，@PrePersist 时生成 */
    @Id
    public UUID id;

    /** 关联客户 ID（必填） */
    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    /** 操作用户 ID（上传时的当前登录用户） */
    @Column(name = "user_id")
    public UUID userId;

    /**
     * 会话状态。
     * PENDING：等待用户确认决策并 commit
     * COMMITTED：已成功 commit（staging → mat_* + 建报价单）
     * CANCELLED：用户主动取消（DELETE /sessions/{id}）
     * EXPIRED：超 expires_at 未 commit，由 scheduled job 标记或清理
     */
    @Column(nullable = false)
    public String status = "PENDING";

    /** 上传的原始 Excel 文件名 */
    @Column(name = "source_excel")
    public String sourceExcel;

    /** 会话创建时间 */
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    /** 会话过期时间（默认 created_at + 24h） */
    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    /** commit 完成时间（COMMITTED 状态时填充） */
    @Column(name = "committed_at")
    public OffsetDateTime committedAt;

    /**
     * PrePersist 钩子：初始化 id、createdAt、expiresAt。
     * expiresAt = createdAt + 24h（由业务逻辑保证，DB DEFAULT 兜底）。
     */
    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (expiresAt == null) {
            // 默认 24 小时 TTL
            expiresAt = createdAt.plusHours(24);
        }
    }
}
