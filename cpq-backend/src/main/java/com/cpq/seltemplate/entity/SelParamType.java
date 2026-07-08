package com.cpq.seltemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sel_param_type")
public class SelParamType extends PanacheEntityBase {
    @Id @Column(length = 30) public String code;
    @Column(nullable = false, length = 50) public String name;
    @Column(name = "value_mode", nullable = false, length = 20) public String valueMode;
    @Column(name = "data_source_key", length = 50) public String dataSourceKey;
    @Column(name = "persist_handler_key", length = 50) public String persistHandlerKey;
    @Column(name = "sort_order", nullable = false) public Integer sortOrder = 0;
}
