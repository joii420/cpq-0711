package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 元素/组成项字典（task-0708 · B1）。
 *
 * <p>{@code elementCode} 存<b>符号</b>（Ag/Cu/SnO2/H70…），是定价 join 键，必须与
 * {@code costing_element_price.element_code} 对齐；不是 Excel 的数字"元素编号"。
 * 本期无独立 CRUD UI，仅由 seed（V316）+ 材质库导入按符号 upsert 维护。
 */
@Entity
@Table(name = "element")
public class Element extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /** 元素符号（定价 join 键，UNIQUE） */
    @Column(name = "element_code")
    public String elementCode;

    /** 中文名（字典命中中文，否则回退=符号） */
    @Column(name = "element_name")
    public String elementName;

    /** Excel 元素编号（内部字典号，留存备用，当前无消费方） */
    @Column(name = "element_no")
    public String elementNo;

    public String status;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;
}
