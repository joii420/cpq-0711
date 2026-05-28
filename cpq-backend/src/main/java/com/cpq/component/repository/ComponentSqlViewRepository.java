package com.cpq.component.repository;

import com.cpq.component.entity.ComponentSqlView;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 组件 SQL 视图仓储（Panache）。
 *
 * <p>方案 §3.1 数据模型对应。
 */
@ApplicationScoped
public class ComponentSqlViewRepository implements PanacheRepositoryBase<ComponentSqlView, UUID> {

    public List<ComponentSqlView> listByComponent(UUID componentId) {
        return list("componentId = ?1 AND status = 'ACTIVE' ORDER BY sqlViewName", componentId);
    }

    public Optional<ComponentSqlView> findByComponentAndName(UUID componentId, String sqlViewName) {
        return find("componentId = ?1 AND sqlViewName = ?2 AND status = 'ACTIVE'",
                componentId, sqlViewName).firstResultOptional();
    }

    /**
     * 查找任意状态（含 INACTIVE）的同名记录 —— 用于 create 时探测软删除残留并复活。
     * PG UNIQUE (component_id, sql_view_name) 不区分 status，所以软删除后同名再创建会撞 UNIQUE。
     */
    public Optional<ComponentSqlView> findAnyByComponentAndName(UUID componentId, String sqlViewName) {
        return find("componentId = ?1 AND sqlViewName = ?2",
                componentId, sqlViewName).firstResultOptional();
    }

    /** 跨组件 GLOBAL 引用查找：按 componentCode + sqlViewName 定位。 */
    public Optional<ComponentSqlView> findGlobalByComponentCodeAndName(String componentCode, String sqlViewName) {
        return find(
                "sqlViewName = ?1 AND scope = 'GLOBAL' AND status = 'ACTIVE' " +
                "AND componentId IN (SELECT c.id FROM Component c WHERE c.code = ?2)",
                sqlViewName, componentCode
        ).firstResultOptional();
    }

    public List<ComponentSqlView> listAllGlobal() {
        return list("scope = 'GLOBAL' AND status = 'ACTIVE' ORDER BY sqlViewName");
    }
}
