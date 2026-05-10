export type DataType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON';
export type ConfigCategory = 'validation' | 'import' | 'retention' | 'element_price' | 'business';
export type ModifiableBy = 'SYSTEM_ADMIN' | 'SALES_MANAGER' | 'ANYONE';

export interface SystemConfigDTO {
  configKey: string;
  configValue: string;
  defaultValue: string | null;
  dataType: DataType;
  category: ConfigCategory;
  description: string | null;
  modifiableBy: ModifiableBy;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface CreateSystemConfigRequest {
  configKey: string;
  configValue: string;
  defaultValue?: string;
  dataType: DataType;
  category: ConfigCategory;
  description?: string;
  modifiableBy: ModifiableBy;
}

export interface UpdateSystemConfigRequest {
  configValue: string;
  description?: string;
  modifiableBy?: ModifiableBy;
}
