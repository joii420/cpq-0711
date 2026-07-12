package com.cpq.basicdata.v6.maintenance.dto;

import java.util.List;
import java.util.Map;

/**
 * §6 保存整组请求。
 * <p>rows 只需带 AXIS 之外的列（AXIS 由 path 的 materialNo + registry 的 price_type/固定常量注入，前端不可改）；
 * NAME 列不必回传（后端忽略、不进指纹比对）。
 */
public final class SaveGroupRequest {

    /** 乐观锁：前端加载时的当前版本号；空 tab 从零新建传 null。ELEMENT_BOM 合并展示时可传 null（见 Service 说明）。 */
    public String expectedCurrentVersion;

    public List<Map<String, Object>> rows;
}
