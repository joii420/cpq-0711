/**
 * VersionSelectDropdown —— 核价单版本切换下拉（task-0713 F2）
 *
 * 复用于核价树页签（挂在带版本的料号节点上）与非树页签（挂在销售料号分组上）。
 * 按需拉取版本列表（展开时才 GET version-options），选中即 POST version-switch，
 * 成功后把返回值原样上抛给 onSwitched（由挂载方——ReadonlyProductCard——决定如何
 * 增量刷新当前卡片，不在本组件内做任何整单/整卡重查，守 AP-31）。
 *
 * 反 AP-50：本组件只负责"选版本 + 报告结果"，不持有/不派生任何渲染数据。
 */
import React, { useState, useEffect } from 'react';
import { Select, Spin, message } from 'antd';
import { costingOrderService } from '../../services/costingOrderService';
import type { VersionSwitchResult } from '../../services/costingOrderService';

interface VersionSelectDropdownProps {
  coid: string;
  lineItemId: string;
  componentId: string;
  partNo: string;
  /** 当前生效/已 override 的版本号，用于下拉高亮与展示值 */
  currentVersion?: string | null;
  /** 非 editable（非 PENDING 或非财务/管理员）时禁用交互，仅展示只读值 */
  disabled?: boolean;
  /** 切换成功后的回调，携带后端返回的增量刷新数据（costingCardValues/costingTotalAmount/affectedTabs） */
  onSwitched?: (result: VersionSwitchResult) => void;
}

const VersionSelectDropdown: React.FC<VersionSelectDropdownProps> = ({
  coid,
  lineItemId,
  componentId,
  partNo,
  currentVersion,
  disabled,
  onSwitched,
}) => {
  const [open, setOpen] = useState(false);
  const [loadingOptions, setLoadingOptions] = useState(false);
  const [switching, setSwitching] = useState(false);
  // null = 尚未拉取过；[] = 拉取过但为空（无 view_version 列/无其他版本）
  const [options, setOptions] = useState<string[] | null>(null);

  // ★ repair-0590（下拉给了料号它没有的版本 根因）：options 用 useState 且 loadOptions 只加载一次
  //   (if options !== null return)。父组件按渲染下标(<tr key={ri}>)标识行，切换后行序变化时 React 会
  //   复用同一 VersionSelectDropdown 实例——partNo prop 更新了但 options state 仍是上一个料号的旧列表，
  //   于是给本料号提供它根本没有的版本(如把 S-3120014539 的 [2002,2001,2000] 沿用到只有 [2000] 的
  //   S-3111320636)，用户一选就切到不存在的版本、被后端拒绝/(修复前)导致料号消失。
  //   身份维度(coid/lineItemId/componentId/partNo)任一变化即重置 options，强制按当前料号重新拉取。
  useEffect(() => {
    setOptions(null);
  }, [coid, lineItemId, componentId, partNo]);

  const loadOptions = async () => {
    if (options !== null || loadingOptions) return;
    setLoadingOptions(true);
    try {
      const res = await costingOrderService.getVersionOptions(coid, { lineItemId, componentId, partNo });
      setOptions(res.data?.options ?? []);
    } catch (e: any) {
      message.error(e?.response?.data?.message || e?.message || '版本列表加载失败');
      setOptions([]);
    } finally {
      setLoadingOptions(false);
    }
  };

  const handleChange = async (viewVersion: string) => {
    if (!viewVersion || viewVersion === currentVersion) {
      setOpen(false);
      return;
    }
    setSwitching(true);
    try {
      const res = await costingOrderService.switchVersion(coid, {
        lineItemId,
        componentId,
        partNo,
        viewVersion,
      });
      onSwitched?.(res.data);
      message.success(`已切换到版本 ${viewVersion}`);
    } catch (e: any) {
      // BL-0030 语义：不静默吞错误，展示后端错误原文
      message.error(e?.response?.data?.message || e?.message || '版本切换失败');
    } finally {
      setSwitching(false);
      setOpen(false);
    }
  };

  // 下拉选项：已拉取则用拉取结果；未拉取时至少把当前版本占位，避免 Select 值找不到 label 报警告
  const selectOptions = (options ?? (currentVersion ? [currentVersion] : [])).map((v) => ({
    value: v,
    label: v,
  }));

  return (
    <Select
      size="small"
      style={{ minWidth: 88 }}
      value={currentVersion ?? undefined}
      placeholder="—"
      disabled={!!disabled || switching}
      loading={switching}
      open={open}
      onDropdownVisibleChange={(v) => {
        setOpen(v);
        if (v) loadOptions();
      }}
      notFoundContent={loadingOptions ? <Spin size="small" /> : '无可选版本'}
      // 展开后异步拉版本列表期间，用居中 Spin 整体替换弹层内容，
      // 避免先显示「当前版本」单项占位、加载完成再瞬间撑开成完整列表的突兀观感。
      // 保留 selectOptions（含 currentVersion 占位）供选中框 label 正常显示、无 console 告警。
      popupRender={(menu) =>
        loadingOptions ? (
          <div style={{ padding: 12, textAlign: 'center' }}>
            <Spin size="small" />
          </div>
        ) : (
          menu
        )
      }
      options={selectOptions}
      onChange={handleChange}
      onClick={(e) => e.stopPropagation()}
    />
  );
};

export default VersionSelectDropdown;
