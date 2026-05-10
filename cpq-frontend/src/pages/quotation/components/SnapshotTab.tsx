// ============================================================
// components/SnapshotTab.tsx
// UI-8 数据来源 Tab 内容
// 展示 submission_snapshot 中 4 个子结构：
//   - referencedVersions（引用版本）
//   - elementActualPrices（元素实际单价）
//   - formulaDefinitions（公式定义）
//   - masterDataSnapshot（主数据快照）
// 每个面板顶部可触发"对比当前数据"Drawer（VersionCompareDrawer）
// ============================================================

import React, { useState } from 'react';
import {
  Collapse, Table, Tag, Button, Space, Typography, Empty,
  Descriptions, Spin, Alert, message as antMessage,
} from 'antd';
import { SwapOutlined, ClockCircleOutlined } from '@ant-design/icons';
import type {
  SubmissionSnapshot,
  ReferencedVersionEntry,
  ElementActualPriceEntry,
  FormulaDefinitionEntry,
  MasterDataSnapshotEntry,
} from '../../../types/quotation-snapshot';

// VersionCompareDrawer 已交付（Phase 3 #14-16）
import VersionCompareDrawer from '../../master-data/VersionCompareDrawer';
import { versioningService } from '../../../services/versioningService';

const { Text, Paragraph } = Typography;

interface SnapshotTabProps {
  snapshot: SubmissionSnapshot | null;
  loading?: boolean;
}

/** 将后端返回的任意结构统一转为数组 */
function toArray<T>(val: T[] | Record<string, any> | undefined | null): T[] {
  if (!val) return [];
  if (Array.isArray(val)) return val;
  // object → array of entries
  return Object.entries(val).map(([k, v]) => ({ key: k, value: v } as unknown as T));
}

/**
 * 解析 referencedVersions，支持两种格式：
 * - 旧格式：ReferencedVersionEntry[] / Record<string, any>（数字版本号）
 * - 新格式：{ tableName: { businessKey: { version: N, recordId: "uuid" } } }
 *
 * 统一返回扁平数组，每条含 tableName / businessKey / version / recordId（可能为 null）
 */
interface ParsedRefVersion {
  tableName: string;
  businessKey: string;
  version: string | number;
  displayName?: string;
  recordId: string | null;
}

function parseReferencedVersions(raw: any): ParsedRefVersion[] {
  if (!raw) return [];

  // 已是扁平数组（旧格式）
  if (Array.isArray(raw)) {
    return raw.map((r: any) => ({
      tableName: r.tableName || '',
      businessKey: r.businessKey || '',
      version: r.version ?? '',
      displayName: r.displayName,
      recordId: r.recordId ?? null,
    }));
  }

  // 对象格式：判断是新格式还是旧格式
  // 新格式：{ tableName: { businessKey: { version, recordId } } }
  // 旧格式：Record<string, ReferencedVersionEntry>（value 含 tableName/businessKey）
  const result: ParsedRefVersion[] = [];
  for (const [outerKey, outerVal] of Object.entries(raw)) {
    if (outerVal && typeof outerVal === 'object' && !Array.isArray(outerVal)) {
      // 检测是否为新格式：outerVal 的 value 也是对象且含 version/recordId
      const innerEntries = Object.entries(outerVal as Record<string, any>);
      const isNewFormat = innerEntries.length > 0 && innerEntries.every(
        ([, v]) => v && typeof v === 'object' && ('version' in v || 'recordId' in v)
      );
      if (isNewFormat) {
        // 新格式：outerKey = tableName, innerKey = businessKey, innerVal = { version, recordId }
        for (const [innerKey, innerVal] of innerEntries) {
          result.push({
            tableName: outerKey,
            businessKey: innerKey,
            version: (innerVal as any).version ?? '',
            displayName: (innerVal as any).displayName,
            recordId: (innerVal as any).recordId ?? null,
          });
        }
      } else {
        // 旧格式：outerKey = arbitrary key, outerVal = ReferencedVersionEntry
        const entry = outerVal as any;
        result.push({
          tableName: entry.tableName || outerKey,
          businessKey: entry.businessKey || '',
          version: entry.version ?? '',
          displayName: entry.displayName,
          recordId: entry.recordId ?? null,
        });
      }
    }
  }
  return result;
}

const SnapshotTab: React.FC<SnapshotTabProps> = ({ snapshot, loading }) => {
  const [compareDrawerOpen, setCompareDrawerOpen] = useState(false);
  const [compareTarget, setCompareTarget] = useState<{
    tableName: string;
    recordIdA: string;
    recordIdB: string;
    versionA: number;
    versionB: number;
  } | null>(null);
  const [compareLoading, setCompareLoading] = useState<string | null>(null); // key = `${tableName}-${businessKey}`

  /**
   * 点击"对比当前数据"：
   * 1. 从 parsedEntry.recordId 取快照时的 recordId（recordIdA）
   * 2. 查 listHistory 找 isCurrent=true 的记录作为 recordIdB
   * 3. 打开 VersionCompareDrawer
   */
  const openCompare = async (entry: ParsedRefVersion) => {
    const loadKey = `${entry.tableName}-${entry.businessKey}`;

    if (entry.recordId === null) {
      antMessage.warning('快照数据缺失 recordId，无法对比');
      return;
    }

    setCompareLoading(loadKey);
    try {
      // 查询同表同业务键的当前记录
      const history = await versioningService.listHistory({
        tableName: entry.tableName,
        page: 0,
        size: 50,
      });
      const currentRecord = history.items.find((item) => item.isCurrent === true);

      if (!currentRecord) {
        antMessage.warning('找不到当前版本记录，无法对比');
        return;
      }

      setCompareTarget({
        tableName: entry.tableName,
        recordIdA: entry.recordId,
        recordIdB: currentRecord.recordId,
        versionA: typeof entry.version === 'number' ? entry.version : parseInt(String(entry.version).replace(/\D/g, '')) || 0,
        versionB: currentRecord.version,
      });
      setCompareDrawerOpen(true);
    } catch {
      antMessage.error('获取当前版本失败，请稍后重试');
    } finally {
      setCompareLoading(null);
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 60 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!snapshot) {
    return (
      <Empty
        description="暂无快照数据（报价单尚未提交或快照写入失败）"
        style={{ padding: 48 }}
      />
    );
  }

  // ---------- 1. 引用版本（新格式解析，容错旧格式） ----------
  const parsedRefVersions = parseReferencedVersions(snapshot.referencedVersions);
  const refVersionColumns = [
    { title: '数据表', dataIndex: 'tableName', key: 'tableName', width: 150,
      render: (v: string) => <Tag color="geekblue">{v}</Tag> },
    { title: '业务键', dataIndex: 'businessKey', key: 'businessKey', width: 180 },
    { title: '引用版本', dataIndex: 'version', key: 'version', width: 100,
      render: (v: string | number) => <Tag color="blue">{v}</Tag> },
    { title: '说明', dataIndex: 'displayName', key: 'displayName', width: 160,
      render: (v: string) => v || '-' },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: any, record: ParsedRefVersion) => {
        const loadKey = `${record.tableName}-${record.businessKey}`;
        const isLoading = compareLoading === loadKey;
        if (record.recordId === null) {
          return <Text type="secondary" style={{ fontSize: 12 }}>缺失 recordId</Text>;
        }
        return (
          <Button
            size="small"
            icon={<SwapOutlined />}
            loading={isLoading}
            onClick={() => openCompare(record)}
          >
            对比
          </Button>
        );
      },
    },
  ];

  // ---------- 2. 元素实际单价 ----------
  const elemPrices = toArray<ElementActualPriceEntry>(snapshot.elementActualPrices);
  const elemPriceColumns = [
    { title: '元素名称', dataIndex: 'elementName', key: 'elementName', width: 150 },
    {
      title: '单价',
      dataIndex: 'price',
      key: 'price',
      width: 120,
      render: (v: number, row: ElementActualPriceEntry) =>
        <Text strong>¥{Number(v).toFixed(2)} {row.currency || 'CNY'}</Text>,
    },
    { title: '货币', dataIndex: 'currency', key: 'currency', width: 80,
      render: (v: string) => v || 'CNY' },
  ];

  // ---------- 3. 公式定义 ----------
  const formulaDefs = toArray<FormulaDefinitionEntry>(snapshot.formulaDefinitions);

  // ---------- 4. 主数据快照 ----------
  const masterData = toArray<MasterDataSnapshotEntry>(snapshot.masterDataSnapshot);
  const masterDataColumns = [
    { title: '数据表', dataIndex: 'tableName', key: 'tableName', width: 150,
      render: (v: string) => <Tag color="cyan">{v}</Tag> },
    { title: '字段名', dataIndex: 'fieldName', key: 'fieldName', width: 180 },
    { title: '说明', dataIndex: 'displayName', key: 'displayName', width: 200,
      render: (v: string) => v || '-' },
    { title: '快照值', dataIndex: 'value', key: 'value',
      render: (v: any) => (
        <Text code style={{ fontSize: 12 }}>{String(v)}</Text>
      ) },
  ];

  // 面板级对比按钮（元素单价 / 公式 / 主数据用，不含 recordId 逻辑）
  const panelHeaderExtra = (tableName: string) => (
    <Button
      size="small"
      icon={<SwapOutlined />}
      onClick={(e) => {
        e.stopPropagation();
        // 对于没有 recordId 的面板，尝试从 parsedRefVersions 中找第一条匹配的
        const entry = parsedRefVersions.find((r) => r.tableName === tableName);
        if (entry) {
          openCompare(entry);
        } else {
          antMessage.warning('该面板暂无可对比的引用版本记录');
        }
      }}
    >
      对比当前数据
    </Button>
  );

  return (
    <div style={{ padding: '8px 0' }}>
      {/* 快照时间 */}
      <Alert
        message={
          <Space>
            <ClockCircleOutlined />
            <Text>
              快照创建时间：
              <Text strong>
                {new Date(snapshot.snapshotAt).toLocaleString('zh-CN')}
              </Text>
            </Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              （以下数据为报价单提交时的冻结状态，仅供追溯参考）
            </Text>
          </Space>
        }
        type="info"
        showIcon={false}
        style={{ marginBottom: 16 }}
      />

      <Collapse
        defaultActiveKey={['referencedVersions']}
        style={{ background: '#fff' }}
      >
        {/* Panel 1：引用版本（每条记录独立对比按钮） */}
        <Collapse.Panel
          key="referencedVersions"
          header={
            <Space>
              <strong>引用版本</strong>
              <Tag>{parsedRefVersions.length} 条</Tag>
            </Space>
          }
        >
          {parsedRefVersions.length === 0 ? (
            <Empty description="无引用版本数据" />
          ) : (
            <Table
              dataSource={parsedRefVersions}
              columns={refVersionColumns}
              rowKey={(r) => `${r.tableName}-${r.businessKey}`}
              pagination={false}
              size="small"
            />
          )}
        </Collapse.Panel>

        {/* Panel 2：元素实际单价 */}
        <Collapse.Panel
          key="elementActualPrices"
          header={
            <Space>
              <strong>元素实际单价</strong>
              <Tag>{elemPrices.length} 条</Tag>
            </Space>
          }
          extra={panelHeaderExtra('element_price')}
        >
          {elemPrices.length === 0 ? (
            <Empty description="无元素单价数据" />
          ) : (
            <Table
              dataSource={elemPrices}
              columns={elemPriceColumns}
              rowKey="elementName"
              pagination={false}
              size="small"
            />
          )}
        </Collapse.Panel>

        {/* Panel 3：公式定义 */}
        <Collapse.Panel
          key="formulaDefinitions"
          header={
            <Space>
              <strong>公式定义</strong>
              <Tag>{formulaDefs.length} 条</Tag>
            </Space>
          }
          extra={panelHeaderExtra('formula')}
        >
          {formulaDefs.length === 0 ? (
            <Empty description="无公式定义数据" />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              {formulaDefs.map((f) => (
                <div
                  key={f.name}
                  style={{
                    border: '1px solid #e8e8e8',
                    borderRadius: 6,
                    padding: '12px 16px',
                    background: '#fafafa',
                  }}
                >
                  <Descriptions column={2} size="small">
                    <Descriptions.Item label="公式名称">
                      <Text strong>{f.name}</Text>
                    </Descriptions.Item>
                    {f.category && (
                      <Descriptions.Item label="分类">
                        <Tag color="purple">{f.category}</Tag>
                      </Descriptions.Item>
                    )}
                    {f.description && (
                      <Descriptions.Item label="说明" span={2}>
                        {f.description}
                      </Descriptions.Item>
                    )}
                  </Descriptions>
                  <Paragraph
                    style={{
                      fontFamily: 'monospace',
                      fontSize: 13,
                      background: '#f0f0f0',
                      padding: '8px 12px',
                      borderRadius: 4,
                      marginBottom: 0,
                      marginTop: 8,
                    }}
                  >
                    {f.expression}
                  </Paragraph>
                </div>
              ))}
            </Space>
          )}
        </Collapse.Panel>

        {/* Panel 4：主数据快照 */}
        <Collapse.Panel
          key="masterDataSnapshot"
          header={
            <Space>
              <strong>主数据快照</strong>
              <Tag>{masterData.length} 条</Tag>
            </Space>
          }
          extra={panelHeaderExtra('mat_fee')}
        >
          {masterData.length === 0 ? (
            <Empty description="无主数据快照" />
          ) : (
            <Table
              dataSource={masterData}
              columns={masterDataColumns}
              rowKey={(r) => `${r.tableName}-${r.fieldName}`}
              pagination={false}
              size="small"
            />
          )}
        </Collapse.Panel>
      </Collapse>

      {/* 版本对比 Drawer */}
      {compareTarget && (
        <VersionCompareDrawer
          open={compareDrawerOpen}
          tableName={compareTarget.tableName}
          recordIdA={compareTarget.recordIdA}
          recordIdB={compareTarget.recordIdB}
          versionA={compareTarget.versionA}
          versionB={compareTarget.versionB}
          onClose={() => { setCompareDrawerOpen(false); setCompareTarget(null); }}
        />
      )}
    </div>
  );
};

export default SnapshotTab;
