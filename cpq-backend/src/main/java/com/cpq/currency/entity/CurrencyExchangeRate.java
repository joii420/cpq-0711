package com.cpq.currency.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="currency_exchange_rate")
public class CurrencyExchangeRate extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name="currency_code",nullable=false,length=10) public String currencyCode;
    @Column(nullable=false,precision=26,scale=12) public BigDecimal rate;
    @Column(name="effective_date",nullable=false) public LocalDate effectiveDate;
    @Column(length=200) public String source;
    @Column(nullable=false,length=16) public String status="ACTIVE";
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    @Column(name="created_by") public UUID createdBy;
    @PrePersist public void prePersist(){var n=OffsetDateTime.now();if(createdAt==null)createdAt=n;if(updatedAt==null)updatedAt=n;}
    @PreUpdate public void preUpdate(){updatedAt=OffsetDateTime.now();}
}
