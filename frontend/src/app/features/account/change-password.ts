import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { AuthResponse, AuthStore } from '../../core/auth/auth.store';
import { apiErrorMessage, applyFieldErrors } from '../../core/api-errors';
import { FieldError } from '../../shared/ui/field-error';

@Component({
  selector: 'app-change-password',
  imports: [ReactiveFormsModule, FieldError],
  template: `
    <h1>Alterar senha</h1>
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="form">
      <label class="field">
        <span>Senha atual</span>
        <input type="password" formControlName="currentPassword" autocomplete="current-password" />
        <app-field-error [control]="form.controls.currentPassword" />
      </label>
      <label class="field">
        <span>Nova senha</span>
        <input type="password" formControlName="newPassword" autocomplete="new-password" />
        <app-field-error [control]="form.controls.newPassword" />
      </label>
      @if (message(); as m) {
        <p [class]="ok() ? 'form-success' : 'form-error'" role="status">{{ m }}</p>
      }
      <button type="submit" class="btn" [disabled]="pending()">Alterar senha</button>
    </form>
  `,
})
export class ChangePassword {
  private readonly auth = inject(AuthStore);
  private readonly http = inject(HttpClient);
  protected readonly pending = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly ok = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    currentPassword: ['', Validators.required],
    newPassword: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(128)]],
  });

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    this.message.set(null);
    try {
      // A API encerra todas as sessões e devolve uma nova para este dispositivo.
      this.auth.setSession(await firstValueFrom(this.http.put<AuthResponse>('/api/me/password', this.form.getRawValue())));
      this.form.reset();
      this.ok.set(true);
      this.message.set('Senha alterada. As outras sessões foram encerradas.');
    } catch (err) {
      applyFieldErrors(this.form, err);
      this.ok.set(false);
      this.message.set(apiErrorMessage(err));
    } finally {
      this.pending.set(false);
    }
  }
}
