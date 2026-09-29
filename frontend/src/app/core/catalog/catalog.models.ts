/** Espelha os DTOs públicos do catálogo (backend: ListingDtos, CategoryDtos). Valores em centavos. */

export interface CategoryNode {
  id: number;
  name: string;
  slug: string;
  path: string;
  featured: boolean;
  children: CategoryNode[];
}

export interface Crumb {
  name: string;
  path: string;
}

export interface CategoryPage {
  category: { id: number; name: string; path: string; description: string | null; metaTitle: string | null; metaDescription: string | null };
  breadcrumb: Crumb[];
  children: Crumb[];
}

export interface Collection {
  id: number;
  name: string;
  slug: string;
  description: string | null;
}

export interface ColorChip {
  slug: string;
  name: string;
  hex: string | null;
}

export interface ProductCard {
  id: number;
  slug: string;
  name: string;
  price: number;
  salePrice: number | null;
  currency: string;
  imageUrl: string | null;
  colors: ColorChip[];
  inStock: boolean;
  badges: ('NEW' | 'SALE')[];
}

export interface FacetValue {
  value: string;
  label: string;
  hex: string | null;
  count: number;
}

export interface Facets {
  sizes: FacetValue[];
  colors: FacetValue[];
  genders: FacetValue[];
  collections: FacetValue[];
  price: { min: number | null; max: number | null };
}

export interface ListingResponse {
  content: ProductCard[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  facets: Facets;
}

export type SortOption = 'newest' | 'best_sellers' | 'price_asc' | 'price_desc';
