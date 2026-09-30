import { HttpClient } from '@angular/common/http';
import { DatePipe, NgOptimizedImage } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '../../core/api-errors';
import { MoneyPipe } from '../../shared/money.pipe';
import { orderStatusLabel } from '../checkout/order-confirmation';

interface OrderSummary {
  orderNumber: string;
  status: string;
  fulfillmentStatus: string;
  placedAt: string;
  total: number;
  itemCount: number;
  imageUrl: string | null;
}

interface Page {
  content: OrderSummary[];
  page: number;
  totalPages: number;
}

@Component({
  selector: 'app-orders',
  imports: [RouterLink, DatePipe, MoneyPipe, NgOptimizedImage],
  template: `
    <h1>Pedidos</h1>
    @if (error(); as e) {
      <p class="form-error" role="alert">{{ e }}</p>
    }
    @if (orders(); as list) {
      <ul class="order-list">
        @for (o of list; track o.orderNumber; let i = $index) {
          <li>
            <a [routerLink]="o.orderNumber" class="order-row">
              @if (o.imageUrl) {
                <img [ngSrc]="o.imageUrl" alt="" width="60" height="80" [priority]="i < 3" />
              }
              <span>
                <strong>{{ o.orderNumber }}</strong><br />
                <span class="muted small">{{ o.placedAt | date: 'dd/MM/yyyy' }} · {{ o.itemCount }} {{ o.itemCount === 1 ? 'item' : 'itens' }}</span>
              </span>
              <span>{{ label(o) }}</span>
              <strong>{{ o.total | money }}</strong>
            </a>
          </li>
        } @empty {
          <li class="empty">Você ainda não fez pedidos. <a routerLink="/novidades">Ver novidades</a></li>
        }
      </ul>
      @if (hasMore()) {
        <button type="button" class="btn-outline" (click)="load()">Ver mais</button>
      }
    } @else if (!error()) {
      <p class="muted">Carregando…</p>
    }
  `,
})
export class Orders {
  private readonly http = inject(HttpClient);
  protected readonly orders = signal<OrderSummary[] | null>(null);
  protected readonly hasMore = signal(false);
  protected readonly error = signal<string | null>(null);
  private page = 0;

  constructor() {
    void this.load();
  }

  /** Pago e já em andamento: mostra o passo do envio, que é o que o cliente quer saber. */
  protected label(o: OrderSummary): string {
    return orderStatusLabel(o.fulfillmentStatus !== 'UNFULFILLED' && o.status !== 'CANCELLED' ? o.fulfillmentStatus : o.status);
  }

  protected async load(): Promise<void> {
    try {
      const res = await firstValueFrom(this.http.get<Page>('/api/orders', { params: { page: this.page, size: 10 } }));
      this.orders.set([...(this.orders() ?? []), ...res.content]);
      this.page = res.page + 1;
      this.hasMore.set(this.page < res.totalPages);
    } catch (err) {
      this.error.set(apiErrorMessage(err));
    }
  }
}
