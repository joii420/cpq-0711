import React, { useEffect, useState } from 'react';
import {
  Drawer, Upload, Button, Space, Typography, Alert, Table, Divider, message,
} from 'antd';
import { UploadOutlined, DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import {
  materialRecipeService,
  type MaterialImportReport,
} from '../../services/materialRecipeService';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  onImported: () => void;
}

/** 材质库导入抽屉(task-0708 · F3)：模板下载 + 上传 + 结果报告 */
const MaterialImportDrawer: React.FC<Props> = ({ open, onClose, onImported }) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [report, setReport] = useState<MaterialImportReport | null>(null);

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
      const blob = await materialRecipeService.downloadTemplate();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'material_library_template.xlsx';
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
      const res = await materialRecipeService.importLibrary(selectedFile);
      setReport(res);
      message.success(`导入完成：${res.materialsUpserted} 种材质`);
    } catch (e: any) {
      // 脏数据不是错误(走 200 报告)；此处仅"文件本身不可用"或服务异常
      if (e?.httpStatus === 400) {
        message.error(e?.message ?? '文件不合法，请检查模板 sheet 与格式');
      } else {
        message.error('导入失败，请检查文件');
      }
    } finally {
      setImporting(false);
    }
  };

  const handleDone = () => {
    onClose();
    onImported();
  };

  return (
    <Drawer
      title="导入材质库"
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        report ? (
          <div style={{ textAlign: 'right' }}>
            <Button type="primary" onClick={handleDone}>完成</Button>
          </div>
        ) : null
      }
    >
      {/* 1. 模板下载区 */}
      <Alert
        type="info"
        showIcon
        message="导入说明"
        description={
          <div>
            <Paragraph style={{ marginBottom: 8 }}>
              仅读取 <Text strong>【材质编号】</Text> 与 <Text strong>【材质对应元素】</Text> 两个 sheet，其余忽略；
              含量填 <Text strong>0–1 小数</Text>，同一材质相加 = 1。
            </Paragraph>
            <Button
              icon={<DownloadOutlined />}
              loading={downloading}
              onClick={handleDownloadTemplate}
            >
              下载导入模板
            </Button>
          </div>
        }
      />

      <Divider />

      {/* 2. 上传区 */}
      <Upload.Dragger
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
      >
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">点击或拖拽 .xlsx 材质库文件到此处</p>
        <p className="ant-upload-hint">仅支持单个 .xlsx 文件</p>
      </Upload.Dragger>

      <div style={{ marginTop: 16 }}>
        <Button
          type="primary"
          icon={<UploadOutlined />}
          loading={importing}
          disabled={!selectedFile}
          onClick={handleImport}
        >
          {importing ? '导入中…' : '开始导入'}
        </Button>
      </div>

      {/* 3. 结果报告区 */}
      {report && (
        <>
          <Divider>导入结果</Divider>
          <Alert
            type={report.skippedRowCount > 0 ? 'warning' : 'success'}
            showIcon
            message={
              <Space size="large" wrap>
                <span>成功 <Text strong>{report.materialsUpserted}</Text> 种材质</span>
                <span><Text strong>{report.elementRowsInserted}</Text> 条元素</span>
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
                  { title: 'Sheet', dataIndex: 'sheet', key: 'sheet', width: 140 },
                  { title: '行号', dataIndex: 'row', key: 'row', width: 80 },
                  { title: '原因', dataIndex: 'reason', key: 'reason' },
                  { title: '原值', dataIndex: 'raw', key: 'raw', width: 140,
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

export default MaterialImportDrawer;
