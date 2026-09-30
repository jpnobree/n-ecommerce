import { HttpErrorResponse } from '@angular/common/http';
import { FormGroup } from '@angular/forms';

/** Mensagens por código de erro da API (ErrorCode no backend). */
const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'E-mail ou senha inválidos.',
  ACCOUNT_BLOCKED: 'Conta bloqueada. Fale com o atendimento.',
  EMAIL_ALREADY_REGISTERED: 'Este e-mail já tem cadastro. Entre ou recupere sua senha.',
  TOKEN_INVALID_OR_EXPIRED: 'Este link é inválido ou expirou. Peça um novo.',
  WRONG_PASSWORD: 'A senha atual está incorreta.',
  CPF_IN_USE: 'Este CPF já está cadastrado em outra conta.',
  ADDRESS_LIMIT_REACHED: 'Você atingiu o limite de 10 endereços.',
  RATE_LIMITED: 'Muitas tentativas. Aguarde alguns minutos e tente novamente.',
  NOT_FOUND: 'Não encontrado.',
  MAX_QUANTITY: 'Máximo de 10 unidades por item.',
  COUPON_NOT_FOUND: 'Cupom inválido.',
  COUPON_NOT_STARTED: 'Este cupom ainda não está valendo.',
  COUPON_EXPIRED: 'Este cupom expirou.',
  COUPON_REQUIRES_LOGIN: 'Entre na sua conta para usar este cupom.',
  COUPON_FIRST_ORDER_ONLY: 'Cupom válido só na primeira compra.',
  COUPON_USAGE_LIMIT_REACHED: 'Este cupom esgotou.',
  COUPON_USER_LIMIT_REACHED: 'Você já usou este cupom.',
  COUPON_NO_ELIGIBLE_ITEMS: 'Nenhum item da sacola participa deste cupom.',
  WISHLIST_LIMIT: 'Limite de favoritos atingido.',
  CART_EMPTY: 'Sua sacola está vazia.',
  ORDER_TOTAL_TOO_LOW: 'O total mínimo do pedido é R$ 0,50.',
  INVALID_STATUS_TRANSITION: 'Este pedido não pode mais ser alterado.',
};

interface Problem {
  code?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}

function problem(err: unknown): Problem | null {
  return err instanceof HttpErrorResponse && err.error && typeof err.error === 'object' ? (err.error as Problem) : null;
}

export function apiErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse && err.status === 0) return 'Sem conexão com o servidor. Tente novamente.';
  const p = problem(err);
  if (p?.code === 'ACCOUNT_LOCKED') return `Conta bloqueada temporariamente. ${p.detail ?? ''}`.trim();
  // O detalhe da API já diz o que falta ("Disponível: 2 unidade(s)", "Faltam R$ 50,00...").
  if (p?.code === 'INSUFFICIENT_STOCK' || p?.code === 'COUPON_MIN_AMOUNT_NOT_REACHED') return p.detail ?? 'Não foi possível.';
  // Checkout recusado porque a sacola mudou: o detalhe lista o que mudou (preço, estoque, cupom)
  if (p?.code === 'CART_CHANGED') return `Sua sacola mudou: ${p.detail ?? 'revise antes de finalizar'}.`;
  if (p?.code && MESSAGES[p.code]) return MESSAGES[p.code];
  if (p?.code === 'VALIDATION_ERROR') return 'Verifique os campos destacados.';
  return 'Algo deu errado. Tente novamente.';
}

/** Marca nos controles os erros de campo devolvidos pela API. */
export function applyFieldErrors(form: FormGroup, err: unknown): void {
  for (const e of problem(err)?.errors ?? []) {
    const control = form.get(e.field);
    control?.setErrors({ server: e.message });
    control?.markAsTouched();
  }
}
