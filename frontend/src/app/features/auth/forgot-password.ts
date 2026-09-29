import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '../../core/api-errors';
import { FieldError } from '../../shared/ui/field-error';

@Component({
  selector: 'app-forgot-password',
  imports: [ReactiveFormsModule, RouterLink, FieldError],
  template: `
    <section class="auth-page">
      <h1>Recuperar senha</h1>
      @if (sent()) {
        <p role="status">Se houver uma conta com esse e-mail, você receberá um link para criar uma nova senha em instantes.</p>
        <p><a routerLink="/entrar">Voltar para o login</a></p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="form">
          <label class="field">
            <span>E-mail da conta</span>
            <input type="email" formControlName="email" autocomplete="email" />
            <app-field-error [control]="form.controls.email" />
          </label>
          @if (error(); as e) {
            <p class="form-error" role="alert">{{ e }}</p>
          }
          <button type="submit" class="btn" [disabled]="pending()">Enviar link</button>
        </form>
      }
    </section>
  `,
})
export class ForgotPassword {
  private readonly http = inject(HttpClient);
  protected readonly sent = signal(false);
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
  });

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    try {
      await firstValueFrom(this.http.post('/api/auth/forgot-password', this.form.getRawValue()));
      this.sent.set(true);
    } catch (err) {
      this.error.set(apiErrorMessage(err));
    } finally {
      this.pending.set(false);
    }
  }
}
