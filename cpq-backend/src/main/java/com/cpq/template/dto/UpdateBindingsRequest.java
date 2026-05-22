package com.cpq.template.dto;

import java.util.List;

/**
 * V212: PUT /api/cpq/templates/{tid}/global-variable-bindings 请求体.
 *
 * <p>全量替换语义 (PUT): 后端先 DELETE by template_id, 再 INSERT 全新列表.
 * 每项只需 globalVariableCode + displayOrder, id 由后端生成.
 */
public class UpdateBindingsRequest {

    public List<BindingItem> bindings;

    public static class BindingItem {
        /** V104 global_variable_definition.code — 不是 gvId */
        public String globalVariableCode;
        public int displayOrder;
    }
}
