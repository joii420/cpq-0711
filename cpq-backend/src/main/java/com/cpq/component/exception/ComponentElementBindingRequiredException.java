package com.cpq.component.exception;

import com.cpq.common.exception.BusinessException;

import java.util.List;

/**
 * task-0729 B7（api.md §5.1）· 400 COMPONENT_ELEMENT_BINDING_REQUIRED —— 该组件的
 * sqlTemplate 检测到取价函数（f_customer_element_price/f_material_element_price），
 * 但 elementCodeField/elementPriceField 未配齐。🔒 拦下不让保存，不是警告、不是静默保存
 * （验收 #32②）。
 */
public class ComponentElementBindingRequiredException extends BusinessException {

    private final List<String> missingFields;

    public ComponentElementBindingRequiredException(List<String> missingFields) {
        super(400, "该组件视图接入了取价函数，必须指定元素列与元素单价列");
        this.missingFields = missingFields;
    }

    public List<String> getMissingFields() { return missingFields; }
}
