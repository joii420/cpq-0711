package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** §6 保存结果三态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SaveGroupResult {

    /** UNCHANGED（内容未变，复用旧版本）/ UPGRADED（升版）/ CREATED（从零新建=2000）。 */
    public String result;
    public String version;       // 生效版本号；ELEMENT_BOM 多材质料号时为代表版本或 null
    public boolean isCurrent;

    public SaveGroupResult(String result, String version, boolean isCurrent) {
        this.result = result; this.version = version; this.isCurrent = isCurrent;
    }
}
