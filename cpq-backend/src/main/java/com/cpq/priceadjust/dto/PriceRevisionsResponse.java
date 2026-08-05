package com.cpq.priceadjust.dto;

import java.util.List;

/**
 * api.md §4.1 · 屏 7 两张表同源，一次返回。
 */
public class PriceRevisionsResponse {
    /** 整单版本轨迹（裁决 15 前半）。 */
    public List<PriceRevisionDTO> revisions;
    /** 料号级价格版本表（裁决 15 后半，§11.1.1 混合价可查证据）。 */
    public List<MaterialVersionRowDTO> materialVersions;
}
