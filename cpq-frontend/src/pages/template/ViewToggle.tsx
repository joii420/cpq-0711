import React from 'react';
import './styles.css';

interface ViewToggleProps {
  mode: 'detail' | 'simple';
  onChange: (mode: 'detail' | 'simple') => void;
}

const ViewToggle: React.FC<ViewToggleProps> = ({ mode, onChange }) => {
  return (
    <div className="tm-view-switch">
      <button
        className={`tm-view-btn${mode === 'detail' ? ' active' : ''}`}
        onClick={() => onChange('detail')}
      >
        明细
      </button>
      <button
        className={`tm-view-btn${mode === 'simple' ? ' active' : ''}`}
        onClick={() => onChange('simple')}
      >
        简易
      </button>
    </div>
  );
};

export default ViewToggle;
