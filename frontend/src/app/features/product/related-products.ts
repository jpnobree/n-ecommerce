import { HttpClient } from '@angular/common/http';
import { Component, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap } from 'rxjs';
import { RelatedProducts as Related } from '../../core/catalog/catalog.models';
import { ProductCard } from '../../shared/ui/product-card';

/** "Complete o look" + semelhantes. Carregado só quando entra na tela (@defer). */
@Component({
  selector: 'app-related-products',
  imports: [ProductCard],
  template: `
    @if (related(); as r) {
      @if (r.completeTheLook.length) {
        <section class="shelf">
          <h2>Complete o look</h2>
          <ul class="shelf-list">
            @for (p of r.completeTheLook; track p.id) {
              <li><app-product-card [product]="p" /></li>
            }
          </ul>
        </section>
      }
      @if (r.similar.length) {
        <section class="shelf">
          <h2>Você também pode gostar</h2>
          <ul class="shelf-list">
            @for (p of r.similar; track p.id) {
              <li><app-product-card [product]="p" /></li>
            }
          </ul>
        </section>
      }
    }
  `,
})
export class RelatedProducts {
  readonly slug = input.required<string>();
  private readonly http = inject(HttpClient);

  protected readonly related = toSignal(
    toObservable(this.slug).pipe(
      switchMap((slug) => this.http.get<Related>(`/api/products/${slug}/related`).pipe(catchError(() => of(null)))),
    ),
  );
}
