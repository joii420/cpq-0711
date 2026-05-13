import React from 'react';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const Step1SearchPart: React.FC<Props> = () => <div>Step1 占位</div>;

export default Step1SearchPart;
