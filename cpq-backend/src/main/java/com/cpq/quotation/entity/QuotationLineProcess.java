package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "quotation_line_process")
public class QuotationLineProcess extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "line_item_id", nullable = false)
    public UUID lineItemId;

    @Column(name = "process_id", nullable = false)
    public UUID processId;
}
