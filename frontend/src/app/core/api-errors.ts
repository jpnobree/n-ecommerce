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
