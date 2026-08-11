import React from 'react';
import { Spin } from 'antd';

export interface CardValuesWarmBoundaryProps {
  loading: boolean;
  warming: boolean;
  children: React.ReactNode;
}

/** Exposes background card warming through the page's existing loading contract. */
export function CardValuesWarmBoundary({
  loading,
  warming,
  children,
}: CardValuesWarmBoundaryProps) {
  return (
    <div data-card-values-warming={warming ? 'true' : 'false'}>
      <Spin spinning={loading || warming}>
        {children}
      </Spin>
    </div>
  );
}
