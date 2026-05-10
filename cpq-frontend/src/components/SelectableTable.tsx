/**
 * SelectableTable —— 列表选择 + 工具栏动作 统一规范的通用容器
 *
 * 设计原则（详见 docs/RECORD.md 同期讨论）：
 *   - 行内只承载数据 + 一个"主入口"（详情/配置链接），所有变更类动作上提到顶部工具栏
 *   - 选择驱动动作：先选行 → 工具栏按钮启用/禁用按 selectedRows 谓词决定
 *   - 跨页保留选中（preserveSelectedRowKeys）
 *   - 危险动作走 Modal 列出所选项并二次确认（不再用零散 Popconfirm）
 *   - 批量操作的"部分失败"语义：用 Promise.allSettled 聚合，message.error 展示失败明细
 *
 * 用法：
 *   <SelectableTable
 *     rowKey="id"
 *     columns={dataColumns}              // 数据列 +（可选）主入口链接列
 *     dataSource={list}
 *     actions={[
 *       { key: 'publish', label: '发布', enabledWhen: rows =>
 *           rows.length > 0 && rows.every(r => r.status === 'DRAFT')
 *           ? true : '仅草稿可发布',
 *         onClick: rows => batchPublish(rows) },
 *       { key: 'delete', label: '删除', danger: true,
 *         enabledWhen: ..., needsConfirm: true,
 *         confirmTitle: '确认删除选中的 {N} 项？',
 *         onClick: ... },
 *     ]}
 *     toolbar={<Space>...</Space>}       // 顶栏永久按钮（如"新建"）+ 筛选
 *     rowLabel={r => r.name}             // 确认弹窗里展示的行标签
 *   />
 */
import React, { useMemo, useState, useCallback } from 'react';
import { Table, Button, Tooltip, Modal, message, Space } from 'antd';
import type { TableProps } from 'antd';
import type { ColumnsType } from 'antd/es/table';

export interface ToolbarAction<T> {
  key: string;
  label: string;
  icon?: React.ReactNode;
  /** 危险动作（删除）会以红色按钮 + 红色确认按钮渲染 */
  danger?: boolean;
  /**
   * 启用判定：
   *   true            → 启用
   *   false           → 禁用，hover tooltip 显示通用提示「请先选择行」
   *   非空字符串       → 禁用，tooltip 显示该字符串作为禁用原因
   */
  enabledWhen: (selectedRows: T[]) => boolean | string;
  /** 危险动作 / 不可逆动作建议 true，会弹 Modal 列出所选项二次确认 */
  needsConfirm?: boolean;
  /** 确认 Modal 标题，可以包含占位符 {N} 自动替换为所选数 */
  confirmTitle?: string;
  /** 确认 Modal 主描述（红色后果说明放这里） */
  confirmDescription?: string;
  /**
   * 真正执行动作。允许 async；返回 Promise 期间按钮 loading。
   * 实现里 caller 自行决定是否批量并发 / 串行。SelectableTable 不在内部并发。
   */
  onClick: (selectedRows: T[]) => void | Promise<void>;
}

export interface SelectableTableProps<T extends object> {
  rowKey: keyof T | ((r: T) => string);
  columns: ColumnsType<T>;
  dataSource: T[];
  loading?: boolean;
  pagination?: TableProps<T>['pagination'];
  size?: TableProps<T>['size'];
  /** 顶部永久工具栏 —— 通常包含"新建"按钮 + 筛选下拉 + 搜索 */
  toolbar?: React.ReactNode;
  /** 工具栏动作列表（启用/禁用按 selectedRows 自动决定） */
  actions: ToolbarAction<T>[];
  /** 确认弹窗 / 失败提示 里展示的行标签 —— 缺省时不展示行列表 */
  rowLabel?: (r: T) => string;
  /** 是否禁用跨页保留选中（默认开启） */
  disablePreserveSelected?: boolean;
  /** 自定义 row 选项（如禁用某些行选择） */
  getCheckboxProps?: (record: T) => { disabled?: boolean; name?: string };
  scroll?: TableProps<T>['scroll'];
}

function getRowKey<T>(r: T, rowKey: SelectableTableProps<T extends object ? T : never>['rowKey']): React.Key {
  return typeof rowKey === 'function' ? rowKey(r as any) : ((r as any)[rowKey as string]);
}

function SelectableTable<T extends object>(props: SelectableTableProps<T>) {
  const {
    rowKey,
    columns,
    dataSource,
    loading,
    pagination,
    size,
    toolbar,
    actions,
    rowLabel,
    disablePreserveSelected,
    getCheckboxProps,
    scroll,
  } = props;

  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [confirmingAction, setConfirmingAction] = useState<ToolbarAction<T> | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  // 已选行 = 当前 dataSource 里 key 命中 selectedKeys 的子集（跨页保留时上一页已选行不在 dataSource，会被遗漏，因此用 keys 做权威源）
  const selectedRows = useMemo(() => {
    return dataSource.filter((r) => selectedKeys.includes(getRowKey(r, rowKey)));
  }, [selectedKeys, dataSource, rowKey]);

  // 检查动作启用状态：返回 { enabled, reason }
  const checkActionState = useCallback((action: ToolbarAction<T>) => {
    const result = action.enabledWhen(selectedRows);
    if (result === true) return { enabled: true, reason: '' };
    if (result === false) {
      return {
        enabled: false,
        reason: selectedRows.length === 0 ? '请先选择行' : '当前选择不允许此动作',
      };
    }
    return { enabled: false, reason: result }; // 字符串 = 禁用 + 自定义原因
  }, [selectedRows]);

  const runAction = async (action: ToolbarAction<T>) => {
    setBusyKey(action.key);
    try {
      await action.onClick(selectedRows);
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    } finally {
      setBusyKey(null);
    }
  };

  const handleClick = async (action: ToolbarAction<T>) => {
    if (action.needsConfirm) {
      setConfirmingAction(action);
      return;
    }
    await runAction(action);
  };

  const handleConfirm = async () => {
    const action = confirmingAction;
    setConfirmingAction(null);
    if (action) await runAction(action);
  };

  const renderActionButton = (action: ToolbarAction<T>) => {
    const { enabled, reason } = checkActionState(action);
    const isBusy = busyKey === action.key;
    const btn = (
      <Button
        size="small"
        icon={action.icon}
        danger={action.danger}
        disabled={!enabled || isBusy}
        loading={isBusy}
        onClick={() => handleClick(action)}
      >
        {action.label}
      </Button>
    );
    return reason ? <Tooltip key={action.key} title={reason}>{btn}</Tooltip> : <span key={action.key}>{btn}</span>;
  };

  const safeActions = actions.filter((a) => !a.danger);
  const dangerActions = actions.filter((a) => a.danger);

  const confirmTitle = (confirmingAction?.confirmTitle ?? `确认${confirmingAction?.label ?? '操作'}选中的 {N} 项？`)
    .replace('{N}', String(selectedRows.length));

  return (
    <div>
      {toolbar && (
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
            gap: 8,
            flexWrap: 'wrap',
          }}
        >
          {toolbar}
        </div>
      )}

      {/* 动作工具栏：永久可见，按钮启用/禁用按 selectedRows 自动决定 */}
      <div
        style={{
          padding: '8px 12px',
          background: selectedKeys.length > 0 ? '#e6f4ff' : '#fafafa',
          border: `1px solid ${selectedKeys.length > 0 ? '#91caff' : '#f0f0f0'}`,
          borderRadius: 6,
          marginBottom: 12,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          flexWrap: 'wrap',
          transition: 'background 0.2s, border-color 0.2s',
        }}
      >
        <span style={{ marginRight: 8, color: selectedKeys.length > 0 ? '#0958d9' : '#8c8c8c', fontSize: 13 }}>
          {selectedKeys.length > 0 ? <>已选 <strong>{selectedKeys.length}</strong> 项</> : '未选择行'}
        </span>
        <Space size={[6, 6]} wrap>
          {safeActions.map(renderActionButton)}
        </Space>
        <div style={{ flex: 1, minWidth: 4 }} />
        <Space size={[6, 6]} wrap>
          {dangerActions.map(renderActionButton)}
        </Space>
        {selectedKeys.length > 0 && (
          <Button size="small" type="link" onClick={() => setSelectedKeys([])}>
            取消选择
          </Button>
        )}
      </div>

      <Table<T>
        rowKey={typeof rowKey === 'function' ? (rowKey as any) : (rowKey as string)}
        columns={columns}
        dataSource={dataSource}
        loading={loading}
        pagination={pagination}
        size={size}
        scroll={scroll}
        rowSelection={{
          selectedRowKeys: selectedKeys,
          onChange: (keys) => setSelectedKeys(keys),
          preserveSelectedRowKeys: !disablePreserveSelected,
          getCheckboxProps,
        }}
        onRow={(record) => ({
          onClick: (e) => {
            // 防止点击「主入口」链接 / 操作链接时同时切换选择 —— 让 a/button click 走原本逻辑
            const target = e.target as HTMLElement;
            if (target.closest('a, button, .ant-checkbox-wrapper, .ant-popover, .ant-modal')) return;
            const k = getRowKey(record, rowKey);
            setSelectedKeys((prev) =>
              prev.includes(k) ? prev.filter((x) => x !== k) : [...prev, k]
            );
          },
          style: { cursor: 'pointer' },
        })}
      />

      <Modal
        title={confirmTitle}
        open={!!confirmingAction}
        onCancel={() => setConfirmingAction(null)}
        onOk={handleConfirm}
        okText={confirmingAction?.label ?? '确认'}
        okButtonProps={{ danger: confirmingAction?.danger }}
        cancelText="取消"
        destroyOnClose
      >
        {confirmingAction?.confirmDescription && (
          <p style={{ color: confirmingAction.danger ? '#ff4d4f' : '#666' }}>
            {confirmingAction.confirmDescription}
          </p>
        )}
        {rowLabel && selectedRows.length > 0 && (
          <>
            <div style={{ color: '#666', marginBottom: 6 }}>所选项：</div>
            <ul
              style={{
                maxHeight: 260,
                overflowY: 'auto',
                margin: 0,
                paddingLeft: 18,
                background: '#fafafa',
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: 12,
              }}
            >
              {selectedRows.slice(0, 50).map((r) => (
                <li key={String(getRowKey(r, rowKey))} style={{ marginBottom: 2 }}>
                  {rowLabel(r)}
                </li>
              ))}
              {selectedRows.length > 50 && (
                <li style={{ color: '#999', listStyle: 'none', marginTop: 6 }}>
                  …等共 {selectedRows.length} 项
                </li>
              )}
            </ul>
          </>
        )}
      </Modal>
    </div>
  );
}

export default SelectableTable;

// ──────────────────────────────────────────────────────────────────────
// 批量操作的"部分失败"helper：调用方在 onClick 里 await runBatch(...)
// 自动用 Promise.allSettled 收集结果，message.error 列出失败明细
// ──────────────────────────────────────────────────────────────────────

export async function runBatch<T>(
  rows: T[],
  perRow: (r: T) => Promise<void>,
  options: {
    rowLabel?: (r: T) => string;
    successMsg?: string;
    /** 是否允许并发 —— 默认 true；某些后端约束时改 false 串行 */
    concurrent?: boolean;
  } = {},
): Promise<{ ok: number; failed: Array<{ row: T; reason: string }> }> {
  const { rowLabel, successMsg, concurrent = true } = options;
  let results: PromiseSettledResult<void>[];
  if (concurrent) {
    results = await Promise.allSettled(rows.map((r) => perRow(r)));
  } else {
    results = [];
    for (const r of rows) {
      try {
        await perRow(r);
        results.push({ status: 'fulfilled', value: undefined });
      } catch (e: any) {
        results.push({ status: 'rejected', reason: e });
      }
    }
  }
  const failed: Array<{ row: T; reason: string }> = [];
  let ok = 0;
  results.forEach((res, i) => {
    if (res.status === 'fulfilled') ok++;
    else failed.push({
      row: rows[i],
      reason: (res.reason as any)?.message ?? '失败',
    });
  });
  if (failed.length === 0) {
    if (successMsg) message.success(successMsg);
    else message.success(`已处理 ${ok} 项`);
  } else {
    const lines = failed
      .slice(0, 5)
      .map(({ row, reason }) => `• ${rowLabel ? rowLabel(row) : ''} ${reason}`.trim())
      .join('\n');
    const more = failed.length > 5 ? `\n…等 ${failed.length - 5} 项` : '';
    message.error({
      content: (
        <div style={{ whiteSpace: 'pre-line', textAlign: 'left' }}>
          {`成功 ${ok} 项，失败 ${failed.length} 项：\n${lines}${more}`}
        </div>
      ),
      duration: 8,
    });
  }
  return { ok, failed };
}
