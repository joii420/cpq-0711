import React, { useEffect, useState } from 'react';
import {
  Drawer, Upload, Button, Space, Typography, Alert, Table, Divider, message,
  Select, DatePicker,
} from 'antd';
import { UploadOutlined, DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO, PriceImportResultDTO, PriceImportRowDTO } from '../../types/element-price-strategy';

const { Text, Paragraph } = Typography;

/**
 * 价格导入抽屉（720） —— task-0722 · F4
 * 一次导入 = 一个源 × 一个日期 × N 个元素（§11.3）；逐行独立处理，失败行不阻断其他行入库（§11.3.2）。
 */
interface Props {
  open: boolean;
  onClose: () => void;
  /** 导入成功后回调（用于刷新元素列表「最后修改时间」等） */
  onImported?: () => void;
}

const PriceImportDrawer: React.FC<Props> = ({ open, onClose, onImported }) => {
  const [sources, setSources] = useState<PriceSourceDTO[]>([]);
  const [sourceId, setSourceId] = useState<string | undefined>(undefined);
  const [priceDate, setPriceDate] = useState<Dayjs>(dayjs());
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState<PriceImportResultDTO | null>(null);

  useEffect(() => {
    if (!open) return;
    setSourceId(undefined);
    setPriceDate(dayjs());
    setSelectedFile(null);
    setResult(null);
    elementPriceStrategyService.listSources({ status: 'ACTIVE' })
      .then(setSources)
      .catch((e: any) => message.error(e?.message ?? '价格源加载失败'));
  }, [open]);

  const fileList: UploadFile[] = selectedFile
    ? [{ uid: '-1', name: selectedFile.name, status: 'done' }]
    : [];

  const handleDownloadTemplate = async () => {
    setDownloading(true);
    try {
      const blob = await elementPriceStrategyService.downloadImportTemplate();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = '元素价格导入模板.xlsx';
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
    if (!sourceId) { message.warning('请选择价格源'); return; }
    if (!priceDate) { message.warning('请选择价格日期'); return; }
    if (!selectedFile) { message.warning('请选择要导入的文件'); return; }
    setImporting(true);
    setResult(null);
    try {
      const res = await elementPriceStrategyService.importPrices(
        selectedFile, sourceId, priceDate.format('YYYY-MM-DD'),
      );
      setResult(res);
      message.success(`导入完成：新增 ${res.createdCount} 条，覆盖 ${res.updatedCount} 条，失败 ${res.failedCount} 条`);
      onImported?.();
    } catch (e: any) {
      message.error(e?.message ?? '导入失败，请检查文件与参数');
    } finally {
      setImporting(false);
    }
  };

  const resultTagLabel: Record<PriceImportRowDTO['result'], string> = {
    CREATED: '新增', UPDATED: '覆盖', FAILED: '失败',
  };

  return (
    <Drawer
      title="价格导入"
      open={open}
      onClose={onClose}
      width={720}
      placement="right"
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>关闭</Button>
            <Button type="primary" loading={importing} onClick={handleImport}>开始导入</Button>
          </Space>
        </div>
      }
    >
      <Space size={16} style={{ width: '100%', marginBottom: 16 }} align="start">
        <div style={{ flex: 1 }}>
          <div style={{ marginBottom: 6 }}><span style={{ color: '#ff4d4f', marginRight: 3 }}>*</span>价格源</div>
          <Select
            style={{ width: '100%' }}
            placeholder="请选择价格源"
            value={sourceId}
            onChange={setSourceId}
            options={sources.map((s) => ({ value: s.id, label: s.sourceName }))}
          />
          <div style={{ marginTop: 5, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>只列出「启用」状态的源</div>
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ marginBottom: 6 }}><span style={{ color: '#ff4d4f', marginRight: 3 }}>*</span>价格日期</div>
          <DatePicker style={{ width: '100%' }} value={priceDate} onChange={(d) => d && setPriceDate(d)} allowClear={false} />
          <div style={{ marginTop: 5, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>本次文件里所有价格都记为这一天</div>
        </div>
      </Space>

      <Divider />

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <div style={{ fontWeight: 500 }}>上传价格文件</div>
        <Button size="small" icon={<DownloadOutlined />} loading={downloading} onClick={handleDownloadTemplate}>
          下载导入模板
        </Button>
      </div>
      <Upload.Dragger
        accept=".xlsx,.xls"
        maxCount={1}
        multiple={false}
        fileList={fileList}
        beforeUpload={(file) => {
          if (file.size > 5 * 1024 * 1024) {
            message.error('单次不超过 5MB');
            return Upload.LIST_IGNORE;
          }
          setSelectedFile(file as unknown as File);
          setResult(null);
          return false; // 阻止自动上传，改由「开始导入」手动触发
        }}
        onRemove={() => { setSelectedFile(null); return true; }}
        disabled={importing}
      >
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">点击或拖拽 Excel 文件到此区域</p>
        <p className="ant-upload-hint">支持 .xlsx / .xls，单次不超过 5MB</p>
      </Upload.Dragger>

      <Alert
        style={{ marginTop: 14 }}
        type="info"
        message={
          <span style={{ fontSize: 12 }}>
            模板列：<Text code>元素符号*</Text> <Text code>单价*</Text> <Text code>货币</Text> <Text code>计价单位</Text>
            　·　元素符号须在「元素管理」里已存在且为启用状态
          </span>
        }
      />

      {result && (
        <>
          <Divider>导入结果</Divider>
          <Alert
            type={result.failedCount > 0 ? 'warning' : 'success'}
            showIcon
            message={
              <div>
                <div>导入完成：新增 <Text strong>{result.createdCount}</Text> 条，覆盖 <Text strong>{result.updatedCount}</Text> 条，失败 <Text strong>{result.failedCount}</Text> 条。</div>
                <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginTop: 4 }}>
                  源：{result.sourceName}　日期：{result.priceDate}　操作人：{result.operatorName}　耗时 {(result.elapsedMs / 1000).toFixed(1)}s
                </div>
              </div>
            }
          />
          <Table<PriceImportRowDTO>
            style={{ marginTop: 12 }}
            size="small"
            rowKey="rowNo"
            pagination={false}
            dataSource={result.rows}
            columns={[
              { title: '行号', dataIndex: 'rowNo', width: 60 },
              { title: '元素符号', dataIndex: 'elementCode', width: 90 },
              { title: '单价', dataIndex: 'price', align: 'right' as const, render: (v: number) => v.toFixed(4) },
              { title: '货币', dataIndex: 'currency' },
              { title: '计价单位', dataIndex: 'priceUnit' },
              {
                title: '结果',
                dataIndex: 'result',
                render: (v: PriceImportRowDTO['result']) => <span style={{
                  display: 'inline-block', padding: '0 7px', borderRadius: 4, fontSize: 12,
                  color: v === 'CREATED' ? '#389e0d' : v === 'UPDATED' ? '#d46b08' : '#cf1322',
                  background: v === 'CREATED' ? '#f6ffed' : v === 'UPDATED' ? '#fffbe6' : '#fff2f0',
                  border: `1px solid ${v === 'CREATED' ? '#b7eb8f' : v === 'UPDATED' ? '#ffe58f' : '#ffccc7'}`,
                }}>{resultTagLabel[v]}</span>,
              },
              {
                title: '说明',
                dataIndex: 'message',
                render: (v: string | null, r) => (
                  <span style={{ color: r.result === 'FAILED' ? '#cf1322' : 'rgba(0,0,0,.45)', fontSize: 12 }}>{v ?? '—'}</span>
                ),
              },
            ]}
            onRow={(r) => (r.result === 'FAILED' ? { style: { background: '#fff2f0' } } : {})}
          />
          <div style={{ marginTop: 10, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
            失败行不影响其他行入库；修正后可重新导入（会走覆盖逻辑）。
          </div>
        </>
      )}
    </Drawer>
  );
};

export default PriceImportDrawer;
