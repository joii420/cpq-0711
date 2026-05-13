import React from 'react';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const Step3Process: React.FC<Props> = () => <div>Step3 占位</div>;

export default Step3Process;
