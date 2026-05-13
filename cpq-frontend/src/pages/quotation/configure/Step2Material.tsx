import React from 'react';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const Step2Material: React.FC<Props> = () => <div>Step2 占位</div>;

export default Step2Material;
