// v0.4 3D 选配类型定义

export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type InstanceStatus = 'DRAFT' | 'SUBMITTED' | 'LINKED' | 'EXPIRED';
export type OptionType = 'EXCLUSIVE' | 'MULTI_SELECT' | 'NUMERIC' | 'TEXT' | 'COLOR';

export interface ConfiguratorTemplate {
  id: string;
  code: string;
  name: string;
  category?: string;
  basePartNo?: string;
  baseModelId?: string;
  baseModelVersion?: number;
  baseModelSnapshotAt?: string;
  description?: string;
  showPrice: boolean;
  metadata?: Record<string, any>;
  status: TemplateStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ConfiguratorOption {
  id: string;
  templateId: string;
  code: string;
  label: string;
  optionType: OptionType;
  dataType?: string;
  assignMode?: string;
  isRequired: boolean;
  defaultValue?: string;
  minValue?: string;
  maxValue?: string;
  partnoPrefix?: string;
  partnoSuffix?: string;
  sortOrder: number;
  description?: string;
  metadata?: Record<string, any>;
  sourceFeatureFieldId?: number;
  sourceFeatureSnapshotAt?: string;
}

export interface ConfiguratorOptionValue {
  id: string;
  optionId: string;
  code: string;
  label: string;
  description?: string;
  priceDelta: number;
  sortOrder: number;
  partnoInclude: boolean;
  isActive: boolean;
  featureType?: string;
  attributes?: Record<string, any>;
  tags?: string[];
  subModelPartNo?: string;
  attachMode?: string;
  replaceBaseMesh?: boolean;
  sourceFeatureValueId?: number;
  sourceFeatureSnapshotAt?: string;
  localOnly: boolean;
}

export interface ConfiguratorInstance {
  id: string;
  instanceCode: string;
  templateId: string;
  templateVersion?: number;
  name?: string;
  customerId?: string;
  customerLeadId?: string;
  userId?: string;
  shareToken?: string;
  selectedValues: Record<string, any>;
  configFingerprint?: string;
  computedTotalPrice?: number;
  basePrice?: number;
  status: InstanceStatus;
  linkedQuotationId?: string;
  linkedAt?: string;
  linkedBy?: string;
  generatedPartNo?: string;
  generatedQuotationId?: string;
  generatedLineItemId?: string;
  expiresAt?: string;
  createdAt: string;
  updatedAt: string;
}
