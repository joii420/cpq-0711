package com.cpq.currency.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name="currency")
public class Currency extends PanacheEntityBase {
    @Id @Column(length=10) public String code;
    @Column(nullable=false,length=100) public String name;
    @Column(length=16) public String symbol;
    @Column(nullable=false) public Boolean enabled=true;
    @Column(name="is_base",nullable=false) public Boolean isBase=false;
    @Column(name="sort_order",nullable=false) public Integer sortOrder=0;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    @PrePersist public void prePersist(){var n=OffsetDateTime.now();if(createdAt==null)createdAt=n;updatedAt=n;}
    @PreUpdate public void preUpdate(){updatedAt=OffsetDateTime.now();}
}
