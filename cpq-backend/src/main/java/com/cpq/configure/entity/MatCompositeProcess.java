package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "mat_composite_process")
public class MatCompositeProcess extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /** 父料号 (V166 中 parent_hf_part_no 已重命名为 hf_part_no 对齐 ImplicitJoinRewriter) */
    @Column(name = "hf_part_no")
    public String hfPartNo;

    @Column(name = "def_code")
    public String defCode;

    @Column(name = "seq_no")
    public int seqNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "participating_parts", columnDefinition = "jsonb")
    public List<String> participatingParts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_values", columnDefinition = "jsonb")
    public Map<String, Object> paramValues;

    @Column(name = "part_version")
    public int partVersion = 2000;

    @Column(name = "is_current")
    public boolean isCurrent = true;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "created_by")
    public UUID createdBy;
}
