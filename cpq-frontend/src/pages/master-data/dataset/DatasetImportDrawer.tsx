// ─────────────────────────────────────────────────────────────────────────────
// DatasetImportDrawer —— 数据集 Excel 导入抽屉（task-260902 · F-6 / F-7）
//
// 三个数据集共用一个组件，只换 `dataset` prop：
//   · 基础核价 / 详细核价 → 主数据维护两个新页签内的「导入核价数据」按钮
//   · 报价数据           → 报价单管理工具栏的「导入报价数据」按钮
//
// 🚫 `frontend.md §1.1`：用 Drawer，不用 Modal。
// 🚫 现有「从基础数据导入」（QuoteBasicDataImportV6Drawer）与「料号核价 → 导入核价数据」
//    （PricingBasicDataImportDrawer）与本组件**完全无关**，一个字节都不碰（AC-35 / AC-43）。
//
// 🚨 原型与 AC 冲突（已上报主线，按 AC 原文实现）：
//    - 原型「数据导入-抽屉」把成功汇总留在抽屉里，靠底部「完成并关闭」手工关；
//    - AC-33 原文要求「导入成功后**抽屉自动关闭且列表自动刷新**，无需手工点刷新」。
//    ⇒ 以 AC-33 为准：成功即关抽屉 + 刷新列表；原型那张汇总表**内容一字不减**，
//      改由 notification 面板承载（抽屉已关闭，只能挪位置，不能丢信息）。
//    失败路径完全按原型「数据导入-校验失败」渲染，抽屉不关。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useEffect, useMemo, useState } from 'react';
import {
  Drawer, Upload, Button, Alert, Space, Table, Tag, Typography, message, notification,
} from 'antd';
import { FileExcelOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { UploadFile, UploadProps } from 'antd/es/upload/interface';
import { createDatasetApi } from './api';
import { DATASETS } from './datasetConfig';
import type {
  DatasetKey, ImportResult, ImportSheetSummary, ValidationError,
} from './types';
import ValidationErrorTable from './ValidationErrorTable';

const { Text } = Typography;
const { Dragger } = Upload;

function fmtSize(bytes?: number): string {
  if (!bytes && bytes !== 0) return '';
  return `${(bytes / 1024).toFixed(1)} KB`;
}

function fmtDuration(ms?: number): string {
  if (ms === undefined || ms === null) return '—';
  return `${(ms / 1000).toFixed(2)} s`;
}

/** 成功汇总表（原型「数据导入-抽屉」：Sheet / 轴值数 / 新建 / 升版 / 无变化） */
export const ImportSummaryTable: React.FC<{ summary: ImportSheetSummary[] }> = ({ summary }) => {
  const columns: ColumnsType<ImportSheetSummary> = [
    {
      title: 'Sheet',
      dataIndex: 'sheet',
      key: 'sheet',
      render: (v: string, r) => (
        <span>
          {v}
          {!r.versioned && <Tag style={{ marginLeft: 6 }}>免版本</Tag>}
        </span>
      ),
    },
    {
      title: '轴值数',
      key: 'axisCount',
      width: 90,
      render: (_: unknown, r) =>
        r.versioned
          ? <span className={r.axisCount ? undefined : 'ant-typography-secondary'}>{r.axisCount ?? 0}</span>
          : <Text type="secondary">—</Text>,
    },
    {
      title: '新建',
      key: 'created',
      width: 90,
      render: (_: unknown, r) => {
        // 免版本 sheet 出 inserted / updated；带版本 sheet 出 created / upgraded / unchanged
        if (!r.versioned) return <span>新增 {r.inserted ?? 0}</span>;
        const n = r.created ?? 0;
        return n > 0 ? <Tag color="green">{n}</Tag> : <span>0</span>;
      },
    },
    {
      title: '升版',
      key: 'upgraded',
      width: 90,
      render: (_: unknown, r) => {
        if (!r.versioned) return <span>覆盖 {r.updated ?? 0}</span>;
        const n = r.upgraded ?? 0;
        return n > 0 ? <Tag color="blue">{n}</Tag> : <span>0</span>;
      },
    },
    {
      title: '无变化',
      key: 'unchanged',
      width: 100,
      render: (_: unknown, r) => {
        if (!r.versioned) return null;
        // AC-39：空 sheet ≠ 清空 —— 明确告诉用户「该 sheet 为空，未做任何改动」
        if ((r.axisCount ?? 0) === 0) return <Text type="secondary">该 sheet 为空，未做任何改动</Text>;
        const n = r.unchanged ?? 0;
        return n > 0 ? <Tag>{n}</Tag> : <span>0</span>;
      },
    },
  ];

  return (
    <Table<ImportSheetSummary>
      size="small"
      rowKey={(r, i) => `${r.sheet}-${i}`}
      columns={columns}
      dataSource={summary ?? []}
      pagination={false}
    />
  );
};

interface Props {
  open: boolean;
  dataset: DatasetKey;
  onClose: () => void;
  /** 导入成功（AC-33：调用方负责关抽屉 + 刷新列表） */
  onSuccess?: (result: ImportResult) => void;
}

const DatasetImportDrawer: React.FC<Props> = ({ open, dataset, onClose, onSuccess }) => {
  const cfg = DATASETS[dataset];
  const api = useMemo(() => createDatasetApi(dataset), [dataset]);

  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [importing, setImporting] = useState(false);
  const [errors, setErrors] = useState<ValidationError[] | null>(null);
  const [fatal, setFatal] = useState<string | null>(null);

  const reset = () => { setFileList([]); setErrors(null); setFatal(null); };

  useEffect(() => { if (!open) reset(); }, [open]);

  const draggerProps: UploadProps = {
    name: 'file',
    multiple: false,
    maxCount: 1,
    accept: '.xlsx',
    fileList,
    showUploadList: false,
    disabled: importing,
    beforeUpload: (file) => {
      setFileList([file as unknown as UploadFile]);
      setErrors(null);
      setFatal(null);
      return false; // 交给「开始导入」手动提交，不走 antd 自动上传
    },
    onRemove: () => setFileList([]),
  };

  const picked = fileList[0] as unknown as (File & UploadFile) | undefined;

  const handleImport = async () => {
    if (!picked) { message.warning('请先选择 .xlsx 文件'); return; }
    setImporting(true);
    setErrors(null);
    setFatal(null);
    try {
      const file = ((picked as any).originFileObj ?? picked) as File;
      const r = await api.importFile(file);

      // AC-11 / AC-33：汇总内容一字不减，但抽屉按 AC-33 自动关闭 ⇒ 汇总挪到 notification
      notification.success({
        message: `导入成功 · ${cfg.label}`,
        description: (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Text>
              耗时 {fmtDuration(r.durationMs)} ｜ {(r.summary ?? []).length} 个 sheet 全部处理完成
            </Text>
            <ImportSummaryTable summary={r.summary ?? []} />
            <Text type="secondary">
              空 sheet 不等于清空 —— 库中原有数据原封不动。
            </Text>
          </Space>
        ),
        duration: 0, // 汇总要留给用户看，手动关
        style: { width: 720 },
      });

      onSuccess?.(r);   // 调用方：关抽屉 + 刷新列表（AC-33）
    } catch (e: any) {
      const status = e?.httpStatus;
      const payloadErrors = (e?.payload as any)?.errors as ValidationError[] | undefined;
      if (status === 400 && Array.isArray(payloadErrors) && payloadErrors.length > 0) {
        setErrors(payloadErrors);            // AC-6~AC-10 / AC-34：整份拒收，逐条列全
      } else {
        setFatal(e?.message ?? '导入失败');  // 500 已回滚 / 其它异常
      }
    } finally {
      setImporting(false);
    }
  };

  return (
    <Drawer
      title={`${cfg.importActionLabel} · ${cfg.label}`}
      width={760}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>关闭</Button>
            <Button
              type="primary"
              disabled={!picked}
              onClick={() => { setFileList([]); setErrors(null); setFatal(null); }}
            >
              重新选择文件
            </Button>
          </Space>
        </div>
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message={
            <span>
              本抽屉只接受 <b>{cfg.label}</b>（「{cfg.templateAlias}」）格式的文件。上传后
              <b>先全量校验</b>，任一必填项为空或主数据不存在，<b>整份拒收、一行不写</b>。
            </span>
          }
        />

        <Dragger {...draggerProps} style={{ background: '#fafafa' }}>
          <div style={{ padding: '16px 8px' }}>
            <div style={{ fontSize: 32, opacity: 0.35 }}>
              <FileExcelOutlined />
            </div>
            {picked ? (
              <>
                <p style={{ margin: '8px 0 4px' }}>
                  <b>{picked.name}</b> <Text type="secondary">· {fmtSize(picked.size)}</Text>
                </p>
                <p style={{ margin: 0 }}>
                  <Text type="secondary">点击或拖拽替换文件（仅支持 .xlsx）</Text>
                </p>
              </>
            ) : (
              <>
                <p style={{ margin: '8px 0 4px' }}>
                  <b>点击或拖拽 .xlsx 文件到此处</b>
                </p>
                <p style={{ margin: 0 }}>
                  <Text type="secondary">仅支持单个 .xlsx 文件</Text>
                </p>
              </>
            )}
          </div>
        </Dragger>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={onClose} disabled={importing}>取消</Button>
          <Button type="primary" loading={importing} disabled={!picked} onClick={handleImport}>
            开始导入
          </Button>
        </div>

        {fatal && <Alert type="error" showIcon message={fatal} />}

        {errors && (
          <>
            <Alert
              type="error"
              showIcon
              message={<b>导入校验未通过，共 {errors.length} 处问题，本次未写入任何数据</b>}
              description={
                <Text type="secondary">
                  校验在写库之前完成，因此库中数据<b>一行未变</b>。修正后重新上传即可。
                </Text>
              }
            />
            <ValidationErrorTable errors={errors} />
            <Text type="secondary">
              <b>行号是 Excel 物理行号</b>：带版本 sheet 第 1 行是表头、第 2 行是「轴 / 对比项」标记，
              数据从<b>第 3 行</b>起；免版本 sheet 无第 2 行，数据从第 2 行起。
            </Text>
          </>
        )}
      </Space>
    </Drawer>
  );
};

export default DatasetImportDrawer;
