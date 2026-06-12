import React, { useEffect, useState } from 'react';
import {
  Drawer,
  Radio,
  Form,
  Input,
  Select,
  Button,
  Space,
  Alert,
  Empty,
  Tag,
  Typography,
  message,
} from 'antd';
import { ExperimentOutlined } from '@ant-design/icons';
import { globalVariableService, type GlobalVariableDefinition } from '../../services/globalVariableService';
import api from '../../services/api';
import { dataSourceResolverService, RESOLVER_TYPE_LABEL } from '../../services/dataSourceResolverService';
import type { DefaultSource } from './types';
import { componentSqlViewService, type ComponentSqlView } from '../../services/componentSqlViewService';
import { ViewColumnPickerBody } from './ViewColumnPickerBody';

const { Text } = Typography;

interface Props {
  open: boolean;
  /** 当前字段的 default_source (空 = 新增) */
  value?: DefaultSource;
  /** 字段名 — 显示在标题里 */
  fieldName?: string;
  /** 组件 ID — 用于拉取 BASIC_DATA 分支可用的 SQL 视图列表 */
  componentId?: string;
  onClose: () => void;
  onConfirm: (next: DefaultSource | undefined) => void;
}

/**
 * G3: 统一默认值来源编辑抽屉 — 替代 V184 散字段编辑.
 * 支持三种 default_source.type:
 *   GLOBAL_VARIABLE — 选 code, 自动按 def.keyColumns 同名映射 driver row 字段
 *   BNF_PATH        — 手填 BNF 路径
 *   HTTP_API        — 占位 (Phase D follow-up)
 */
const DefaultSourceEditor: React.FC<Props> = ({ open, value, fieldName, componentId, onClose, onConfirm }) => {
  const [type, setType] = useState<DefaultSource['type']>('GLOBAL_VARIABLE');
  const [gvCode, setGvCode] = useState<string | undefined>(undefined);
  const [keyFieldRefs, setKeyFieldRefs] = useState<string>('');
  const [bnfPath, setBnfPath] = useState<string>('');
  // J2: HTTP_API 配置字段
  const [apiUrlTemplate, setApiUrlTemplate] = useState<string>('');
  const [apiResponsePath, setApiResponsePath] = useState<string>('');
  const [apiAuthTokenEnv, setApiAuthTokenEnv] = useState<string>('');

  const [defs, setDefs] = useState<GlobalVariableDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  // BASIC_DATA 内联视图点选
  const [views, setViews] = useState<ComponentSqlView[]>([]);
  const [pickedViewName, setPickedViewName] = useState<string | undefined>();
  // K2: 动态拉数据源类型, 不再硬编码 Radio 选项
  const [availableTypes, setAvailableTypes] = useState<string[]>(
    ['GLOBAL_VARIABLE', 'BNF_PATH', 'HTTP_API', 'BASIC_DATA']  // SSR/初始默认, 拉到后端列表后会更新
  );
  // J3: 测试解析
  const [testDriverRowJson, setTestDriverRowJson] = useState<string>('{}');
  const [testResult, setTestResult] = useState<string | undefined>(undefined);
  const [testError, setTestError] = useState<string | undefined>(undefined);
  const [testing, setTesting] = useState(false);

  useEffect(() => {
    if (!open) return;
    // 初始化表单
    if (value) {
      setType(value.type);
      setGvCode(value.code);
      setKeyFieldRefs(value.key_field_refs ? JSON.stringify(value.key_field_refs) : '');
      setBnfPath(value.path || '');
      const ac = value.api_config || {};
      setApiUrlTemplate(String(ac.url_template ?? ''));
      setApiResponsePath(String(ac.response_path ?? ''));
      setApiAuthTokenEnv(String(ac.auth_token_env ?? ''));
    } else {
      setType('GLOBAL_VARIABLE');
      setGvCode(undefined);
      setKeyFieldRefs('');
      setBnfPath('');
      setApiUrlTemplate('');
      setApiResponsePath('');
      setApiAuthTokenEnv('');
    }
    // 拉变量列表
    setLoading(true);
    globalVariableService.list()
      .then((r: any) => setDefs(r?.data?.data || r?.data || []))
      .catch(() => setDefs([]))
      .finally(() => setLoading(false));
    // K2: 拉 resolver type 列表 (DATABASE_QUERY 不作为 default_source 默认值场景, 过滤掉)
    dataSourceResolverService.listTypes()
      .then((types) => setAvailableTypes(
        [...new Set([...types.filter((t) => t !== 'DATABASE_QUERY'), 'BASIC_DATA'])]
      ))
      .catch(() => {/* 用默认 */});
  }, [open, value]);

  useEffect(() => {
    if (!open || !componentId) return;
    componentSqlViewService.list(componentId)
      .then((r) => setViews(r.data || []))
      .catch(() => setViews([]));
  }, [open, componentId]);

  const submit = () => {
    if (type === 'GLOBAL_VARIABLE') {
      if (!gvCode) { return; }
      let refs: Record<string, string> | undefined = undefined;
      if (keyFieldRefs.trim()) {
        try {
          refs = JSON.parse(keyFieldRefs);
        } catch {
          refs = undefined;
        }
      }
      onConfirm({
        type: 'GLOBAL_VARIABLE',
        code: gvCode,
        key_field_refs: refs ?? {},  // 留空 = 同名映射
      });
      return;
    }
    if (type === 'BNF_PATH') {
      if (!bnfPath.trim()) return;
      onConfirm({ type: 'BNF_PATH', path: bnfPath.trim() });
      return;
    }
    if (type === 'BASIC_DATA') {
      if (!bnfPath.trim()) return;
      onConfirm({ type: 'BASIC_DATA', path: bnfPath.trim() });
      return;
    }
    if (type === 'HTTP_API') {
      if (!apiUrlTemplate.trim()) return;
      const apiConfig: Record<string, any> = { url_template: apiUrlTemplate.trim() };
      if (apiResponsePath.trim()) apiConfig.response_path = apiResponsePath.trim();
      if (apiAuthTokenEnv.trim()) apiConfig.auth_token_env = apiAuthTokenEnv.trim();
      onConfirm({ type: 'HTTP_API', api_config: apiConfig });
      return;
    }
  };

  const clear = () => onConfirm(undefined);

  /** J3: 不保存, 临时拼当前配置调 /data-sources/resolve 看结果. driverRow 由用户手填. */
  const testResolve = async () => {
    setTesting(true);
    setTestResult(undefined);
    setTestError(undefined);
    let driverRow: Record<string, any> = {};
    try {
      const t = testDriverRowJson.trim();
      if (t) driverRow = JSON.parse(t);
    } catch (e: any) {
      setTestError('driverRow JSON 解析失败: ' + (e?.message || ''));
      setTesting(false);
      return;
    }
    let config: Record<string, any> = {};
    if (type === 'GLOBAL_VARIABLE') {
      if (!gvCode) { setTestError('请先选变量'); setTesting(false); return; }
      config.code = gvCode;
      if (keyFieldRefs.trim()) {
        try { config.key_field_refs = JSON.parse(keyFieldRefs); } catch {/* ignore */}
      }
    } else if (type === 'BNF_PATH') {
      if (!bnfPath.trim()) { setTestError('请先填路径'); setTesting(false); return; }
      config.path = bnfPath.trim();
    } else if (type === 'HTTP_API') {
      if (!apiUrlTemplate.trim()) { setTestError('请先填 URL 模板'); setTesting(false); return; }
      const apiConfig: Record<string, any> = { url_template: apiUrlTemplate.trim() };
      if (apiResponsePath.trim()) apiConfig.response_path = apiResponsePath.trim();
      if (apiAuthTokenEnv.trim()) apiConfig.auth_token_env = apiAuthTokenEnv.trim();
      config.api_config = apiConfig;
    }
    try {
      const resp: any = await api.post('/data-sources/resolve', { type, config, driverRow });
      const v = resp?.data?.data;
      if (v === null || v === undefined) {
        setTestError('解析返 null — 检查配置 / driverRow / 后端日志');
      } else {
        setTestResult(typeof v === 'object' ? JSON.stringify(v) : String(v));
        message.success('解析成功');
      }
    } catch (e: any) {
      setTestError(e?.response?.data?.message || e?.message || '调用失败');
    } finally {
      setTesting(false);
    }
  };

  const selectedDef = defs.find((d) => d.code === gvCode);

  return (
    <Drawer
      title={`配置默认值来源${fieldName ? ` — ${fieldName}` : ''}`}
      open={open}
      onClose={onClose}
      width={520}
      footer={
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Button danger onClick={clear} disabled={!value}>清除默认值</Button>
          <Space>
            <Button icon={<ExperimentOutlined />} onClick={testResolve} loading={testing} disabled={type === 'BASIC_DATA'}>
              测试解析
            </Button>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" onClick={submit}>保存</Button>
          </Space>
        </div>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="默认值兜底链"
        description="用户输入 → default_source 解析 → field.content 静态兜底 → 0. 配置 default_source 后, 行值空时报价单 placeholder 显示「默认 X · CODE」"
      />

      <Form.Item label="数据来源类型" style={{ marginBottom: 16 }}>
        <Radio.Group value={type} onChange={(e) => { setType(e.target.value); setBnfPath(''); }}>
          {availableTypes.map((t) => (
            <Radio key={t} value={t}>{RESOLVER_TYPE_LABEL[t] || t}</Radio>
          ))}
        </Radio.Group>
      </Form.Item>

      {type === 'GLOBAL_VARIABLE' && (
        <>
          <Form.Item label="选择变量" required>
            {defs.length === 0 && !loading ? (
              <Empty description="暂无可用全局变量, 先到「全局变量」页新建" />
            ) : (
              <Select
                value={gvCode}
                onChange={setGvCode}
                placeholder="选择全局变量"
                loading={loading}
                style={{ width: '100%' }}
                showSearch
                optionFilterProp="label"
                options={defs.map((d) => ({
                  label: `${d.name} (${d.code})${d.unit ? ` · ${d.unit}` : ''}`,
                  value: d.code,
                }))}
              />
            )}
          </Form.Item>

          {selectedDef && (
            <>
              <Alert
                type={selectedDef.varType === 'LOOKUP_TABLE' ? 'warning' : 'success'}
                style={{ marginBottom: 12 }}
                message={
                  selectedDef.varType === 'LOOKUP_TABLE'
                    ? `查表型 — key 列: ${selectedDef.keyColumns.join(', ')}`
                    : '标量型 — 单一值, 无需 key'
                }
                description={
                  selectedDef.varType === 'LOOKUP_TABLE' && (
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      留空 key_field_refs 走「同名映射」: key 列{' '}
                      <code>{selectedDef.keyColumns[0]}</code> → driver row 字段{' '}
                      <code>{selectedDef.keyColumns[0]}</code>. 仅 driver 行字段名与 key 列不同名时才显式填映射.
                    </Text>
                  )
                }
              />

              {selectedDef.varType === 'LOOKUP_TABLE' && (
                <Form.Item
                  label={
                    <Space>
                      <span>key_field_refs (高级, 通常留空)</span>
                      <Tag color="blue">JSON</Tag>
                    </Space>
                  }
                  extra='留空 = 同名映射. 显式形态: {"process_code": "driver_field_name"}'
                >
                  <Input.TextArea
                    rows={3}
                    value={keyFieldRefs}
                    onChange={(e) => setKeyFieldRefs(e.target.value)}
                    placeholder='{} 或 {"process_code": "另一字段名"}'
                  />
                </Form.Item>
              )}
            </>
          )}
        </>
      )}

      {type === 'BNF_PATH' && (
        <Form.Item label="BNF 路径" required extra="同 BASIC_DATA 字段路径语法, 如 mat_part.unit_weight">
          <Input
            value={bnfPath}
            onChange={(e) => setBnfPath(e.target.value)}
            placeholder="mat_part.unit_weight"
            style={{ fontFamily: 'Consolas, Monaco, monospace' }}
          />
        </Form.Item>
      )}

      {type === 'BASIC_DATA' && (
        <Form.Item label="基础数据来源（选视图 → 点字段）" required>
          <ViewColumnPickerBody
            views={views}
            selectedViewName={pickedViewName}
            onSelectView={setPickedViewName}
            effectiveComponentId={componentId}
            currentPath={bnfPath}
            onPick={(path) => setBnfPath(path)}
          />
          {bnfPath && <div style={{ marginTop: 6, fontSize: 12 }}>已选：<code>{bnfPath}</code></div>}
        </Form.Item>
      )}

      {/* J3: 测试解析区 — driverRow 由用户手填, 不持久化; BASIC_DATA 在报价单展开时解析, 此处不支持测试 */}
      {type !== 'BASIC_DATA' && (
        <Form.Item
          label={<Space><span>测试 driverRow (JSON)</span><Tag color="blue">仅本次测试用</Tag></Space>}
          style={{ marginTop: 16, marginBottom: 8 }}
          extra='点底部「测试解析」按钮验证当前配置. 例: {"process_code":"Z350"}'
        >
          <Input.TextArea
            rows={2}
            value={testDriverRowJson}
            onChange={(e) => setTestDriverRowJson(e.target.value)}
            placeholder='{"process_code":"Z350"}'
            style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }}
          />
        </Form.Item>
      )}
      {type !== 'BASIC_DATA' && testResult !== undefined && (
        <Alert
          type="success"
          showIcon
          style={{ marginBottom: 12 }}
          message="解析结果"
          description={<code>{testResult}</code>}
        />
      )}
      {type !== 'BASIC_DATA' && testError !== undefined && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message="解析失败"
          description={testError}
        />
      )}

      {type === 'HTTP_API' && (
        <>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="HTTP_API 默认完全关闭"
            description={
              <Text type="secondary" style={{ fontSize: 12 }}>
                必须在 application.properties 显式 opt-in: <code>cpq.http-api.allowed-hosts=...</code>{' '}
                未配置时 resolver 返 null. 详见 <code>docs/HTTP_API_安全配置.md</code>.
              </Text>
            }
          />
          <Form.Item label="URL 模板" required extra="{字段名} 占位由 driver row 替换并 URL-encode">
            <Input
              value={apiUrlTemplate}
              onChange={(e) => setApiUrlTemplate(e.target.value)}
              placeholder="https://api.example.com/price/{partNo}"
              style={{ fontFamily: 'Consolas, Monaco, monospace' }}
            />
          </Form.Item>
          <Form.Item label="response_path (JSON dot-path)" extra="留空 = 返完整 body 字符串. 例: data.unit_price">
            <Input
              value={apiResponsePath}
              onChange={(e) => setApiResponsePath(e.target.value)}
              placeholder="data.unit_price"
              style={{ fontFamily: 'Consolas, Monaco, monospace' }}
            />
          </Form.Item>
          <Form.Item
            label="auth_token_env (环境变量名)"
            extra="留空 = 不带鉴权; 填写后 resolver 从 process env 取 Bearer token, 严禁明文 token 入 DB"
          >
            <Input
              value={apiAuthTokenEnv}
              onChange={(e) => setApiAuthTokenEnv(e.target.value)}
              placeholder="EXAMPLE_API_TOKEN"
              style={{ fontFamily: 'Consolas, Monaco, monospace' }}
            />
          </Form.Item>
        </>
      )}
    </Drawer>
  );
};

export default DefaultSourceEditor;
