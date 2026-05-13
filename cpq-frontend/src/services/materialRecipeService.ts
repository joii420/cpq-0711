import api from './api';

export interface MaterialRecipeLite {
  id: string;
  code: string;
  symbol: string;
  name: string;
  specLabel?: string;
  recipeType: 'locked' | 'editable' | 'partial';
}

export interface MaterialRecipeElement {
  elementCode: string;
  elementName: string;
  defaultPct: number;
  minPct?: number;
  maxPct?: number;
  isLocked: boolean;
  sortOrder: number;
}

export interface MaterialRecipeDetail extends MaterialRecipeLite {
  elements: MaterialRecipeElement[];
}

export const materialRecipeService = {
  async list(): Promise<MaterialRecipeLite[]> {
    const res = await api.get('/material-recipes');
    return (res as MaterialRecipeLite[]) ?? [];
  },

  async detail(id: string): Promise<MaterialRecipeDetail> {
    const res = await api.get(`/material-recipes/${id}`);
    return res as MaterialRecipeDetail;
  },
};
