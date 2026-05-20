package com.cpq.configure.dto;

import java.util.List;
import java.util.UUID;

/**
 * 智能推断"未绑材质料号"的建议绑定单条记录.
 *
 * <p>对应 GET /material-recipes/suggest-bindings 返回列表项.
 *
 * <p>每条料号可能对应多个候选材质(EXACT_CODE > EXACT_SYMBOL > PREFIX_MATCH),前端
 * 按置信度排序展示,管理员勾选其中一个或忽略.
 */
public class BindingSuggestionDTO {
    public String partNo;
    public String partName;
    public String specification;
    /** 来源依据(显示给管理员判断): mat_bom.element_name 命中字符串列表(去重) */
    public List<String> sourceHints;
    /** 候选材质,按 confidence 排序 (EXACT_CODE > EXACT_SYMBOL > PREFIX_MATCH) */
    public List<Candidate> candidates;

    public static class Candidate {
        public UUID recipeId;
        public String recipeCode;
        public String recipeSymbol;
        public String recipeName;
        /** EXACT_CODE / EXACT_SYMBOL / PREFIX_MATCH */
        public String confidence;
        /** 命中依据(对应 mat_bom.element_name 原值) */
        public String matchedOn;

        public Candidate() {}

        public Candidate(UUID recipeId, String code, String symbol, String name,
                         String confidence, String matchedOn) {
            this.recipeId = recipeId;
            this.recipeCode = code;
            this.recipeSymbol = symbol;
            this.recipeName = name;
            this.confidence = confidence;
            this.matchedOn = matchedOn;
        }
    }
}
