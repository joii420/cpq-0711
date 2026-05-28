package com.cpq.featurelibrary.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "cpq_feature_field",
       uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "code"}))
public class FeatureField extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "group_id", nullable = false)
    public Long groupId;

    @Column(nullable = false, length = 40)
    public String code;

    @Column(nullable = false, length = 255)
    public String name;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "data_type", nullable = false, length = 20)
    public String dataType;  // STRING / NUMBER / DATE / BOOLEAN

    @Column(name = "assign_mode", nullable = false, length = 20)
    public String assignMode;  // MANUAL / SELECT / COMPUTED

    @Column(name = "is_required", nullable = false)
    public Boolean isRequired = false;

    @Column(name = "default_value", length = 255)
    public String defaultValue;

    @Column(name = "min_value", length = 40)
    public String minValue;

    @Column(name = "max_value", length = 40)
    public String maxValue;

    @Column(name = "code_length")
    public Integer codeLength;

    @Column(name = "decimal_places")
    public Integer decimalPlaces;

    @Column(name = "data_source_ref", length = 80)
    public String dataSourceRef;

    @Column(name = "partno_prefix", length = 20)
    public String partnoPrefix;

    @Column(name = "partno_suffix", length = 20)
    public String partnoSuffix;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_attrs", columnDefinition = "JSONB")
    public Map<String, Object> extraAttrs;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
