/**
 * CompactUploadDragger —— 紧凑横排上传框（task-0728 · F0-2）
 *
 * 4 个导入抽屉（核价数据 / 工序 / 材质库 / 元素价格）共用，避免 4 处各写一遍样式导致漂移。
 *
 * 与 antd `Upload.Dragger` 的关系：
 *   - 除 `text` / `hint` / `icon` 外，其余 props 原样透传给 `Upload.Dragger`
 *     （`accept` / `fileList` / `beforeUpload` / `onRemove` / `disabled` / `maxCount` / `multiple` …）；
 *   - 只替换拖拽区内部的排版（图标 22px 与文案同一行，高约 64~68px，改造前约 180px）；
 *   - **不碰文件列表**：`Upload` 的已选文件列表是拖拽区的兄弟节点，选中文件后文件名与移除按钮照常显示。
 *
 * 用法：
 *   <CompactUploadDragger
 *     accept=".xlsx"
 *     maxCount={1}
 *     multiple={false}
 *     fileList={fileList}
 *     beforeUpload={...}
 *     onRemove={...}
 *     disabled={importing}
 *     text="点击或拖拽 .xlsx 工序主数据文件到此处"
 *     hint="仅支持单个 .xlsx 文件"
 *   />
 */
import React from 'react';
import { Upload, theme } from 'antd';
import type { UploadProps } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import './CompactUploadDragger.css';

export interface CompactUploadDraggerProps extends Omit<UploadProps, 'type' | 'children'> {
  /** 主文案（第一行） */
  text: React.ReactNode;
  /** 次文案（第二行，12px 次要色）。不传则只渲染一行 */
  hint?: React.ReactNode;
  /** 自定义图标，默认 `<InboxOutlined />` */
  icon?: React.ReactNode;
}

const CompactUploadDragger: React.FC<CompactUploadDraggerProps> = ({
  text,
  hint,
  icon,
  className,
  disabled,
  ...uploadProps
}) => {
  const { token } = theme.useToken();

  // 自定义节点没有用 antd 的 p.ant-upload-text / p.ant-upload-hint 类名，
  // 因此 antd 自带的 disabled 置灰规则不会命中，这里自己处理。
  const iconColor = disabled ? token.colorTextDisabled : token.colorPrimary;
  const textColor = disabled ? token.colorTextDisabled : token.colorTextHeading;
  const hintColor = disabled ? token.colorTextDisabled : token.colorTextDescription;

  return (
    <Upload.Dragger
      {...uploadProps}
      disabled={disabled}
      className={className ? `compact-upload-dragger ${className}` : 'compact-upload-dragger'}
    >
      <div className="cud-body">
        <span className="cud-icon" style={{ color: iconColor }}>
          {icon ?? <InboxOutlined />}
        </span>
        <div>
          <div className="cud-text" style={{ color: textColor }}>
            {text}
          </div>
          {hint ? (
            <div className="cud-hint" style={{ color: hintColor }}>
              {hint}
            </div>
          ) : null}
        </div>
      </div>
    </Upload.Dragger>
  );
};

export default CompactUploadDragger;
