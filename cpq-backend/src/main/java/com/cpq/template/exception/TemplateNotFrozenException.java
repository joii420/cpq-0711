package com.cpq.template.exception;

import com.cpq.common.exception.BusinessException;

import java.util.UUID;

/**
 * task-0806 B20（D16~D17，2026-08-07）：模板 status ∈ (PUBLISHED, ARCHIVED) 但
 * {@code template_component_snapshot} 一行都没有 —— <b>不是故障</b>，是「尚未按 B20 后的
 * 新语义重新发布过」的正常过渡态（原 D3「一次性对齐存量」已被 D16 推翻：不迁移存量，
 * 由用户手工重新发布模板即可）。
 *
 * <p>渲染期任何触达 {@link com.cpq.template.service.PublishedTemplateReader} 的路径命中
 * 这个状态都必须让它一路冒泡到 HTTP 响应（HTTP 409 + {@code data.code=TEMPLATE_NOT_FROZEN}），
 * <b>禁止捕获后回落活表</b> {@code component} / {@code template_component}——那正是本次
 * 改造要消灭的行为（见 {@code PublishedTemplateReader} 类注释）。
 *
 * <p><b>与「有行但缺某个 sortOrder」严格区分</b>（D19）：那是快照被破坏（后门/裸 SQL 删的），
 * 仍然抛 {@link BusinessException}(500)，见 {@code PublishedTemplateReader#findTab}。
 * 两者不可混为一谈——混了会让「数据被删」被误判成「还没发布」，丢失最想要的报警。
 *
 * <p>{@link #CODE}（响应体 {@code data.code}）是前端的唯一判定依据，禁止按 message
 * 文本匹配（与 {@code FormulaCycleException} 的 {@code errorType} 同款纪律）。
 */
public class TemplateNotFrozenException extends BusinessException {

    public static final String CODE = "TEMPLATE_NOT_FROZEN";

    private final UUID templateId;
    private final String templateStatus;

    public TemplateNotFrozenException(UUID templateId, String templateStatus) {
        // task-0806 B22-c：原文案指向"重新发布"，但 publish() 只收 DRAFT，已发布模板根本没有
        // "重新发布"这个操作（createNewDraft→publish 只会产出新版本，老版本永远冻不上）。
        // B22-a 新增了真正能就地补冻的端点 POST /templates/{id}/freeze，但本期不加前端按钮
        // （一次性过渡操作，冻完不再需要）——文案改为引导联系管理员处理，而不是让普通用户
        // 自己去点一个界面上并不存在的按钮。
        super(409, "模板尚未冻结：templateId=" + templateId + ", status=" + templateStatus
                + "。该模板的渲染配置冻结快照为空（过渡期正常状态），请联系管理员冻结该模板后再试。");
        this.templateId = templateId;
        this.templateStatus = templateStatus;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public String getTemplateStatus() {
        return templateStatus;
    }
}
