import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '../../core/api-errors';
import { SeoService } from '../../core/seo/seo.service';
import { MoneyPipe } from '../../shared/money.pipe';

interface Order {
  orderNumber: string;
  status: string;
  expiresAt: string;
  cancelReason: string | null;
  items: { productSlug: string; productName: string; color: string; size: string; quantity: number; lineTotal: number }[];
  subtotal: number;
  discount: number;
  shipping: number;
  shippingDiscount: number;
  total: number;
  couponCode: string | null;
  shippingAddress: { recipientName: string; street: string; number: string; complement: string | null; district: string; city: string; state: string; postalCode: string };
  shippingMethod: { service: string; days: number };
}

const STATUS: Record<string, string> = {
  PENDING_PAYMENT: 'Aguardando pagamento',
  PAYMENT_PROCESSING: 'Processando pagamento',
  PAID: 'Pago',
  CANCELLED: 'Cancelado',
};

const CANCEL_REASON: Record<string, string> = {
  PAYMENT_TIMEOUT: 'o prazo para pagamento terminou',
  CUSTOMER: 'você cancelou',
  REPLACED: 'um pedido mais recente da mesma sacola o substituiu',
};

@Component({
  selector: 'app-order-confirmation',
  imports: [RouterLink, MoneyPipe, DatePipe],
  template: `
    @if (error(); as e) {
      <p class="form-error" role="alert">{{ e }}</p>
    }
    @if (order(); as o) {
      <h1>Pedido {{ o.orderNumber }}</h1>
      <p><strong>{{ statusLabel(o.status) }}</strong></p>
      @if (o.status === 'PENDING_PAYMENT') {
        <p>Seus itens estão reservados até {{ o.expiresAt | date: 'HH:mm' }}.</p>
        <p class="notice">O pagamento (cartão e Pix) chega na próxima etapa do projeto.</p>
        <button type="button" class="link danger" [disabled]="busy()" (click)="cancel(o.orderNumber)">Cancelar pedido</button>
      } @else if (o.status === 'CANCELLED' && o.cancelReason) {
        <p>Cancelado porque {{ cancelReason(o.cancelReason) }}.</p>
      }

      <div class="cart">
        <ul class="summary-items">
          @for (i of o.items; track $index) {
            <li><a [routerLink]="['/p', i.productSlug]">{{ i.quantity }}× {{ i.productName }}</a> ({{ i.color }}, {{ i.size }}) <span>{{ i.lineTotal | money }}</span></li>
          }
        </ul>
        <aside class="cart-summary" aria-label="Valores">
          <dl class="totals">
            <dt>Subtotal</dt>
            <dd>{{ o.subtotal | money }}</dd>
            @if (o.discount) {
              <dt>Desconto{{ o.couponCode ? ' (' + o.couponCode + ')' : '' }}</dt>
              <dd>− {{ o.discount | money }}</dd>
            }
            <dt>Frete ({{ o.shippingMethod.service }}, até {{ o.shippingMethod.days }} dias úteis)</dt>
            <dd>{{ o.shipping === o.shippingDiscount ? 'Grátis' : ((o.shipping - o.shippingDiscount) | money) }}</dd>
            <dt class="total">Total</dt>
            <dd class="total">{{ o.total | money }}</dd>
          </dl>
          @let a = o.shippingAddress;
          <p class="muted small">
            Entrega para {{ a.recipientName }}: {{ a.street }}, {{ a.number }}@if (a.complement) { - {{ a.complement }} } ·
            {{ a.district }} · {{ a.city }}/{{ a.state }} · CEP {{ a.postalCode }}
          </p>
        </aside>
      </div>
    } @else if (!error()) {
      <p class="muted">Carregando…</p>
    }
  `,
})
export class OrderConfirmation implements OnInit {
  private readonly http = inject(HttpClient);
  readonly number = input.required<string>();
  protected readonly order = signal<Order | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  constructor() {
    inject(SeoService).set({ title: 'Pedido', noindex: true });
  }

  ngOnInit(): void {
    void this.load(this.http.get<Order>(`/api/orders/${encodeURIComponent(this.number())}`));
  }

  protected statusLabel(status: string): string {
    return STATUS[status] ?? status;
  }

  protected cancelReason(reason: string): string {
    return CANCEL_REASON[reason] ?? reason;
  }

  protected cancel(number: string): void {
    if (!confirm('Cancelar este pedido? Os itens voltam para o estoque.')) return;
    void this.load(this.http.post<Order>(`/api/orders/${encodeURIComponent(number)}/cancel`, null));
  }

  private async load(request: Observable<Order>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.order.set(await firstValueFrom(request));
    } catch (err) {
      this.error.set(apiErrorMessage(err));
    } finally {
      this.busy.set(false);
    }
  }
}
