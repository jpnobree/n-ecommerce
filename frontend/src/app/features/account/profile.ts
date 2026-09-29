import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { AuthStore, User } from '../../core/auth/auth.store';
import { apiErrorMessage, applyFieldErrors } from '../../core/api-errors';
import { FieldError } from '../../shared/ui/field-error';
import { digits } from '../../shared/digits';

@Component({
  selector: 'app-profile',
  imports: [ReactiveFormsModule, FieldError],
  template: `
    <h1>Meus dados</h1>
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="form">
      <label class="field">
        <span>E-mail</span>
        <input [value]="auth.user()?.email ?? ''" disabled />
      </label>
      <label class="field">
        <span>Nome completo</span>
        <input formControlName="name" autocomplete="name" />
        <app-field-error [control]="form.controls.name" />
      </label>
      <label class="field">
        <span>Celular (com DDD)</span>
        <input formControlName="phone" inputmode="tel" autocomplete="tel-national" />
        <app-field-error [control]="form.controls.phone" />
      </label>
      <label class="field">
        <span>CPF <small>(necessário para a nota fiscal)</small></span>
        <input formControlName="cpf" inputmode="numeric" />
        <app-field-error [control]="form.controls.cpf" />
      </label>
      <label class="field">
        <span>Data de nascimento</span>
        <input type="date" formControlName="birthDate" autocomplete="bday" />
        <app-field-error [control]="form.controls.birthDate" />
      </label>
      <label class="check">
        <input type="checkbox" formControlName="marketingOptIn" />
        <span>Quero receber novidades e ofertas por e-mail.</span>
      </label>
      @if (message(); as m) {
        <p [class]="ok() ? 'form-success' : 'form-error'" role="status">{{ m }}</p>
      }
      <button type="submit" class="btn" [disabled]="pending()">Salvar</button>
    </form>
  `,
})
export class Profile implements OnInit {
  protected readonly auth = inject(AuthStore);
  private readonly http = inject(HttpClient);
  protected readonly pending = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly ok = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    phone: ['', Validators.pattern(/^\(?\d{2}\)?\s?\d{4,5}-?\d{4}$/)],
    cpf: ['', Validators.pattern(/^\d{3}\.?\d{3}\.?\d{3}-?\d{2}$/)],
    birthDate: [''],
    marketingOptIn: [false],
  });

  async ngOnInit(): Promise<void> {
    const user = await firstValueFrom(this.http.get<User>('/api/me'));
    this.auth.setUser(user);
    this.form.patchValue({
      name: user.name,
      phone: user.phone ?? '',
      cpf: user.cpf ?? '',
      birthDate: user.birthDate ?? '',
      marketingOptIn: user.marketingOptIn,
    });
  }

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    this.message.set(null);
    const v = this.form.getRawValue();
    try {
      const user = await firstValueFrom(
        this.http.put<User>('/api/me', {
          name: v.name,
          phone: digits(v.phone) || null,
          cpf: digits(v.cpf) || null,
          birthDate: v.birthDate || null,
          marketingOptIn: v.marketingOptIn,
        }),
      );
      this.auth.setUser(user);
      this.ok.set(true);
      this.message.set('Dados salvos.');
    } catch (err) {
      applyFieldErrors(this.form, err);
      this.ok.set(false);
      this.message.set(apiErrorMessage(err));
    } finally {
      this.pending.set(false);
    }
  }
}
