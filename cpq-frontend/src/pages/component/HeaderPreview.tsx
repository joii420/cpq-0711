import React from 'react';
import type { FieldItem } from './types';
import './styles.css';

interface HeaderPreviewProps {
  fields: FieldItem[];
}

const HeaderPreview: React.FC<HeaderPreviewProps> = ({ fields }) => {
  return (
    <div className="cm-card-section">
      <div className="cm-card-section-header">
        <div className="cm-card-section-header-left">
          <span>📋 表头预览</span>
          <span className="cm-section-badge">{fields.length} 列</span>
        </div>
      </div>
      <div style={{ overflowX: 'auto' }}>
        {fields.length === 0 ? (
          <div style={{ padding: '16px 14px', color: '#c0c4cc', fontSize: 12 }}>
            暂无字段，请在下方添加
          </div>
        ) : (
          <table className="cm-preview-table">
            <thead>
              <tr>
                {fields.map((f, i) => (
                  <th
                    key={f.key}
                    className={f.is_subtotal ? 'cm-subtotal-th' : ''}
                  >
                    {f.name || `字段${i + 1}`}
                    {f.is_subtotal && <span style={{ marginLeft: 4, fontSize: 10 }}>★</span>}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                {fields.map((f) => (
                  <td
                    key={f.key}
                    className={f.is_subtotal ? 'cm-subtotal-col' : ''}
                  >
                    示例值
                  </td>
                ))}
              </tr>
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

export default HeaderPreview;
