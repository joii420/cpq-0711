package com.cpq.seltemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "sel_template_item_value")
public class SelTemplateItemValue extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "item_id", nullable = false) public UUID itemId;
    @Column(name = "allowed_value_key", nullable = false, length = 100) public String allowedValueKey;
}
