import React from 'react';
import { Drawer, Descriptions, Tag, Divider } from 'antd';
import type { MaterialBomItemDTO } from '../../services/v6MasterDataService';

interface Props {
  open: boolean;
  record: MaterialBomItemDTO | null;
  onClose: () => void;
}

const SYSTEM_TYPE_MAP: Record<string, { label: string; color: string }> = {
  QUOTE:   { label: '报价', color: 'blue' },
  PRICING: { label: '核价', color: 'orange' },
  BOTH:    { label: '共用', color: 'green' },
};

const V6BomItemDetailDrawer: React.FC<Props> = ({ open, record, onClose }) => {
  const fmt = (val: any) =>
    val !== undefined && val !== null && val !== '' ? String(val) : '—';
  const fmtNum = (val: number | undefined | null) =>
    val !== undefined && val !== null ? val : '—';
  const fmtBool = (val: boolean | undefined | null) =>
    val === true ? (
      <Tag color="blue">是</Tag>
    ) : val === false ? (
      <Tag color="default">否</Tag>
    ) : (
      <>—</>
    );

  return (
    <Drawer
      title={
        record
          ? `BOM 明细 — ${record.materialNo || ''} / ${record.customerNo || ''}`
          : 'BOM 明细详情'
      }
      placement="right"
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={null}
    >
      {record && (
        <>
          {/* 组 1：维度键 */}
          <Divider titlePlacement="left" style={{ marginTop: 0 }}>
            维度键
          </Divider>
          <Descriptions
            column={2}
            bordered
            size="small"
            labelStyle={{ width: 120, fontWeight: 500 }}
          >
            <Descriptions.Item label="系统类型">
              {(() => {
                const t = SYSTEM_TYPE_MAP[record.systemType];
                return t ? <Tag color={t.color}>{t.label}</Tag> : fmt(record.systemType);
              })()}
            </Descriptions.Item>
            <Descriptions.Item label="客户编号">{fmt(record.customerNo)}</Descriptions.Item>
            <Descriptions.Item label="料号">{fmt(record.materialNo)}</Descriptions.Item>
            <Descriptions.Item label="特征码">{fmt(record.characteristic)}</Descriptions.Item>
          </Descriptions>

          {/* 组 2：项次与工序 */}
          <Divider titlePlacement="left">项次与工序</Divider>
          <Descriptions
            column={2}
            bordered
            size="small"
            labelStyle={{ width: 120, fontWeight: 500 }}
          >
            <Descriptions.Item label="序号">{fmtNum(record.seqNo)}</Descriptions.Item>
            <Descriptions.Item label="组件编号">{fmt(record.componentNo)}</Descriptions.Item>
            <Descriptions.Item label="零件号">{fmt(record.partNo)}</Descriptions.Item>
            <Descriptions.Item label="工序号">{fmt(record.operationNo)}</Descriptions.Item>
            <Descriptions.Item label="工序顺序">{fmt(record.operationSeq)}</Descriptions.Item>
            <Descriptions.Item label="工序项次">{fmtNum(record.itemSeq)}</Descriptions.Item>
            <Descriptions.Item label="生效日期">{fmt(record.effectiveDatetime)}</Descriptions.Item>
            <Descriptions.Item label="失效日期">{fmt(record.expireDatetime)}</Descriptions.Item>
          </Descriptions>

          {/* 组 3：用量与损耗 */}
          <Divider titlePlacement="left">用量与损耗</Divider>
          <Descriptions
            column={2}
            bordered
            size="small"
            labelStyle={{ width: 130, fontWeight: 500 }}
          >
            <Descriptions.Item label="发料单位">{fmt(record.issueUnit)}</Descriptions.Item>
            <Descriptions.Item label="组成数量">{fmtNum(record.compositionQty)}</Descriptions.Item>
            <Descriptions.Item label="基础数量">{fmtNum(record.baseQty)}</Descriptions.Item>
            <Descriptions.Item label="组件用途类型">{fmt(record.componentUsageType)}</Descriptions.Item>
            <Descriptions.Item label="特性管理">{fmt(record.featureMgmt)}</Descriptions.Item>
            <Descriptions.Item label="上限百分比">{fmtNum(record.upperLimitPct)}</Descriptions.Item>
            <Descriptions.Item label="下限百分比">{fmtNum(record.lowerLimitPct)}</Descriptions.Item>
            <Descriptions.Item label="批次损耗">{fmtNum(record.scrapBatch)}</Descriptions.Item>
            <Descriptions.Item label="损耗率">{fmtNum(record.scrapRate)}</Descriptions.Item>
            <Descriptions.Item label="固定损耗">{fmtNum(record.fixedScrap)}</Descriptions.Item>
            <Descriptions.Item label="不良率">{fmtNum(record.defectRate)}</Descriptions.Item>
            <Descriptions.Item label="损耗率类型">{fmt(record.scrapRateType)}</Descriptions.Item>
            <Descriptions.Item label="启用数量公式">{fmtBool(record.useQtyFormula)}</Descriptions.Item>
            <Descriptions.Item label="数量公式" span={2}>
              {fmt(record.qtyFormula)}
            </Descriptions.Item>
          </Descriptions>

          {/* 组 4：选项追溯 */}
          <Divider titlePlacement="left">选项追溯</Divider>
          <Descriptions
            column={2}
            bordered
            size="small"
            labelStyle={{ width: 130, fontWeight: 500 }}
          >
            <Descriptions.Item label="特性管理">{fmt(record.featureMgmt)}</Descriptions.Item>
            <Descriptions.Item label="是否可选">{fmtBool(record.isOptional)}</Descriptions.Item>
            <Descriptions.Item label="工单展开选项">{fmt(record.woExpandOption)}</Descriptions.Item>
            <Descriptions.Item label="是否采购替代">{fmtBool(record.isPurchaseReplace)}</Descriptions.Item>
            <Descriptions.Item label="组件提前期">{fmtNum(record.componentLeadTime)}</Descriptions.Item>
            <Descriptions.Item label="主替代件">{fmt(record.mainSubstitute)}</Descriptions.Item>
            <Descriptions.Item label="附件件号">{fmt(record.attachedPart)}</Descriptions.Item>
            <Descriptions.Item label="ECN 号">{fmt(record.ecnNo)}</Descriptions.Item>
            <Descriptions.Item label="是否倒冲">{fmtBool(record.isBackflush)}</Descriptions.Item>
            <Descriptions.Item label="是否客供">{fmtBool(record.isCustomerSupply)}</Descriptions.Item>
            <Descriptions.Item label="计算类型">{fmt(record.calcType)}</Descriptions.Item>
            <Descriptions.Item label="回收折扣">{fmtNum(record.recoveryDiscount)}</Descriptions.Item>
            <Descriptions.Item label="回收货币">{fmt(record.recoveryCurrency)}</Descriptions.Item>
            <Descriptions.Item label="回收单位">{fmt(record.recoveryUnit)}</Descriptions.Item>
            <Descriptions.Item label="发料库位">{fmt(record.issueLocation)}</Descriptions.Item>
            <Descriptions.Item label="发料存储区">{fmt(record.issueStorage)}</Descriptions.Item>
            <Descriptions.Item label="FAS 组">{fmt(record.fasGroup)}</Descriptions.Item>
            <Descriptions.Item label="插件位置">{fmt(record.plugPosition)}</Descriptions.Item>
            <Descriptions.Item label="参考研发中心">{fmt(record.refRdCenter)}</Descriptions.Item>
          </Descriptions>

          {/* 组 5：审计 */}
          <Divider titlePlacement="left">审计信息</Divider>
          <Descriptions
            column={2}
            bordered
            size="small"
            labelStyle={{ width: 120, fontWeight: 500 }}
          >
            <Descriptions.Item label="记录 ID" span={2}>
              {fmt(record.id)}
            </Descriptions.Item>
            <Descriptions.Item label="更新时间">{fmt(record.updatedAt)}</Descriptions.Item>
            <Descriptions.Item label="更新人">{fmt(record.updatedBy)}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{fmt(record.createdAt)}</Descriptions.Item>
            <Descriptions.Item label="创建人">{fmt(record.createdBy)}</Descriptions.Item>
          </Descriptions>
        </>
      )}
    </Drawer>
  );
};

export default V6BomItemDetailDrawer;
