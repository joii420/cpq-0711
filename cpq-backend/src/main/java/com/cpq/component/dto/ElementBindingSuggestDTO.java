package com.cpq.component.dto;

import java.util.List;

/** task-0729 B7（api.md §5.2）· GET /components/{id}/element-binding-suggest 响应体。 */
public class ElementBindingSuggestDTO {

    public Suggested suggested = new Suggested();
    /** 动态捕获的取价函数别名，未推导出时为 null。 */
    public String alias;
    /** HIGH | LOW（LOW 时前端提示需人工确认）。未推导出时仍为 LOW，不报错。 */
    public String confidence = "LOW";
    public List<String> warnings = List.of();

    public static class Suggested {
        public String elementCodeField;
        public String elementPriceField;
        public String elementCurrencyField;
    }
}
