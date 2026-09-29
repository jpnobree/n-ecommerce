import { HttpClient } from '@angular/common/http';
import { Injectable, PLATFORM_ID, effect, inject, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../auth/auth.store';

/** Ids favoritados do usuário logado (para os corações); visitante é levado ao login. */
@Injectable({ providedIn: 'root' })
export class WishlistStore {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  readonly ids = signal<Set<number>>(new Set());

  constructor() {
    if (!isPlatformBrowser(inject(PLATFORM_ID))) return;
    effect(() => {
      if (!this.auth.user()) {
        this.ids.set(new Set());
        return;
      }
      this.http.get<number[]>('/api/me/wishlist/ids').subscribe((ids) => this.ids.set(new Set(ids)));
    });
  }

  has(productId: number): boolean {
    return this.ids().has(productId);
  }

  async toggle(productId: number): Promise<void> {
    if (!this.auth.isAuthenticated()) {
      await this.router.navigate(['/entrar'], { queryParams: { returnUrl: this.router.url } });
      return;
    }
    const had = this.has(productId);
    await firstValueFrom(had ? this.http.delete(`/api/me/wishlist/${productId}`) : this.http.put(`/api/me/wishlist/${productId}`, null));
    this.ids.update((s) => {
      const next = new Set(s);
      if (had) next.delete(productId);
      else next.add(productId);
      return next;
    });
  }
}
