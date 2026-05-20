package com.cpq.configure.dto;

import java.util.List;

/**
 * POST /material-recipes/{id}/bind-parts 与 /unbind-parts 共用请求体.
 *
 * <p>bind-parts: 把 partNos 列出的料号 UPDATE mat_part.material_recipe_id = recipeId
 * (允许从一个材质转移到另一个材质,即 transferFrom 语义)
 *
 * <p>unbind-parts: 把 partNos 列出的料号 UPDATE mat_part.material_recipe_id = NULL
 * (与 recipeId 无关,只看 partNos)
 */
public class BindPartsRequest {
    public List<String> partNos;
}
