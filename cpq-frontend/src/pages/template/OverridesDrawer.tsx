import React, { useEffect, useMemo, useState } from 'react';
import { Drawer, Space, Button, Input, Switch, Alert, Typography, Tag, Divider, message } from 'antd';
import { templateService } from '../../services/templateService';
import FieldConfigTable from '../component/FieldConfigTable';
import type { FieldItem } from '../component/types';

const { Text, Paragraph } = Typography;

/**
 * V204: 模板级 fields / dataDriverPath 覆盖编辑抽屉.
 *
 * <p>用于同 component 在不同 Tab 字段集/驱动不同的场景 (典型: "材质" Tab 用 v_composite_child_*
 * 视图聚合子件多 1 列"子件", "选配-材质" Tab 用 mat_part_material 单条无"子件" — 共享同
 * componentId 但快照里字段集不同). 仅 DRAFT 可改, PUBLISHED 只读.
 *
 * <p>设计: 开关式启用. 关闭时清空 override 走 component 默认; 打开时载入当前 override
 * 或复制 component 默认作为编辑起点. 数据源类 DATABASE_QUERY 配置因依赖独立 Modal 暂未支持,
 * 用户需去组件管理配 (打开 message.info 提示).
 */
interface OverridesDrawerProps {
  open: boolean;
  templateId: string;
  tcId: string;
  tabName: string;
  componentName?: string;
  componentCode?: string;
  componentDefaultFields: any[];
  /**
   * 2026-05-20: component.formulas — FieldConfigTable 的 FORMULA 字段配置列展示下拉
   * 可绑定的公式列表. Tab 级 fields_override 不独立维护 formulas (公式只在 component 级定义),
   * 这里复用 component 默认公式列表.
   */
  componentDefaultFormulas?: any[];
  componentDefaultDriverPath?: string | null;
  currentFieldsOverride?: string | null;
  currentDataDriverPathOverride?: string | null;
  isDraft: boolean;
  onClose: () => void;
  onSaved: () => void;  // 父组件刷新 listComponents
}

function parseFieldsJson(json: string | null | undefined): any[] {
  if (!json) return [];
  try {
    const v = JSON.parse(json);
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

/** 把后端 field JSON 规范化为 FieldConfigTable 接受的 FieldItem (补 key). */
function toFieldItems(rawFields: any[]): FieldItem[] {
  return rawFields.map((f, i) => ({
    key: f.key || `field-${i}-${f.name || ''}`,
    name: f.name || '',
    field_type: f.field_type || 'INPUT_TEXT',
    content: f.content,
    is_amount: !!f.is_amount,
    is_subtotal: !!f.is_subtotal,
    is_required: !!f.is_required,
    formula_name: f.formula_name,
    datasource_binding: f.datasource_binding,
    basic_data_path: f.basic_data_path,
    global_variable_code: f.global_variable_code,
    default_source: f.default_source,
    list_formula_config: f.list_formula_config,
    notes: f.notes || '',
    sort_order: f.sort_order,
    label: f.label,
  }));
}

/** 把 FieldItem 还原为后端持久化用的 plain object — 去掉 key (后端用不上), 保留所有业务字段. */
function fromFieldItems(items: FieldItem[]): any[] {
  return items.map((f, i) => {
    const o: any = {
      name: f.name,
      field_type: f.field_type,
      content: f.content ?? '',
      is_amount: !!f.is_amount,
      is_subtotal: !!f.is_subtotal,
      is_required: !!f.is_required,
      sort_order: f.sort_order ?? i,
    };
    if (f.formula_name) o.formula_name = f.formula_name;
    if (f.datasource_binding) o.datasource_binding = f.datasource_binding;
    if (f.basic_data_path) o.basic_data_path = f.basic_data_path;
    if (f.global_variable_code) o.global_variable_code = f.global_variable_code;
    if (f.default_source) o.default_source = f.default_source;
    if (f.list_formula_config) o.list_formula_config = f.list_formula_config;
    if (f.notes) o.notes = f.notes;
    if (f.label) o.label = f.label;
    return o;
  });
}

const OverridesDrawer: React.FC<OverridesDrawerProps> = ({
  open,
  templateId,
  tcId,
  tabName,
  componentName,
  componentCode,
  componentDefaultFields,
  componentDefaultFormulas,
  componentDefaultDriverPath,
  currentFieldsOverride,
  currentDataDriverPathOverride,
  isDraft,
  onClose,
  onSaved,
}) => {
  // ---- Driver Path Override ----
  const initialDriverEnabled = currentDataDriverPathOverride != null && currentDataDriverPathOverride !== '';
  const [driverEnabled, setDriverEnabled] = useState(initialDriverEnabled);
  const [driverPath, setDriverPath] = useState<string>(currentDataDriverPathOverride || '');

  // ---- Fields Override ----
  const initialFieldsEnabled = currentFieldsOverride != null && currentFieldsOverride !== '';
  const [fieldsEnabled, setFieldsEnabled] = useState(initialFieldsEnabled);
  const [fields, setFields] = useState<FieldItem[]>(() =>
    toFieldItems(parseFieldsJson(currentFieldsOverride))
  );

  const [saving, setSaving] = useState(false);

  // 抽屉每次打开重置 state (避免抽屉关闭后再打开仍是上次的编辑值)
  useEffect(() => {
    if (open) {
      const driverOn = currentDataDriverPathOverride != null && currentDataDriverPathOverride !== '';
      setDriverEnabled(driverOn);
      setDriverPath(currentDataDriverPathOverride || '');
      const fieldsOn = currentFieldsOverride != null && currentFieldsOverride !== '';
      setFieldsEnabled(fieldsOn);
      setFields(toFieldItems(parseFieldsJson(currentFieldsOverride)));
    }
  }, [open, currentFieldsOverride, currentDataDriverPathOverride]);

  const defaultFieldNames = useMemo(
    () => (componentDefaultFields || []).map((f: any) => f.name).filter(Boolean).join(', '),
    [componentDefaultFields]
  );

  const handleCopyDefaults = () => {
    setFields(toFieldItems(componentDefaultFields || []));
    message.success(`已复制组件默认 ${componentDefaultFields.length} 个字段, 你可以在下方编辑`);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const body: any = {};
      // dataDriverPathOverride: enabled=true 设值; enabled=false 显式 null 清空
      body.dataDriverPathOverride = driverEnabled ? (driverPath.trim() || null) : null;
      // fieldsOverride: enabled=true 序列化; enabled=false 显式 null 清空
      body.fieldsOverride = fieldsEnabled ? JSON.stringify(fromFieldItems(fields)) : null;
      await templateService.updateOverrides(templateId, tcId, body);
      message.success('已保存覆盖配置');
      onSaved();
      onClose();
    } catch (e: any) {
      message.error('保存失败: ' + (e?.message || String(e)));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={
        <Space size={8}>
          <span>字段 / Driver 覆盖</span>
          <Tag color="blue">{tabName}</Tag>
          {componentName && <Tag>{componentCode} / {componentName}</Tag>}
        </Space>
      }
      open={open}
      onClose={onClose}
      width={960}
      destroyOnClose
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={handleSave} disabled={!isDraft} loading={saving}>
            保存
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="模板级覆盖（V204）"
        description={
          <Paragraph style={{ marginBottom: 0, fontSize: 12 }}>
            同一组件在不同 Tab 想要不同字段集 / 不同 driver 时, 用本抽屉做 Tab 级覆盖. 关闭开关
            = 走「组件管理」里该组件的默认配置. 仅 DRAFT 模板可改, PUBLISHED 只读.
          </Paragraph>
        }
      />

      {/* ---- Driver Path Override ---- */}
      <Divider titlePlacement="left" plain style={{ marginTop: 8 }}>
        <Space>
          <Text strong>Driver Path 覆盖</Text>
          <Switch
            checked={driverEnabled}
            disabled={!isDraft}
            onChange={(v) => {
              setDriverEnabled(v);
              if (v && !driverPath && componentDefaultDriverPath) {
                setDriverPath(componentDefaultDriverPath);
              }
            }}
          />
        </Space>
      </Divider>
      {driverEnabled ? (
        <Space.Compact style={{ width: '100%' }}>
          <Input
            value={driverPath}
            onChange={(e) => setDriverPath(e.target.value)}
            placeholder={componentDefaultDriverPath || '例: v_composite_child_materials'}
            disabled={!isDraft}
          />
          {componentDefaultDriverPath && (
            <Button onClick={() => setDriverPath(componentDefaultDriverPath!)} disabled={!isDraft}>
              重置为组件默认
            </Button>
          )}
        </Space.Compact>
      ) : (
        <Text type="secondary" style={{ fontSize: 12 }}>
          未启用. 当前使用组件默认 Driver: <code>{componentDefaultDriverPath || '(无)'}</code>
        </Text>
      )}

      {/* ---- Fields Override ---- */}
      <Divider titlePlacement="left" plain style={{ marginTop: 24 }}>
        <Space>
          <Text strong>字段定义覆盖</Text>
          <Switch
            checked={fieldsEnabled}
            disabled={!isDraft}
            onChange={(v) => {
              setFieldsEnabled(v);
              if (v && fields.length === 0 && componentDefaultFields && componentDefaultFields.length > 0) {
                handleCopyDefaults();
              }
            }}
          />
        </Space>
      </Divider>
      {fieldsEnabled ? (
        <>
          <Space style={{ marginBottom: 8 }}>
            <Button size="small" onClick={handleCopyDefaults} disabled={!isDraft}>
              复制组件默认字段重新开始
            </Button>
            <Text type="secondary" style={{ fontSize: 12 }}>
              组件默认 {componentDefaultFields?.length || 0} 个字段
            </Text>
          </Space>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 8 }}
            message="DATABASE_QUERY 类数据源配置请先去「组件管理」配好, 这里不支持选 datasource"
          />
          <FieldConfigTable
            fields={fields}
            formulas={componentDefaultFormulas as any}
            onChange={(next) => setFields(next)}
            onConfigDatasource={() =>
              message.info('请到「组件管理 → 此组件」里配置 DATABASE_QUERY 数据源 (datasource_id), 再回此页面 LIST_FORMULA / BNF_PATH / GLOBAL_VARIABLE / FORMULA 等其他类型可直接编辑.')
            }
          />
        </>
      ) : (
        <Text type="secondary" style={{ fontSize: 12 }}>
          未启用. 当前使用组件默认字段集 ({componentDefaultFields?.length || 0} 个):{' '}
          <code style={{ fontSize: 11 }}>{defaultFieldNames || '(无)'}</code>
        </Text>
      )}
    </Drawer>
  );
};

export default OverridesDrawer;
