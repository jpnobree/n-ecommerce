import { HttpClient } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ProductCard as Card } from '../../core/catalog/catalog.models';
import { WishlistStore } from '../../core/cart/wishlist.store';
import { ProductCard } from '../../shared/ui/product-card';

@Component({
  selector: 'app-favorites',
  imports: [ProductCard, RouterLink],
  template: `
    <h1>Favoritos</h1>
    @if (items(); as list) {
      @if (visible().length === 0) {
        <p class="empty">Nenhum favorito ainda. <a routerLink="/novidades">Ver novidades</a></p>
      }
      <ul class="product-grid">
        @for (p of visible(); track p.id; let i = $index) {
          <li>
            <app-product-card [product]="p" [priority]="i < 4" />
            <button type="button" class="link danger" (click)="wishlist.toggle(p.id)">Remover dos favoritos</button>
          </li>
        }
      </ul>
    }
  `,
})
export class Favorites {
  protected readonly wishlist = inject(WishlistStore);
  protected readonly items = toSignal(inject(HttpClient).get<Card[]>('/api/me/wishlist'));
  /** Remover some da lista na hora, sem recarregar. */
  protected readonly visible = computed(() => (this.items() ?? []).filter((p) => this.wishlist.has(p.id)));
}
