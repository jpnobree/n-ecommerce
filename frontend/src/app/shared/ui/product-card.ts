import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProductCard as Card } from '../../core/catalog/catalog.models';
import { MoneyPipe } from '../money.pipe';

@Component({
  selector: 'app-product-card',
  imports: [RouterLink, MoneyPipe],
  template: `
    @let p = product();
    <a class="product-card" [routerLink]="['/p', p.slug]">
      <div class="product-media" [class.sold-out]="!p.inStock">
        @if (p.imageUrl) {
          <img [src]="p.imageUrl" [alt]="p.name" loading="lazy" />
        }
        <div class="product-badges">
          @if (!p.inStock) {
            <span class="tag">Esgotado</span>
          } @else {
            @for (b of p.badges; track b) {
              <span class="tag">{{ b === 'NEW' ? 'Novo' : 'Promoção' }}</span>
            }
          }
        </div>
      </div>
      <h3 class="product-name">{{ p.name }}</h3>
      <p class="product-price">
        @if (p.salePrice) {
          <s aria-label="Preço original">{{ p.price | money }}</s>
          <strong>{{ p.salePrice | money }}</strong>
        } @else {
          <span>{{ p.price | money }}</span>
        }
      </p>
      @if (p.colors.length > 1) {
        <ul class="swatches" [attr.aria-label]="p.colors.length + ' cores'">
          @for (c of p.colors; track c.slug) {
            <li class="swatch" [style.background]="c.hex" [title]="c.name"></li>
          }
        </ul>
      }
    </a>
  `,
})
export class ProductCard {
  readonly product = input.required<Card>();
}
