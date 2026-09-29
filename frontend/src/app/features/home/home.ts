import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, tap } from 'rxjs';
import { Home as HomeData } from '../../core/catalog/catalog.models';
import { SeoService } from '../../core/seo/seo.service';
import { ProductCard } from '../../shared/ui/product-card';

/**
 * Home configurável pelo admin (banners, vitrines). Uma chamada à API, renderizada no SSR.
 * ponytail: só o primeiro banner HERO (sem carrossel); carrossel quando houver campanha que peça.
 */
@Component({
  selector: 'app-home',
  imports: [RouterLink, ProductCard, NgOptimizedImage],
  templateUrl: './home.html',
})
export class Home {
  private readonly seo = inject(SeoService);

  protected readonly home = toSignal(
    inject(HttpClient)
      .get<HomeData>('/api/home')
      .pipe(
        tap(() =>
          this.seo.set({
            title: 'Atelier',
            description: 'Moda feminina, masculina e acessórios. Novidades toda semana, frete para todo o Brasil.',
            path: '/',
            jsonLd: [
              { '@type': 'Organization', name: 'Atelier', url: this.seo.absolute('/') },
              {
                '@type': 'WebSite',
                name: 'Atelier',
                url: this.seo.absolute('/'),
                potentialAction: {
                  '@type': 'SearchAction',
                  target: this.seo.absolute('/busca?q={search_term_string}'),
                  'query-input': 'required name=search_term_string',
                },
              },
            ],
          }),
        ),
        catchError(() => of(null)),
      ),
  );
}
