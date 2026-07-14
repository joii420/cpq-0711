package com.cpq.modelconfig.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 3D 模型配置的物理文件登记（task-0712 B5，新表 {@code model_config_file}）。
 *
 * <p>每条 {@link ModelConfig} 版本对应 1~2 条文件行（GLB 必有，THUMBNAIL 可选）。
 * DB 侧 {@code model_config_id} FK 为 {@code ON DELETE CASCADE}，删除 {@link ModelConfig}
 * 时子表自动级联清理（B508）。
 */
@Entity
@Table(name = "model_config_file")
public class ModelConfigFile extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "model_config_id", nullable = false)
    public UUID modelConfigId;

    @Column(name = "file_role", nullable = false, length = 20)
    public String fileRole;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    public String fileUrl;

    @Column(name = "file_size_bytes")
    public Long fileSizeBytes;

    @Column(name = "md5_hash", length = 64)
    public String md5Hash;

    @Column(name = "uploaded_at", nullable = false)
    public OffsetDateTime uploadedAt = OffsetDateTime.now();
}
