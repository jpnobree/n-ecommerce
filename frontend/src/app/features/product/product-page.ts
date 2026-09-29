import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, PLATFORM_ID, RESPONSE_INIT, computed, effect, inject, input, signal } from '@angular/core';
import { NgOptimizedImage, isPlatformBrowser } from '@angular/common';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, map, of, switchMap, tap } from 'rxjs';
import { Availability, ProductPage as Page, VariantAvailability, VariantOption } from '../../core/catalog/catalog.models';
import { SeoService, breadcrumbLd } from '../../core/seo/seo.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { RelatedProducts } from './related-products';
import { CartStore } from '../../core/cart/cart.store';
import { WishlistStore } from '../../core/cart/wishlist.store';
import { apiErrorMessage } from '../../core/api-errors';

type Loaded = { status: 'ok'; page: Page } | { status: 'not-found' } | { status: 'error' };

@Component({
  selector: 'app-product-page',
  imports: [RouterLink, MoneyPipe, NgOptimizedImage, RelatedProducts],
  templateUrl: './product-page.html',
})
export class ProductPage {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly seo = inject(SeoService);
  private readonly response = inject(RESPONSE_INIT, { optional: true });

  readonly slug = input.required<string>();

  private readonly loaded = toSignal(
    toObservable(this.slug).pipe(
      switchMap((slug) =>
        this.http.get<Page>(`/api/products/${encodeURIComponent(slug)}`).pipe(
          tap((page) => this.onLoaded(slug, page)),
          map((page): Loaded => ({ status: 'ok', page })),
          catchError((err: unknown) => {
            const notFound = err instanceof HttpErrorResponse && err.status === 404;
            if (notFound && this.response) this.response.status = 404;
            if (notFound) this.seo.set({ title: 'Produto não encontrado', noindex: true });
            return of<Loaded>(notFound ? { status: 'not-found' } : { status: 'error' });
          }),
        ),
      ),
    ),
  );

  protected readonly state = computed(() => this.loaded());
  protected readonly product = computed(() => {
    const s = this.loaded();
    return s?.status === 'ok' ? s.page : null;
  });

  /** Cor escolhida vem da URL (?cor=preto): compartilhável e indexável como a mesma página (canonical). */
  private readonly colorParam = toSignal(this.route.queryParamMap.pipe(map((q) => q.get('cor'))), { initialValue: null });
  protected readonly selectedColor = computed(() => {
    const p = this.product();
    if (!p) return null;
    return p.colors.find((c) => c.slug === this.colorParam()) ?? p.colors[0] ?? null;
  });
  protected readonly selectedSizeId = signal<number | null>(null);

  /** Disponibilidade é buscada só no navegador (sem cache): a página SSR pode estar em cache por 60 s. */
  private readonly availability = signal<Map<number, Availability> | null>(null);

  protected readonly selectedVariant = computed<VariantOption | null>(() => {
    const p = this.product();
    const color = this.selectedColor();
    const size = this.selectedSizeId();
    if (!p || !color || size == null) return null;
    return p.variants.find((v) => v.colorId === color.id && v.sizeId === size) ?? null;
  });

  protected readonly gallery = computed(() => {
    const p = this.product();
    if (!p) return [];
    const color = this.selectedColor();
    const forColor = p.images.filter((i) => i.colorId === color?.id);
    const general = p.images.filter((i) => i.colorId == null);
    const list = [...forColor, ...general];
    return list.length ? list : p.images;
  });

  protected readonly price = computed(() => {
    const p = this.product();
    if (!p) return null;
    const v = this.selectedVariant();
    if (v) return { full: v.price, effective: v.effectivePrice, from: false };
    const forColor = p.variants.filter((x) => x.colorId === this.selectedColor()?.id);
    const list = forColor.length ? forColor : p.variants;
    const effective = Math.min(...list.map((x) => x.effectivePrice));
    const full = Math.min(...list.map((x) => x.price));
    const varies = new Set(list.map((x) => x.effectivePrice)).size > 1;
    return { full, effective, from: varies };
  });

  protected readonly selectedStatus = computed(() => {
    const v = this.selectedVariant();
    return v ? this.statusOf(v.id) : null;
  });

  protected readonly activeImage = signal(0);

  constructor() {
    const browser = isPlatformBrowser(inject(PLATFORM_ID));
    effect(() => {
      const p = this.product();
      if (!p || !browser) return;
      this.http
        .get<VariantAvailability[]>(`/api/products/${p.slug}/availability`)
        .subscribe((list) => this.availability.set(new Map(list.map((a) => [a.variantId, a.status]))));
    });
    // Trocar de cor: volta para a primeira foto e mantém o tamanho se existir na nova cor.
    effect(() => {
      this.selectedColor();
      this.activeImage.set(0);
    });
  }

  protected selectColor(slug: string): void {
    void this.router.navigate([], { queryParams: { cor: slug }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  protected readonly wishlist = inject(WishlistStore);
  private readonly cart = inject(CartStore);
  protected readonly adding = signal(false);
  protected readonly added = signal(false);
  protected readonly bagMessage = signal<string | null>(null);

  protected async addToBag(): Promise<void> {
    const variant = this.selectedVariant();
    this.added.set(false);
    if (!variant) {
      this.bagMessage.set('Escolha um tamanho.');
      return;
    }
    this.adding.set(true);
    try {
      await this.cart.add(variant.id, 1);
      this.added.set(true);
      this.bagMessage.set('Adicionado à sacola.');
    } catch (err) {
      this.bagMessage.set(apiErrorMessage(err));
    } finally {
      this.adding.set(false);
    }
  }

  protected selectSize(sizeId: number): void {
    this.bagMessage.set(null);
    this.selectedSizeId.set(sizeId);
  }

  /** Status da variante cor selecionada × tamanho; null enquanto a disponibilidade não chegou. */
  protected sizeStatus(sizeId: number): Availability | null {
    const p = this.product();
    const color = this.selectedColor();
    const variant = p?.variants.find((v) => v.colorId === color?.id && v.sizeId === sizeId);
    if (!variant) return 'OUT';
    return this.statusOf(variant.id);
  }

  protected openSizeChart(dialog: HTMLDialogElement): void {
    dialog.showModal();
  }

  private statusOf(variantId: number): Availability | null {
    const map = this.availability();
    return map ? (map.get(variantId) ?? 'OUT') : null;
  }

  private onLoaded(requestedSlug: string, page: Page): void {
    // Slug antigo: 301 no SSR (buscadores atualizam o índice) e troca de URL no navegador.
    if (page.slug !== requestedSlug) {
      if (this.response) {
        this.response.status = 301;
        this.response.headers = { Location: `/p/${page.slug}` };
      } else {
        void this.router.navigate(['/p', page.slug], { replaceUrl: true, queryParamsHandling: 'preserve' });
      }
    }
    const path = `/p/${page.slug}`;
    const image = page.images[0]?.url ?? null;
    const prices = page.variants.map((v) => v.effectivePrice);
    this.seo.set({
      title: page.seo.title,
      description: page.seo.description,
      path,
      image,
      type: 'product',
      jsonLd: [
        {
          '@type': 'Product',
          name: page.name,
          sku: page.sku,
          description: page.seo.description ?? undefined,
          image: page.images.map((i) => this.seo.absolute(i.url)),
          brand: { '@type': 'Brand', name: 'Atelier' },
          material: page.material ?? undefined,
          offers: {
            '@type': 'AggregateOffer',
            priceCurrency: 'BRL',
            lowPrice: (Math.min(...prices) / 100).toFixed(2),
            highPrice: (Math.max(...prices) / 100).toFixed(2),
            offerCount: page.variants.length,
            availability: page.inStock ? 'https://schema.org/InStock' : 'https://schema.org/OutOfStock',
            url: this.seo.absolute(path),
          },
        },
        breadcrumbLd(this.seo, [
          { name: 'Início', path: '/' },
          ...page.breadcrumb.map((c) => ({ name: c.name, path: `/c/${c.path}` })),
          { name: page.name, path },
        ]),
      ],
    });
  }
}
