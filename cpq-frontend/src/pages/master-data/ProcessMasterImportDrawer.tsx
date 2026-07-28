// ─────────────────────────────────────────────────────────────────────────────
// ProcessMasterImportDrawer —— 工序主数据批量导入抽屉（childtask-1 · F1，BL-0045）
//   结构照抄 pages/config/MaterialImportDrawer.tsx：模板下载 + 上传 + 结果报告。
//   upsert 覆盖语义（工序编号唯一键）；导入后 onImported 回调刷新工序列表。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useEffect, useState } from 'react';
import {
  Drawer, Button, Space, Typography, Alert, Table, Divider, message,
} from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import CompactUploadDragger from '../../components/CompactUploadDragger';
import {
  importProcesses,
  downloadProcessTemplate,
  type ProcessMasterImportReport,
} from '../../services/v6MasterDataService';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  onImported?: () => void;
}

/** 工序主数据导入抽屉(childtask-1 · F1)：模板下载 + 上传 + 结果报告 */
const ProcessMasterImportDrawer: React.FC<Props> = ({ open, onClose, onImported }) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [report, setReport] = useState<ProcessMasterImportReport | null>(null);

  // 每次打开重置内部状态，避免复用上次导入的报告/文件
  useEffect(() => {
    if (open) {
      setSelectedFile(null);
      setImporting(false);
      setReport(null);
    }
  }, [open]);

  const fileList: UploadFile[] = selectedFile
    ? [{ uid: '-1', name: selectedFile.name, status: 'done' }]
    : [];

  const handleDownloadTemplate = async () => {
    setDownloading(true);
    try {
      const blob = await downloadProcessTemplate();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'process_master_template.xlsx';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch {
      message.error('模板下载失败，请稍后重试');
    } finally {
      setDownloading(false);
    }
  };

  const handleImport = async () => {
    if (!selectedFile) return;
    setImporting(true);
    setReport(null);
    try {
      const res = await importProcesses(selectedFile);
      setReport(res);
      message.success(`导入完成：新增 ${res.insertedCount} 条，更新 ${res.updatedCount} 条`);
    } catch (e: any) {
      // 脏数据不是错误(走 200 报告)；此处仅"文件本身不可用"或服务异常
      if (e?.httpStatus === 400) {
        message.error(e?.message ?? '文件不合法，请检查模板列与格式');
      } else {
        message.error('导入失败，请检查文件');
      }
    } finally {
      setImporting(false);
    }
  };

  const handleDone = () => {
    onClose();
    onImported?.();
  };

  /** 重置：清空已选文件与导入报告（task-0728 · F6 footer 统一） */
  const handleReset = () => {
    setSelectedFile(null);
    setReport(null);
  };

  return (
    <Drawer
      title="导入工序主数据"
      placement="right"
      width={840}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={handleReset}>重置</Button>
            <Button
              type={report ? 'default' : 'primary'}
              loading={importing}
              disabled={!selectedFile}
              onClick={handleImport}
            >
              {importing ? '导入中…' : '开始导入'}
            </Button>
            {report && <Button type="primary" onClick={handleDone}>完成</Button>}
          </Space>
        </div>
      }
    >
      {/* 1. 模板下载区 */}
      <Alert
        style={{ marginBottom: 16 }}
        type="info"
        showIcon
        message="导入说明"
        description={
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
            <Paragraph style={{ marginBottom: 0 }}>
              首个 sheet 按中文列名读取，<Text strong>【工序编号】</Text> 与 <Text strong>【工序名称】</Text> 两列必填，
              其余列（工序类别/是否外协/标准币种/标准单位/默认不良率）选填；
              写入语义为 <Text strong>覆盖更新</Text>（同工序编号重导会刷新名称）。
            </Paragraph>
            <Button
              icon={<DownloadOutlined />}
              loading={downloading}
              onClick={handleDownloadTemplate}
              style={{ flex: 'none' }}
            >
              下载模板
            </Button>
          </div>
        }
      />

      {/* 2. 上传区 */}
      <CompactUploadDragger
        accept=".xlsx"
        maxCount={1}
        multiple={false}
        fileList={fileList}
        beforeUpload={(file) => {
          setSelectedFile(file as unknown as File);
          setReport(null);
          return false; // 阻止自动上传，改由「开始导入」手动触发
        }}
        onRemove={() => { setSelectedFile(null); return true; }}
        disabled={importing}
        text="点击或拖拽 .xlsx 工序主数据文件到此处"
        hint="仅支持单个 .xlsx 文件"
      />

      {/* 3. 结果报告区 */}
      {report && (
        <>
          <Divider>导入结果</Divider>
          <Alert
            type={report.skippedRowCount > 0 ? 'warning' : 'success'}
            showIcon
            message={
              <Space size="large" wrap>
                <span>共 <Text strong>{report.totalRows}</Text> 行</span>
                <span>新增 <Text strong>{report.insertedCount}</Text> 条</span>
                <span>更新 <Text strong>{report.updatedCount}</Text> 条</span>
                <span>跳过 <Text strong>{report.skippedRowCount}</Text> 行</span>
                <span>耗时 <Text strong>{report.durationMs}</Text> ms</span>
              </Space>
            }
          />

          {report.skipped && report.skipped.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">
                以下 {report.skipped.length} 行被跳过（脏数据，请据此修正 Excel 后重新导入）：
              </Text>
              <Table
                size="small"
                rowKey={(_, idx) => String(idx)}
                style={{ marginTop: 8 }}
                pagination={false}
                scroll={{ y: 320 }}
                dataSource={report.skipped}
                columns={[
                  { title: '行号', dataIndex: 'row', key: 'row', width: 80 },
                  { title: '原因', dataIndex: 'reason', key: 'reason' },
                  { title: '原值', dataIndex: 'raw', key: 'raw', width: 160,
                    render: (v?: string) => v ?? '—' },
                ]}
              />
            </div>
          )}
        </>
      )}
    </Drawer>
  );
};

export default ProcessMasterImportDrawer;
