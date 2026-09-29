import { Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthStore, safeReturnUrl } from '../../core/auth/auth.store';
import { apiErrorMessage } from '../../core/api-errors';
import { FieldError } from '../../shared/ui/field-error';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink, FieldError],
  template: `
    <section class="auth-page">
      <h1>Entrar</h1>
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="form">
        <label class="field">
          <span>E-mail</span>
          <input type="email" formControlName="email" autocomplete="email" />
          <app-field-error [control]="form.controls.email" />
        </label>
        <label class="field">
          <span>Senha</span>
          <input type="password" formControlName="password" autocomplete="current-password" />
          <app-field-error [control]="form.controls.password" />
        </label>
        @if (error(); as e) {
          <p class="form-error" role="alert">{{ e }}</p>
        }
        <button type="submit" class="btn" [disabled]="pending()">{{ pending() ? 'Entrando…' : 'Entrar' }}</button>
      </form>
      <p><a routerLink="/recuperar-senha">Esqueci minha senha</a></p>
      <p>Não tem conta? <a routerLink="/cadastro" [queryParams]="{ returnUrl: returnUrl() }">Cadastre-se</a></p>
    </section>
  `,
})
export class Login {
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  readonly returnUrl = input<string>();
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    this.error.set(null);
    try {
      const { email, password } = this.form.getRawValue();
      await this.auth.login(email, password);
      await this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
    } catch (err) {
      this.error.set(apiErrorMessage(err));
    } finally {
      this.pending.set(false);
    }
  }
}
