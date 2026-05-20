package com.cpq.datasource.resolver;

import java.util.Map;

/**
 * I2: 数据源解析器统一接口.
 *
 * <p>配置中心 V190 重构后, DATA_SOURCE 字段不再硬绑数据库查询, 支持 4 种来源类型:
 * <ul>
 *   <li>DATABASE_QUERY  — 复用现有 datasource_id 查询 (DataSourceService.query)</li>
 *   <li>GLOBAL_VARIABLE — 查 global_variable_value 单表 (GlobalVariableService.resolveValue)</li>
 *   <li>BNF_PATH        — BNF 路径解析 (DataLoader.loadByPath)</li>
 *   <li>HTTP_API        — 外部 API 调用 (严格安全约束: 白名单 / 超时 / 缓存)</li>
 * </ul>
 *
 * <p>添加新类型: 实现本接口 + 标 @ApplicationScoped + 在 type() 返回 type 字符串.
 * CDI Instance&lt;DataSourceResolver&gt; 自动发现并注册到 Registry.
 */
public interface DataSourceResolver {

    /** 该 resolver 处理的数据源类型字符串 (与 datasource_binding.type 对应) */
    String type();

    /**
     * 解析数据源, 返回值.
     *
     * @param config    datasource_binding 配置 Map (具体字段按 type 各异)
     * @param driverRow 当前 driver 行字段 (用于动态 key 取值; 可空)
     * @return 标量值 (Number / String / Boolean / null); 失败返 null 不抛
     */
    Object resolve(Map<String, Object> config, Map<String, Object> driverRow);
}
