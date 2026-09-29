import { Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthStore, safeReturnUrl } from '../../core/auth/auth.store';
import { apiErrorMessage, applyFieldErrors } from '../../core/api-errors';
import { FieldError } from '../../shared/ui/field-error';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink, FieldError],
  template: `
    <section class="auth-page">
      <h1>Criar conta</h1>
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="form">
        <label class="field">
          <span>Nome completo</span>
          <input formControlName="name" autocomplete="name" />
          <app-field-error [control]="form.controls.name" />
        </label>
        <label class="field">
          <span>E-mail</span>
          <input type="email" formControlName="email" autocomplete="email" />
          <app-field-error [control]="form.controls.email" />
        </label>
        <label class="field">
          <span>Senha</span>
          <input type="password" formControlName="password" autocomplete="new-password" aria-describedby="pwd-hint" />
          <small id="pwd-hint" class="hint">Mínimo de 8 caracteres.</small>
          <app-field-error [control]="form.controls.password" />
        </label>
        <label class="check">
          <input type="checkbox" formControlName="acceptTerms" />
          <span>Li e aceito os <a href="/termos" target="_blank">termos de uso</a> e a
            <a href="/privacidade" target="_blank">política de privacidade</a>.</span>
        </label>
        <app-field-error [control]="form.controls.acceptTerms" />
        <label class="check">
          <input type="checkbox" formControlName="marketingOptIn" />
          <span>Quero receber novidades e ofertas por e-mail.</span>
        </label>
        @if (error(); as e) {
          <p class="form-error" role="alert">{{ e }}</p>
        }
        <button type="submit" class="btn" [disabled]="pending()">{{ pending() ? 'Criando…' : 'Criar conta' }}</button>
      </form>
      <p>Já tem conta? <a routerLink="/entrar" [queryParams]="{ returnUrl: returnUrl() }">Entrar</a></p>
    </section>
  `,
})
export class Register {
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  readonly returnUrl = input<string>();
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(128)]],
    acceptTerms: [false, Validators.requiredTrue],
    marketingOptIn: [false],
  });

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    this.error.set(null);
    try {
      await this.auth.register(this.form.getRawValue());
      await this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
    } catch (err) {
      applyFieldErrors(this.form, err);
      this.error.set(apiErrorMessage(err));
    } finally {
      this.pending.set(false);
    }
  }
}
