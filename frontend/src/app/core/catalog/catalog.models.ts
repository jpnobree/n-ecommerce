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

// ---- Página de produto ----

export interface ColorOption {
  id: number;
  slug: string;
  name: string;
  hex: string | null;
}

export interface SizeOption {
  id: number;
  slug: string;
  name: string;
}

export interface VariantOption {
  id: number;
  sku: string;
  colorId: number;
  sizeId: number;
  price: number;
  effectivePrice: number;
}

export interface ProductImage {
  url: string;
  alt: string;
  colorId: number | null;
  width: number | null;
  height: number | null;
}

export interface ProductPage {
  id: number;
  slug: string;
  sku: string;
  name: string;
  description: string | null;
  material: string | null;
  careInstructions: string | null;
  gender: 'FEMALE' | 'MALE' | 'UNISEX';
  breadcrumb: Crumb[];
  price: number;
  salePrice: number | null;
  priceVaries: boolean;
  inStock: boolean;
  badges: ('NEW' | 'SALE')[];
  colors: ColorOption[];
  sizes: SizeOption[];
  variants: VariantOption[];
  images: ProductImage[];
  sizeChart: { name: string; columns: string[]; rows: string[][] } | null;
  seo: { title: string; description: string | null };
}

export type Availability = 'IN_STOCK' | 'LOW' | 'OUT';

export interface VariantAvailability {
  variantId: number;
  status: Availability;
}

export interface RelatedProducts {
  completeTheLook: ProductCard[];
  similar: ProductCard[];
}

// ---- Busca ----

export interface SearchResponse {
  query: string;
  correctedQuery: string | null;
  exactMatch: string | null;
  result: ListingResponse;
  suggestions: ProductCard[];
}

export interface Suggestions {
  products: ProductCard[];
  categories: Crumb[];
}

// ---- Home ----

export interface Banner {
  id: number;
  position: 'HERO' | 'CAMPAIGN' | 'STRIP';
  title: string;
  subtitle: string | null;
  ctaLabel: string | null;
  linkUrl: string | null;
  imageDesktopUrl: string | null;
  imageMobileUrl: string | null;
}

export interface Home {
  hero: Banner[];
  campaigns: Banner[];
  strip: Banner | null;
  categories: CategoryNode[];
  collections: Collection[];
  featured: ProductCard[];
  newArrivals: ProductCard[];
  bestSellers: ProductCard[];
}
