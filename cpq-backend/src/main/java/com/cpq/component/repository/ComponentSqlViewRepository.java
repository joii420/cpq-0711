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

    /**
     * 取数配置器托管的全部 ACTIVE 视图（{@code builder_config IS NOT NULL}）——
     * repair-260908 B-6 的存量重编译清单。
     *
     * <p>{@code builderConfig IS NULL} 的是**存量手写视图**（AC-32 要求行为逐字不变），
     * 重编译无从谈起，天然排除在外。ORDER BY 固定，保证预览与执行两次跑的顺序一致
     * （否则报「28 个里 22 个会变」之后再执行，用户无法逐条对上是哪 22 个）。
     */
    public List<ComponentSqlView> listBuilderManaged() {
        return list("builderConfig IS NOT NULL AND status = 'ACTIVE' ORDER BY componentId, sqlViewName");
    }

    /**
     * repair-260916 B-2：{@link #listBuilderManaged()} 的「只看指定组件」版本 ——
     * 判定口径（{@code builder_config IS NOT NULL AND status = 'ACTIVE'}）与排序逐字相同，
     * 只多一个 {@code componentId IN (…)}。一条 SQL，与组件数无关。
     *
     * <p>🚫 两个方法的口径必须一起改：否则「按组件重编译」与「全量重编译」对同一组件看到的视图集合会不同，
     * AC-7② 的「产物逐字等于全量重编译」就失去前提。
     */
    public List<ComponentSqlView> listBuilderManagedByComponents(java.util.Collection<UUID> componentIds) {
        if (componentIds == null || componentIds.isEmpty()) return List.of();
        return list("builderConfig IS NOT NULL AND status = 'ACTIVE' AND componentId IN ?1 "
                + "ORDER BY componentId, sqlViewName", componentIds);
    }
}
