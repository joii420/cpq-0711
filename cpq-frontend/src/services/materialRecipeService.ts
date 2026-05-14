import api from './api';

export interface MaterialRecipeLite {
  id: string;
  code: string;
  symbol: string;
  name: string;
  specLabel?: string;
  recipeType: 'locked' | 'editable' | 'partial';
  status?: 'ACTIVE' | 'INACTIVE';
  sortOrder?: number;
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

export interface ExistingPartMaterialElement {
  elementCode: string;
  elementName: string;
  pct: number;
  minPct: number | null;
  maxPct: number | null;
  isLocked: boolean;
}

export interface ExistingPartMaterial {
  hfPartNo: string;
  recipeBound: boolean;
  recipeCode: string | null;
  recipeSymbol: string | null;
  recipeName: string | null;
  recipeSpec: string | null;
  recipeType: 'locked' | 'editable' | 'partial' | null;
  elements: ExistingPartMaterialElement[];
}

export interface MaterialRecipeUpsertRequest {
  code: string;
  symbol: string;
  name: string;
  specLabel?: string;
  recipeType: 'locked' | 'editable' | 'partial';
  sortOrder?: number;
  status?: 'ACTIVE' | 'INACTIVE';
  elements: Array<{
    elementCode: string;
    elementName: string;
    defaultPct: number;
    minPct?: number;
    maxPct?: number;
    isLocked: boolean;
    sortOrder?: number;
  }>;
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

  async create(req: MaterialRecipeUpsertRequest): Promise<MaterialRecipeDetail> {
    const res = await api.post('/material-recipes', req);
    return res as MaterialRecipeDetail;
  },

  async update(id: string, req: MaterialRecipeUpsertRequest): Promise<MaterialRecipeDetail> {
    const res = await api.put(`/material-recipes/${id}`, req);
    return res as MaterialRecipeDetail;
  },

  async deleteSoft(id: string): Promise<void> {
    await api.delete(`/material-recipes/${id}`);
  },

  async loadForExisting(hfPartNo: string): Promise<ExistingPartMaterial> {
    const res = await api.get(
      `/quotations/configure/existing-part/${encodeURIComponent(hfPartNo)}/material`,
    );
    return res as ExistingPartMaterial;
  },
};
