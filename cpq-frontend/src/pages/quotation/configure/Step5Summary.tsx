import React from 'react';
import type { ProductType } from '../../../types/configure';
import type { PartState, CompositeProcessAdded } from '../ConfigureProductDrawer';

interface Props {
  productType: ProductType;
  parts: PartState[];
  addedCProcs: CompositeProcessAdded[];
  onUpdatePart: (idx: number, patch: Partial<PartState>) => void;
}

const Step5Summary: React.FC<Props> = () => <div>Step5 占位</div>;

export default Step5Summary;
