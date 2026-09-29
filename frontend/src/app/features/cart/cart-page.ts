import { Component, inject, signal } from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import { RouterLink } from '@angular/router';
import { CartStore } from '../../core/cart/cart.store';
import { apiErrorMessage } from '../../core/api-errors';
import { SeoService } from '../../core/seo/seo.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { digits } from '../../shared/digits';

@Component({
  selector: 'app-cart-page',
  imports: [RouterLink, MoneyPipe, NgOptimizedImage],
  templateUrl: './cart-page.html',
})
export class CartPage {
  protected readonly store = inject(CartStore);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly quantities = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

  constructor() {
    inject(SeoService).set({ title: 'Sacola', noindex: true });
    void this.store.load();
  }

  protected update(itemId: number, change: { quantity?: number; variantId?: number }): Promise<void> {
    return this.act(() => this.store.update(itemId, change));
  }

  protected remove(itemId: number): Promise<void> {
    return this.act(() => this.store.remove(itemId));
  }

  protected applyCoupon(code: string): Promise<void> {
    if (!code.trim()) return Promise.resolve();
    return this.act(() => this.store.applyCoupon(code.trim()));
  }

  protected removeCoupon(): Promise<void> {
    return this.act(() => this.store.removeCoupon());
  }

  protected setShipping(postalCode: string, option?: string): Promise<void> {
    const cep = digits(postalCode);
    if (cep.length !== 8) {
      this.error.set('Informe um CEP com 8 dígitos.');
      return Promise.resolve();
    }
    return this.act(() => this.store.setShipping(cep, option));
  }

  private async act(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (err) {
      this.error.set(apiErrorMessage(err));
    } finally {
      this.busy.set(false);
    }
  }
}
