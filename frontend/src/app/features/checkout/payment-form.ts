import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, OnInit, inject, input, output, signal, viewChild } from '@angular/core';
import { Stripe, StripeElements, loadStripe } from '@stripe/stripe-js';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '../../core/api-errors';
import { MoneyPipe } from '../../shared/money.pipe';

interface Intent {
  amount: number;
  clientSecret: string;
  publishableKey: string;
}

/**
 * Payment Element da Stripe (cartão, Pix e o que estiver ativo na conta). Os dados do cartão ficam no iframe da
 * Stripe, nunca passam pela loja. O resultado aqui é só indicativo: quem confirma o pagamento é o webhook no
 * servidor, e a página do pedido consulta o status depois.
 */
@Component({
  selector: 'app-payment-form',
  imports: [MoneyPipe],
  template: `
    @if (error(); as e) {
      <p class="form-error" role="alert">
        {{ e }}
        @if (!intent()) { <button type="button" class="link" (click)="init()">Tentar de novo</button> }
      </p>
    }
    <div #mount class="payment-element"></div>
    @if (intent(); as i) {
      <button type="button" class="btn add-to-bag" [disabled]="!ready() || busy()" (click)="pay()">
        {{ busy() ? 'Processando…' : 'Pagar ' + (i.amount | money) }}
      </button>
    } @else if (!error()) {
      <p class="muted">Carregando pagamento…</p>
    }
  `,
})
export class PaymentForm implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  readonly orderNumber = input.required<string>();
  /** O navegador concluiu a confirmação (sem redirecionar); o pedido passa a consultar o status. */
  readonly submitted = output<void>();

  private readonly mount = viewChild.required<ElementRef<HTMLElement>>('mount');
  protected readonly intent = signal<Intent | null>(null);
  protected readonly ready = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private stripe: Stripe | null = null;
  private elements: StripeElements | null = null;

  ngOnInit(): void {
    void this.init();
  }

  ngOnDestroy(): void {
    this.elements?.getElement('payment')?.destroy();
  }

  protected async init(): Promise<void> {
    this.error.set(null);
    try {
      const url = `/api/orders/${encodeURIComponent(this.orderNumber())}/payment-intent`;
      const intent = await firstValueFrom(this.http.post<Intent>(url, null));
      this.stripe = await loadStripe(intent.publishableKey, { locale: 'pt-BR' });
      if (!this.stripe) throw new Error('stripe');
      this.elements = this.stripe.elements({
        clientSecret: intent.clientSecret,
        appearance: { theme: 'flat', variables: { colorPrimary: '#111111', borderRadius: '0px', fontFamily: 'inherit' } },
      });
      const element = this.elements.create('payment', { layout: 'tabs' });
      element.on('ready', () => this.ready.set(true));
      element.mount(this.mount().nativeElement);
      this.intent.set(intent);
    } catch (err) {
      this.error.set(err instanceof Error && err.message === 'stripe' ? 'Não foi possível carregar o pagamento.' : apiErrorMessage(err));
    }
  }

  protected async pay(): Promise<void> {
    if (!this.stripe || !this.elements) return;
    this.busy.set(true);
    this.error.set(null);
    // 3DS e Pix podem precisar de outra tela: a Stripe volta para esta mesma página do pedido
    const { error } = await this.stripe.confirmPayment({
      elements: this.elements,
      confirmParams: { return_url: `${location.origin}/checkout/confirmacao/${encodeURIComponent(this.orderNumber())}` },
      redirect: 'if_required',
    });
    this.busy.set(false);
    if (error) {
      // Mensagem da Stripe já traduzida ("Seu cartão foi recusado."); o mesmo pedido aceita outra tentativa
      this.error.set(error.message ?? 'Pagamento não concluído. Tente outro meio de pagamento.');
      return;
    }
    this.submitted.emit();
  }
}
