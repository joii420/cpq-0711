// v0.4 §7.9 3D 模型注册

export interface PartModel {
  id: string;
  partNo: string;
  version: number;
  label?: string;
  isCurrent: boolean;
  glbUrl: string;
  thumbnailUrl?: string;
  meshCount?: number;
  vertices?: number;
  sizeKb?: number;
  metadata?: Record<string, any>;
  uploadedBy?: string;
  uploadedAt: string;
}
