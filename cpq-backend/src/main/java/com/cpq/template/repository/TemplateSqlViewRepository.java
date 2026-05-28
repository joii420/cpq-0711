package com.cpq.template.repository;

import com.cpq.template.entity.TemplateSqlView;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 产品卡片模板 SQL 视图仓储（Panache）。
 *
 * <p>仿 {@link com.cpq.component.repository.ComponentSqlViewRepository} 结构，
 * owner 维度换成 templateId（template.id）。
 * V249 起替代 Phase 1 的 CostingTemplateSqlViewRepository。
 */
@ApplicationScoped
public class TemplateSqlViewRepository
        implements PanacheRepositoryBase<TemplateSqlView, UUID> {

    /** 列出指定模板下所有 ACTIVE 视图，按名称排序。 */
    public List<TemplateSqlView> findActiveByTemplate(UUID templateId) {
        return list("templateId = ?1 AND status = 'ACTIVE' ORDER BY sqlViewName",
                templateId);
    }

    /** 按模板 + 视图名 + ACTIVE 查找（唯一）。 */
    public Optional<TemplateSqlView> findByTemplateAndName(UUID templateId, String sqlViewName) {
        return find("templateId = ?1 AND sqlViewName = ?2 AND status = 'ACTIVE'",
                templateId, sqlViewName).firstResultOptional();
    }

    /**
     * 查找任意状态（含 INACTIVE）的同名记录。
     * 用于 create 时探测软删除残留并复活（PG UNIQUE 不区分 status）。
     */
    public Optional<TemplateSqlView> findAnyByTemplateAndName(UUID templateId, String sqlViewName) {
        return find("templateId = ?1 AND sqlViewName = ?2",
                templateId, sqlViewName).firstResultOptional();
    }

    /** 按模板 + 状态 查找（支持 status='ACTIVE'/'INACTIVE'）。 */
    public List<TemplateSqlView> findByTemplateAndStatus(UUID templateId, String status) {
        return list("templateId = ?1 AND status = ?2 ORDER BY sqlViewName",
                templateId, status);
    }
}
