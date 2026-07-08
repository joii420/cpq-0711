package com.cpq.seltemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "sel_template_item")
public class SelTemplateItem extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "template_id", nullable = false) public UUID templateId;
    @Column(name = "param_type_code", nullable = false, length = 30) public String paramTypeCode;
    @Column(nullable = false) public Boolean enabled = true;
    @Column(name = "sort_order", nullable = false) public Integer sortOrder = 0;
}
