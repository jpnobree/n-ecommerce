import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, RESPONSE_INIT, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { Meta, Title } from '@angular/platform-browser';
import { ActivatedRoute, ParamMap, Router, RouterLink, UrlSegment } from '@angular/router';
import { catchError, combineLatest, distinctUntilChanged, forkJoin, map, of, switchMap } from 'rxjs';
import {
  CategoryPage,
  Collection,
  Crumb,
  FacetValue,
  Facets,
  ListingResponse,
  ProductCard as Card,
  SortOption,
} from '../../core/catalog/catalog.models';
import { MoneyPipe } from '../../shared/money.pipe';
import { ProductCard } from '../../shared/ui/product-card';

type Mode = 'category' | 'collection' | 'new' | 'sale';
type ListParam = 'sizes' | 'colors' | 'collection' | 'gender';

const PAGE_SIZE = 24;
/** "Carregar mais" até a página 10 pela URL (?page=); além disso o usuário usa filtros. */
const MAX_PAGES_FROM_URL = 10;
const FILTER_PARAMS = ['sizes', 'colors', 'collection', 'gender', 'minPrice', 'maxPrice', 'inStock', 'onSale'];

const SORT_LABELS: Record<SortOption, string> = {
  newest: 'Mais recentes',
  best_sellers: 'Mais vendidos',
  price_asc: 'Menor preço',
  price_desc: 'Maior preço',
};

/**
 * Listagem do catálogo. A URL é a fonte da verdade dos filtros (compartilhável, botão voltar funciona);
 * mudar filtro/ordenação refaz a busca, mudar só ?page= não (quem carrega mais páginas é loadMore).
 */
@Component({
  selector: 'app-catalog-page',
  imports: [RouterLink, ProductCard, MoneyPipe],
  templateUrl: './catalog-page.html',
})
export class CatalogPage {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly response = inject(RESPONSE_INIT, { optional: true });

  protected readonly mode = this.route.snapshot.data['mode'] as Mode;
  protected readonly sortLabels = SORT_LABELS;
  protected readonly sortOptions = Object.keys(SORT_LABELS) as SortOption[];

  protected readonly heading = signal('');
  protected readonly breadcrumb = signal<Crumb[]>([]);
  protected readonly subcategories = signal<Crumb[]>([]);
  protected readonly notFound = signal(false);

  protected readonly items = signal<Card[]>([]);
  protected readonly facets = signal<Facets | null>(null);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly failed = signal(false);
  protected readonly filtersOpen = signal(false);

  private readonly query = toSignal(this.route.queryParamMap, { requireSync: true });

  protected readonly sort = computed(() => (this.query().get('sort') as SortOption) || 'newest');
  protected readonly hasMore = computed(() => this.items().length < this.total());
  protected readonly activeChips = computed(() => this.chips(this.query(), this.facets()));

  constructor() {
    const destroyRef = inject(DestroyRef);

    this.route.url
      .pipe(
        map((segments) => this.contextOf(segments)),
        distinctUntilChanged((a, b) => a.key === b.key),
        switchMap((ctx) => this.loadHeader(ctx)),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe();

    combineLatest([this.route.url, this.route.queryParamMap])
      .pipe(
        map(([segments, qp]) => ({ ctx: this.contextOf(segments), qp })),
        distinctUntilChanged((a, b) => a.ctx.key === b.ctx.key && this.filterKey(a.qp) === this.filterKey(b.qp)),
        switchMap(({ ctx, qp }) => {
          this.loading.set(true);
          this.failed.set(false);
          const pages = Math.min(Number(qp.get('page') ?? 0) + 1, MAX_PAGES_FROM_URL);
          return forkJoin(Array.from({ length: pages }, (_, i) => this.fetch(ctx, qp, i))).pipe(
            catchError(() => {
              this.failed.set(true);
              return of([] as ListingResponse[]);
            }),
          );
        }),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe((pages) => {
        this.loading.set(false);
        // Combinações de filtro/ordenação não são indexadas (PRD 21.3); a página base é.
        const filtered = [...FILTER_PARAMS, 'sort', 'page'].some((p) => this.route.snapshot.queryParamMap.has(p));
        this.meta.updateTag({ name: 'robots', content: filtered ? 'noindex, follow' : 'index, follow' });
        if (!pages.length) return;
        this.items.set(pages.flatMap((p) => p.content));
        this.facets.set(pages[0].facets);
        this.total.set(pages[0].totalElements);
      });
  }

  // ---- ações de filtro (sempre via URL) ----

  protected selected(name: ListParam): string[] {
    return (this.query().get(name) ?? '').split(',').filter(Boolean);
  }

  protected flag(name: 'inStock' | 'onSale'): boolean {
    return this.query().get(name) === 'true';
  }

  protected toggle(name: ListParam, value: string): void {
    const current = this.selected(name);
    const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
    this.update({ [name]: next.length ? next.join(',') : null });
  }

  protected setFlag(name: 'inStock' | 'onSale', on: boolean): void {
    this.update({ [name]: on ? 'true' : null });
  }

  protected setSort(value: string): void {
    this.update({ sort: value === 'newest' ? null : value });
  }

  /** Recebe reais digitados; a URL e a API usam centavos. */
  protected setPrice(minReais: string, maxReais: string): void {
    const cents = (v: string) => (v.trim() === '' || isNaN(Number(v)) ? null : Math.round(Number(v) * 100));
    this.update({ minPrice: cents(minReais), maxPrice: cents(maxReais) });
  }

  protected removeChip(chip: { param: string; value: string }): void {
    if (chip.param === 'price') this.update({ minPrice: null, maxPrice: null });
    else if (chip.param === 'inStock' || chip.param === 'onSale') this.update({ [chip.param]: null });
    else this.toggle(chip.param as ListParam, chip.value);
  }

  protected clearAll(): void {
    this.update(Object.fromEntries(FILTER_PARAMS.map((p) => [p, null])));
  }

  protected loadMore(): void {
    if (this.loadingMore()) return;
    const nextPage = Math.ceil(this.items().length / PAGE_SIZE);
    this.loadingMore.set(true);
    this.fetch(this.contextOf(this.route.snapshot.url), this.route.snapshot.queryParamMap, nextPage).subscribe({
      next: (res) => {
        this.items.update((list) => [...list, ...res.content]);
        this.loadingMore.set(false);
        void this.router.navigate([], { queryParams: { page: nextPage }, queryParamsHandling: 'merge', replaceUrl: true });
      },
      error: () => this.loadingMore.set(false),
    });
  }

  protected price(value: number | null | undefined): string {
    return value == null ? '' : String(value / 100);
  }

  /** Valores selecionados continuam visíveis mesmo se a contagem zerou com os outros filtros. */
  protected options(name: ListParam, values: FacetValue[] | undefined): FacetValue[] {
    const list = [...(values ?? [])];
    for (const v of this.selected(name)) {
      if (!list.some((f) => f.value === v)) list.push({ value: v, label: v, hex: null, count: 0 });
    }
    return list;
  }

  private update(params: Record<string, string | number | null>): void {
    void this.router.navigate([], { queryParams: { ...params, page: null }, queryParamsHandling: 'merge' });
  }

  // ---- dados ----

  private contextOf(segments: UrlSegment[]): { key: string; mode: Mode; value: string } {
    const value = segments.slice(1).map((s) => s.path).join('/');
    return { key: `${this.mode}:${value}`, mode: this.mode, value };
  }

  private filterKey(qp: ParamMap): string {
    return [...FILTER_PARAMS, 'sort'].map((p) => `${p}=${qp.get(p) ?? ''}`).join('&');
  }

  private fetch(ctx: { mode: Mode; value: string }, qp: ParamMap, page: number) {
    let params = new HttpParams().set('page', page).set('pageSize', PAGE_SIZE);
    for (const p of [...FILTER_PARAMS, 'sort']) {
      const v = qp.get(p);
      if (v) params = params.set(p, v);
    }
    if (ctx.mode === 'category') params = params.set('category', ctx.value);
    if (ctx.mode === 'collection') params = params.set('collection', ctx.value);
    if (ctx.mode === 'sale') params = params.set('onSale', 'true');
    return this.http.get<ListingResponse>('/api/products', { params });
  }

  private loadHeader(ctx: { mode: Mode; value: string }) {
    this.notFound.set(false);
    const done = (heading: string, description: string | null = null) => {
      this.heading.set(heading);
      this.title.setTitle(`${heading} | Atelier`);
      if (description) this.meta.updateTag({ name: 'description', content: description });
    };
    const missing = (err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 404) {
        this.notFound.set(true);
        if (this.response) this.response.status = 404;
      }
      return of(null);
    };

    switch (ctx.mode) {
      case 'category':
        return this.http.get<CategoryPage>('/api/categories/page', { params: { path: ctx.value } }).pipe(
          map((page) => {
            this.breadcrumb.set(page.breadcrumb.slice(0, -1));
            this.subcategories.set(page.children);
            done(page.category.metaTitle || page.category.name, page.category.metaDescription || page.category.description);
          }),
          catchError(missing),
        );
      case 'collection':
        return this.http.get<Collection>(`/api/collections/${ctx.value}`).pipe(
          map((c) => done(c.name, c.description)),
          catchError(missing),
        );
      case 'new':
        done('Novidades');
        return of(null);
      case 'sale':
        done('Promoções');
        return of(null);
    }
  }

  private chips(qp: ParamMap, facets: Facets | null) {
    const label = (list: FacetValue[] | undefined, v: string) => list?.find((f) => f.value === v)?.label ?? v;
    const chips: { param: string; value: string; label: string }[] = [];
    for (const v of this.selectedFrom(qp, 'sizes')) chips.push({ param: 'sizes', value: v, label: `Tam. ${label(facets?.sizes, v)}` });
    for (const v of this.selectedFrom(qp, 'colors')) chips.push({ param: 'colors', value: v, label: label(facets?.colors, v) });
    for (const v of this.selectedFrom(qp, 'collection')) chips.push({ param: 'collection', value: v, label: label(facets?.collections, v) });
    for (const v of this.selectedFrom(qp, 'gender')) chips.push({ param: 'gender', value: v, label: label(facets?.genders, v) });
    if (qp.get('minPrice') || qp.get('maxPrice')) chips.push({ param: 'price', value: '', label: 'Faixa de preço' });
    if (qp.get('inStock') === 'true') chips.push({ param: 'inStock', value: '', label: 'Disponível' });
    if (qp.get('onSale') === 'true') chips.push({ param: 'onSale', value: '', label: 'Em promoção' });
    return chips;
  }

  private selectedFrom(qp: ParamMap, name: ListParam): string[] {
    return (qp.get(name) ?? '').split(',').filter(Boolean);
  }
}
