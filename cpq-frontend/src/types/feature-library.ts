// v0.4 §18A 特征库类型定义

export type FeatureGroupStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
export type FieldDataType = 'STRING' | 'NUMBER' | 'DATE' | 'BOOLEAN';
export type FieldAssignMode = 'MANUAL' | 'SELECT' | 'COMPUTED';

export interface FeatureGroup {
  id: number;
  code: string;
  name: string;
  description?: string;
  category?: string;
  status: FeatureGroupStatus;
  erpRefCode?: string;
  extraAttrs?: Record<string, any>;
  createdAt: string;
  updatedAt: string;
  createdBy?: string;
  updatedBy?: string;
}

export interface FeatureField {
  id: number;
  groupId: number;
  code: string;
  name: string;
  sortOrder: number;
  dataType: FieldDataType;
  assignMode: FieldAssignMode;
  isRequired: boolean;
  defaultValue?: string;
  minValue?: string;
  maxValue?: string;
  codeLength?: number;
  decimalPlaces?: number;
  dataSourceRef?: string;
  partnoPrefix?: string;
  partnoSuffix?: string;
  extraAttrs?: Record<string, any>;
}

export interface FeatureValue {
  id: number;
  fieldId: number;
  code: string;
  label: string;
  description?: string;
  sortOrder: number;
  partnoInclude: boolean;
  isActive: boolean;
  extraAttrs?: Record<string, any>;
}
