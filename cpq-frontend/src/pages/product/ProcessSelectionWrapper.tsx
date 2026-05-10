import React from 'react';
import { useParams } from 'react-router-dom';
import ProcessSelection from './ProcessSelection';

const ProcessSelectionWrapper: React.FC = () => {
  const { productId } = useParams<{ productId: string }>();
  if (!productId) return null;
  return <ProcessSelection productId={productId} />;
};

export default ProcessSelectionWrapper;
