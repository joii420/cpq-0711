// 3D 模型配置类型（task-0712 B5，api.md §4；字段对齐后端 com.cpq.modelconfig.dto.ModelConfigDTO）
// 新表 model_config / model_config_file（D4，弃旧 mat_part_model，v0.4 独立选配器不复用）。

export type ModelSubjectType = 'SALES_PART' | 'MATERIAL';

/**
 * 分页包络（真实后端类 com.cpq.common.dto.PageResult）：content/totalElements/page/size/totalPages，
 * 不是 items/total。与 existingProduct.ts 的同名类型结构一致但独立声明（两个 domain 各自 self-contained，
 * 与工程既有 PageResult(v6MasterDataService.ts) / PageResultLike(materialRecipeService.ts) 并存惯例一致）。
 */
export interface PageResult<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ModelConfigDTO {
  id: string;
  subjectType: ModelSubjectType;
  /** 销售料号 material_no / 材质配方码 material_recipe.code。 */
  subjectKey: string;
  /** 展示用中文名（后端 JOIN 材质库/客户产品批量得出，防 N+1）。 */
  subjectLabel?: string | null;
  version: number;
  isCurrent: boolean;
  label?: string | null;
  /** 完整可用路径，形如 /api/cpq/model-configs/files/{fileId}，前端直接用，无需拼接。 */
  glbUrl?: string | null;
  thumbnailUrl?: string | null;
  sizeKb?: number | null;
  meshCount?: number | null;
  vertices?: number | null;
  uploadedAt?: string | null;
  uploadedBy?: string | null;
}

/** `GET /model-configs` 列表查询参数。 */
export interface ModelConfigListParams {
  subjectType?: ModelSubjectType;
  keyword?: string;
  page?: number;
  size?: number;
}

/**
 * `POST /model-configs`（multipart）上传新版本请求体（前端组 FormData 用，不直接 JSON.stringify）。
 * setCurrent 对应"上传并设为当前版本" vs "仅上传为历史版本"两个提交按钮。
 */
export interface ModelConfigUploadPayload {
  subjectType: ModelSubjectType;
  subjectKey: string;
  label?: string;
  glbFile: File;
  thumbnailFile?: File | null;
  setCurrent: boolean;
}

/** `GET /model-configs/current` 查询参数（选配/已有产品添加抽屉实时带出 3D，D15）。 */
export interface ModelConfigCurrentParams {
  subjectType: ModelSubjectType;
  subjectKey: string;
}
