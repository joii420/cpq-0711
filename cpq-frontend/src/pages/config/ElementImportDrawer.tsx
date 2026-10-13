import React, { useEffect, useState } from 'react';
import { Alert, Button, Divider, Drawer, Space, Typography, message } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import CompactUploadDragger from '../../components/CompactUploadDragger';
import type { ApiError } from '../../services/api';
import { elementService, type ElementImportReport } from '../../services/elementService';
import { apiErrorMessage } from '../../utils/apiError';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  onImported: () => void;
}

const StatTile: React.FC<{ label: string; value: React.ReactNode; tone?: 'warn' | 'err' }> = ({ label, value, tone }) => (
  <div style={{ flex: 1, minWidth: 120, padding: '14px 16px', borderRight: '1px solid #f0f0f0' }}>
    <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginBottom: 4 }}>{label}</div>
    <div style={{ fontSize: 22, fontWeight: 600, lineHeight: 1.2, color: tone === 'warn' ? '#d48806' : tone === 'err' ? '#cf1322' : undefined }}>
      {value}
    </div>
  </div>
);

const detailMessages = (detail: unknown): string[] => {
  if (!detail || typeof detail !== 'object') return [];
  const source = detail as Record<string, unknown>;
  const conflicts = Array.isArray(source.conflicts) ? source.conflicts : [];
  const errors = source.rowErrors ?? source.errors;
  const conflictMessages = conflicts.map((item) => {
    if (!item || typeof item !== 'object') return String(item);
    const conflict = item as Record<string, unknown>;
    const symbol = conflict.symbol == null ? '未知元素符号' : String(conflict.symbol);
    const rows = Array.isArray(conflict.rows) && conflict.rows.length > 0
      ? `，Excel 行：${conflict.rows.map(String).join('、')}`
      : '';
    const names = Array.isArray(conflict.names) && conflict.names.length > 0
      ? `，中文名称：${conflict.names.map(String).join('、')}`
      : '';
    return `元素符号 ${symbol} 存在名称冲突${rows}${names}`;
  });
  const rowMessages = Array.isArray(errors) ? errors.map((item) => {
    if (typeof item === 'string') return item;
    if (!item || typeof item !== 'object') return String(item);
    const row = item as Record<string, unknown>;
    const rowNo = row.row ?? row.rowNumber;
    const reason = row.reason ?? row.message ?? JSON.stringify(item);
    return rowNo == null ? String(reason) : `第 ${String(rowNo)} 行：${String(reason)}`;
  }) : [];
  return [...conflictMessages, ...rowMessages];
};

const ElementImportDrawer: React.FC<Props> = ({ open, onClose, onImported }) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [report, setReport] = useState<ElementImportReport | null>(null);
  const [fatalError, setFatalError] = useState<string | null>(null);
  const [fatalDetail, setFatalDetail] = useState<unknown>(null);

  useEffect(() => {
    if (!open) return;
    setSelectedFile(null);
    setImporting(false);
    setReport(null);
    setFatalError(null);
    setFatalDetail(null);
  }, [open]);

  const fileList: UploadFile[] = selectedFile ? [{ uid: '-1', name: selectedFile.name, status: 'done' }] : [];

  const downloadTemplate = async () => {
    setDownloading(true);
    try {
      const blob = await elementService.downloadImportTemplate();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = '元素导入模板.xlsx';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (error: unknown) {
      message.error(apiErrorMessage(error, '模板下载失败，请稍后重试'));
    } finally {
      setDownloading(false);
    }
  };

  const importFile = async () => {
    if (!selectedFile) return;
    setImporting(true);
    setReport(null);
    setFatalError(null);
    setFatalDetail(null);
    try {
      const result = await elementService.importLibrary(selectedFile);
      setReport(result);
      message.success(`导入完成：新增 ${result.createdCount} 个，覆盖中文名 ${result.updatedNameCount} 个`);
      onImported();
    } catch (error: unknown) {
      setFatalError(apiErrorMessage(error, '导入失败，请检查文件'));
      setFatalDetail((error as ApiError).detail ?? null);
      setReport(null);
    } finally {
      setImporting(false);
    }
  };

  const reset = () => {
    setSelectedFile(null);
    setReport(null);
    setFatalError(null);
    setFatalDetail(null);
  };

  const rowErrors = detailMessages(fatalDetail);

  return (
    <Drawer
      title={report ? '元素导入完成' : '导入元素'}
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={<div style={{ textAlign: 'right' }}><Space>
        <Button onClick={reset}>重置</Button>
        <Button type="primary" loading={importing} disabled={!selectedFile || !!fatalError} onClick={importFile}>开始导入</Button>
        {report && <Button onClick={onClose}>完成</Button>}
      </Space></div>}
    >
      {fatalError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={fatalError}
          description={rowErrors.length > 0 ? (
            <div><div style={{ fontWeight: 500, marginBottom: 6 }}>错误明细（整批未写入）：</div>{rowErrors.map((item, index) => <div key={`${item}-${index}`}>{item}</div>)}</div>
          ) : '请修正文件后重新选择并导入。'}
        />
      )}

      {!report && (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="模板格式：元素符号 | 中文名称（两列，单个工作表）"
            description={<div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
              <Paragraph style={{ marginBottom: 0 }}>
                <Text strong>元素编号无需填写</Text>，新符号由系统自动发号并设为启用。已有符号只覆盖中文名称，元素编号、符号和状态保持不变。<br />
                同一文件内同一符号只能对应一个中文名称；发现冲突时整批失败，不会部分写入。
              </Paragraph>
              <Button icon={<DownloadOutlined />} loading={downloading} onClick={downloadTemplate} style={{ flex: 'none' }}>下载导入模板</Button>
            </div>}
          />
          <CompactUploadDragger
            accept=".xlsx"
            maxCount={1}
            multiple={false}
            fileList={fileList}
            beforeUpload={(file) => {
              setSelectedFile(file as unknown as File);
              setReport(null);
              setFatalError(null);
              setFatalDetail(null);
              return false;
            }}
            onRemove={() => { setSelectedFile(null); return true; }}
            disabled={importing}
            text="点击或拖拽 .xlsx 元素文件到此处"
            hint="仅支持单个 .xlsx 文件，最大 20 MB"
          />
        </>
      )}

      {report && (
        <>
          <div style={{ display: 'flex', flexWrap: 'wrap', border: '1px solid #f0f0f0', borderRadius: 8, overflow: 'hidden' }}>
            <StatTile label="读取行数" value={report.totalRows} />
            <StatTile label="新增元素" value={report.createdCount} />
            <StatTile label="覆盖中文名" value={report.updatedNameCount} tone={report.updatedNameCount > 0 ? 'warn' : undefined} />
            <StatTile label="名称未变化" value={report.unchangedCount} />
          </div>
          <Divider />
          <Alert type="success" showIcon message="元素导入成功" description="本次导入已原子完成；元素编号、符号和状态未被导入文件修改。" />
        </>
      )}
    </Drawer>
  );
};

export default ElementImportDrawer;
