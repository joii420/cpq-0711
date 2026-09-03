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

    /**
     * task-260902 · B-22（V402）：<b>工艺顺序</b>，写入时按请求 {@code processNos} 数组下标 +1 赋值。
     *
     * <p>本表原先<b>一个顺序列都没有</b>（只有 id / line_item_id / process_id / process_no），
     * 读出点只能 {@code ORDER BY id}（{@code gen_random_uuid()} ⇒ 随机），
     * 而 AC-11 断言「工序顺序回填」、AC-19④ 要显示第一次的顺序
     * ⇒ 不加这一列，那两条 AC 会「今天绿、下周红」（堆表 UPDATE 后物理顺序还会变）。
     * 🚨 <b>所有读出点必须 {@code ORDER BY seqNo}</b>。
     */
    @Column(name = "seq_no")
    public Integer seqNo;
}
