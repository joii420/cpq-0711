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
import java.util.UUID;

@Entity
@Table(name = "composite_process_def")
public class CompositeProcessDef extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    public String code;
    public String name;
    public String icon;
    public String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_schema", columnDefinition = "jsonb")
    public String paramSchema = "[]";

    @Column(name = "sort_order")
    public int sortOrder;

    public String status;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    public static CompositeProcessDef findByCodeOrThrow(String code) {
        CompositeProcessDef d = find("code = ?1 AND status = 'ACTIVE'", code).firstResult();
        if (d == null) throw new IllegalArgumentException("组合工艺未找到或未激活: " + code);
        return d;
    }
}
