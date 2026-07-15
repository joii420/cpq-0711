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

    /**
     * task-0712 缺口1(工序 id 契约修复, V336 加法式变体): 遗留列, 现允许 NULL。
     * 新写路径(ConfigureProductService)不再填此列, 只填 {@link #processNo}。
     * 保留(不删)供收缩阶段迁移前的过渡兼容; 收缩阶段(合并 master 时)另做迁移删除。
     */
    @Column(name = "process_id")
    public UUID processId;

    /**
     * task-0712 缺口1(工序 id 契约修复, 方案A锚点): {@code process_master.process_no}。
     * FK -> process_master(process_no)（V336）。全链权威标识, 取代 {@link #processId}。
     */
    @Column(name = "process_no")
    public String processNo;
}
