import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, PLATFORM_ID, computed, effect, inject, signal, untracked } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable, firstValueFrom } from 'rxjs';
import { AuthStore } from '../auth/auth.store';

export interface SizeChoice {
  variantId: number;
  size: string;
  available: boolean;
}

export interface CartItem {
  id: number;
  variantId: number;
  productSlug: string;
  productName: string;
  sku: string;
  color: string;
  size: string;
  imageUrl: string | null;
  unitPrice: number;
  listPrice: number;
  quantity: number;
  lineTotal: number;
  status: 'OK' | 'QUANTITY_REDUCED' | 'OUT_OF_STOCK' | 'UNAVAILABLE';
  maxQuantity: number;
  sizes: SizeChoice[];
}

export interface ShippingOption {
  id: string;
  carrier: string;
  service: string;
  price: number;
  days: number;
}

export interface Cart {
  cartToken: string | null;
  items: CartItem[];
  count: number;
  totals: { subtotal: number; discount: number; shipping: number | null; shippingDiscount: number; total: number };
  coupon: { code: string; description: string | null } | null;
  shipping: { postalCode: string; selected: string; options: ShippingOption[] } | null;
  warnings: { type: string; itemId: number | null; message: string }[];
  canCheckout: boolean;
}

const TOKEN_KEY = 'atelier.cartToken';

/**
 * Sacola. O servidor é a fonte da verdade (preços, estoque, cupom); aqui fica só a última resposta.
 * Convidado: token em localStorage (não é credencial de conta, só dá acesso a esta sacola).
 * Ao entrar na conta, a sacola do convidado é juntada à da conta e o token descartado.
 */
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));

  private readonly _cart = signal<Cart | null>(null);
  readonly cart = this._cart.asReadonly();
  readonly count = computed(() => this._cart()?.count ?? 0);

  constructor() {
    if (!this.browser) return;
    // Recarrega quando a sessão muda (entrou, saiu). Ao entrar com sacola de convidado: junta.
    effect(() => {
      const user = this.auth.user();
      untracked(() => {
        const sync = user && this.token()
          ? this.run(this.http.post<Cart>('/api/cart/merge', null, { headers: this.headers() }), true)
          : this.load();
        sync.catch(() => undefined); // só o contador do cabeçalho; a página da sacola trata o erro ao recarregar
      });
    });
  }

  load(): Promise<void> {
    return this.run(this.http.get<Cart>('/api/cart', { headers: this.headers() }));
  }

  add(variantId: number, quantity = 1): Promise<void> {
    return this.run(this.http.post<Cart>('/api/cart/items', { variantId, quantity }, { headers: this.headers() }));
  }

  update(itemId: number, change: { quantity?: number; variantId?: number }): Promise<void> {
    return this.run(this.http.patch<Cart>(`/api/cart/items/${itemId}`, change, { headers: this.headers() }));
  }

  remove(itemId: number): Promise<void> {
    return this.run(this.http.delete<Cart>(`/api/cart/items/${itemId}`, { headers: this.headers() }));
  }

  applyCoupon(code: string): Promise<void> {
    return this.run(this.http.put<Cart>('/api/cart/coupon', { code }, { headers: this.headers() }));
  }

  removeCoupon(): Promise<void> {
    return this.run(this.http.delete<Cart>('/api/cart/coupon', { headers: this.headers() }));
  }

  setShipping(postalCode: string, option?: string): Promise<void> {
    return this.run(this.http.put<Cart>('/api/cart/shipping', { postalCode, option }, { headers: this.headers() }));
  }

  private async run(request: Observable<Cart>, merged = false): Promise<void> {
    const cart = await firstValueFrom(request);
    if (cart.cartToken) localStorage.setItem(TOKEN_KEY, cart.cartToken);
    if (merged) localStorage.removeItem(TOKEN_KEY);
    this._cart.set(cart);
  }

  private token(): string | null {
    return this.browser ? localStorage.getItem(TOKEN_KEY) : null;
  }

  /** Logado, o Bearer identifica a sacola; o token de convidado só vai quando não há sessão (ou no merge). */
  private headers(): HttpHeaders {
    const token = this.token();
    return token ? new HttpHeaders({ 'X-Cart-Token': token }) : new HttpHeaders();
  }
}
