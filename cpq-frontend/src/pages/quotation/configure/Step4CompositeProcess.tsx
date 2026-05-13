import React from 'react';
import type { PartState, CompositeProcessAdded } from '../ConfigureProductDrawer';

interface Props {
  parts: PartState[];
  addedCProcs: CompositeProcessAdded[];
  onChangeAdded: (next: CompositeProcessAdded[]) => void;
}

const Step4CompositeProcess: React.FC<Props> = () => <div>Step4 占位</div>;

export default Step4CompositeProcess;
