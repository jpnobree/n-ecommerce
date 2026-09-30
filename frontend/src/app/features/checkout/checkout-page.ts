import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CartStore } from '../../core/cart/cart.store';
import { apiErrorMessage } from '../../core/api-errors';
import { SeoService } from '../../core/seo/seo.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Address } from '../account/addresses';

interface Placed {
  orderNumber: string;
}

/**
 * Checkout em uma página: endereço → entrega → revisão. Valores vêm sempre do servidor; o botão só manda
 * endereço, opção de frete e uma chave de idempotência que é a mesma em qualquer nova tentativa do mesmo pedido.
 */
@Component({
  selector: 'app-checkout-page',
  imports: [RouterLink, MoneyPipe],
  template: `
    <h1>Finalizar compra</h1>
    @let cart = store.cart();

    @if (!cart || !addresses()) {
      <p class="muted">Carregando…</p>
    } @else if (cart.items.length === 0) {
      <div class="empty-state">
        <p>Sua sacola está vazia.</p>
        <a routerLink="/novidades" class="btn">Ver novidades</a>
      </div>
    } @else {
      @if (error(); as e) {
        <p class="form-error" role="alert">
          {{ e }}
          @if (backToCart()) { <a routerLink="/sacola">Revisar sacola</a> }
        </p>
      }

      <div class="cart">
        <div class="checkout-steps">
          <section>
            <h2>1. Endereço de entrega</h2>
            @for (a of addresses(); track a.id) {
              <label class="check address-option">
                <input type="radio" name="endereco" [checked]="a.id === addressId()" [disabled]="busy()" (change)="chooseAddress(a)" />
                <span>
                  <strong>{{ a.label || a.recipientName }}</strong><br />
                  {{ a.street }}, {{ a.number }}@if (a.complement) { - {{ a.complement }} } · {{ a.district }} · {{ a.city }}/{{ a.state }} · CEP {{ a.postalCode }}
                </span>
              </label>
            } @empty {
              <p>Você ainda não tem endereço cadastrado.</p>
            }
            <a routerLink="/conta/enderecos" [queryParams]="{ returnUrl: '/checkout' }">Adicionar endereço</a>
          </section>

          <section>
            <h2>2. Entrega</h2>
            @if (cart.shipping; as s) {
              @for (o of s.options; track o.id) {
                <label class="check">
                  <input type="radio" name="frete" [checked]="o.id === s.selected" [disabled]="busy()" (change)="chooseOption(o.id)" />
                  <span>{{ o.service }} · até {{ o.days }} dias úteis · {{ o.price | money }}</span>
                </label>
              }
            } @else {
              <p class="muted">Escolha o endereço para ver as opções.</p>
            }
          </section>
        </div>

        <aside class="cart-summary" aria-label="Resumo do pedido">
          <h2>3. Revisão</h2>
          @for (w of cart.warnings; track $index) {
            <p class="notice" role="status">{{ w.message }}</p>
          }
          <ul class="summary-items">
            @for (item of cart.items; track item.id) {
              <li>{{ item.quantity }}× {{ item.productName }} ({{ item.size }}) <span>{{ item.lineTotal | money }}</span></li>
            }
          </ul>
          <dl class="totals">
            <dt>Subtotal</dt>
            <dd>{{ cart.totals.subtotal | money }}</dd>
            @if (cart.totals.discount) {
              <dt>Desconto{{ cart.coupon ? ' (' + cart.coupon.code + ')' : '' }}</dt>
              <dd>− {{ cart.totals.discount | money }}</dd>
            }
            @if (cart.totals.shipping !== null) {
              <dt>Frete</dt>
              <dd>{{ cart.totals.shipping === cart.totals.shippingDiscount ? 'Grátis' : ((cart.totals.shipping - cart.totals.shippingDiscount) | money) }}</dd>
            }
            <dt class="total">Total</dt>
            <dd class="total">{{ cart.totals.total | money }}</dd>
          </dl>
          <button type="button" class="btn add-to-bag" [disabled]="!ready() || busy()" (click)="placeOrder()">
            {{ busy() ? 'Reservando…' : 'Confirmar pedido' }}
          </button>
          <p class="muted small">Os itens ficam reservados por 30 minutos para o pagamento.</p>
        </aside>
      </div>
    }
  `,
})
export class CheckoutPage {
  protected readonly store = inject(CartStore);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  protected readonly addresses = signal<Address[] | null>(null);
  protected readonly addressId = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly backToCart = signal(false);
  protected readonly ready = computed(() => {
    const cart = this.store.cart();
    return !!cart?.canCheckout && !!cart.shipping && this.addressId() !== null;
  });

  /** Uma chave por tentativa de pedido: repetir o clique (ou a requisição após falha de rede) não duplica. */
  private attemptKey: string | null = null;

  constructor() {
    inject(SeoService).set({ title: 'Finalizar compra', noindex: true });
    void this.init();
  }

  private async init(): Promise<void> {
    try {
      const [list] = await Promise.all([firstValueFrom(this.http.get<Address[]>('/api/me/addresses')), this.store.load()]);
      this.addresses.set(list);
      const chosen = list.find((a) => a.postalCode === this.store.cart()?.shipping?.postalCode) ?? list[0];
      if (chosen) await this.chooseAddress(chosen);
    } catch (err) {
      this.addresses.set(this.addresses() ?? []);
      this.error.set(apiErrorMessage(err));
    }
  }

  protected chooseAddress(a: Address): Promise<void> {
    this.attemptKey = null;
    this.addressId.set(a.id);
    return this.act(() => this.store.setShipping(a.postalCode, this.store.cart()?.shipping?.selected));
  }

  protected chooseOption(option: string): Promise<void> {
    this.attemptKey = null;
    const cep = this.addresses()?.find((a) => a.id === this.addressId())?.postalCode;
    return cep ? this.act(() => this.store.setShipping(cep, option)) : Promise.resolve();
  }

  protected async placeOrder(): Promise<void> {
    this.attemptKey ??= crypto.randomUUID();
    const body = { addressId: this.addressId(), shippingOption: this.store.cart()?.shipping?.selected };
    await this.act(async () => {
      const placed = await firstValueFrom(
        this.http.post<Placed>('/api/checkout', body, { headers: new HttpHeaders({ 'Idempotency-Key': this.attemptKey! }) }),
      );
      await this.router.navigate(['/checkout/confirmacao', placed.orderNumber]);
    });
  }

  private async act(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.backToCart.set(false);
    try {
      await action();
    } catch (err) {
      // Sem resposta (rede): a próxima tentativa reusa a chave e cai no mesmo pedido se ele foi criado
      if (!(err instanceof HttpErrorResponse && err.status === 0)) this.attemptKey = null;
      this.error.set(apiErrorMessage(err));
      const code = err instanceof HttpErrorResponse ? err.error?.code : null;
      if (code === 'CART_CHANGED' || code === 'INSUFFICIENT_STOCK') {
        this.backToCart.set(true);
        await this.store.load().catch(() => undefined);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
