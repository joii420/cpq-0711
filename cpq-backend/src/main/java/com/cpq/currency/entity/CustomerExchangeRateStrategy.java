package com.cpq.currency.entity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="customer_exchange_rate_strategy")
public class CustomerExchangeRateStrategy extends PanacheEntityBase {
 @Id @Column(name="customer_id") public UUID customerId;
 @Column(nullable=false,length=32) public String method;
 @Column(name="window_num") public Integer windowNum;
 @Column(name="window_unit",length=16) public String windowUnit;
 @Column(name="specific_date") public LocalDate specificDate;
 @Column(name="day_of_month") public Integer dayOfMonth;
 @Column(nullable=false) public Integer version=1;
 @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
 @Column(name="updated_by") public UUID updatedBy;
 @PrePersist @PreUpdate public void touch(){updatedAt=OffsetDateTime.now();}
}
