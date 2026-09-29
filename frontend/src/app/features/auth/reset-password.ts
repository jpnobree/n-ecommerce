import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage, applyFieldErrors } from '../../core/api-errors';
import { FieldError } from '../../shared/ui/field-error';
import { stripTokenFromUrl } from './strip-token';

@Component({
  selector: 'app-reset-password',
  imports: [ReactiveFormsModule, RouterLink, FieldError],
  template: `
    <section class="auth-page">
      <h1>Nova senha</h1>
      @if (done()) {
        <p role="status">Senha alterada. Por segurança, encerramos as outras sessões.</p>
        <p><a routerLink="/entrar" class="btn">Entrar</a></p>
      } @else if (!token()) {
        <p class="form-error">Link inválido. <a routerLink="/recuperar-senha">Peça um novo link</a>.</p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="form">
          <label class="field">
            <span>Nova senha</span>
            <input type="password" formControlName="newPassword" autocomplete="new-password" />
            <app-field-error [control]="form.controls.newPassword" />
          </label>
          @if (error(); as e) {
            <p class="form-error" role="alert">{{ e }}</p>
          }
          <button type="submit" class="btn" [disabled]="pending()">Salvar senha</button>
        </form>
      }
    </section>
  `,
})
export class ResetPassword implements OnInit {
  private readonly http = inject(HttpClient);
  readonly token = input<string>();
  private tokenValue = '';

  protected readonly done = signal(false);
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    newPassword: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(128)]],
  });

  ngOnInit(): void {
    this.tokenValue = this.token() ?? '';
    stripTokenFromUrl();
  }

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(
        this.http.post('/api/auth/reset-password', { token: this.tokenValue, ...this.form.getRawValue() }),
      );
      this.done.set(true);
    } catch (err) {
      applyFieldErrors(this.form, err);
      this.error.set(apiErrorMessage(err));
    } finally {
      this.pending.set(false);
    }
  }
}
