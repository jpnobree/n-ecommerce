import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth/auth.store';
import { apiErrorMessage } from '../../core/api-errors';
import { stripTokenFromUrl } from './strip-token';

@Component({
  selector: 'app-verify-email',
  imports: [RouterLink],
  template: `
    <section class="auth-page" role="status">
      <h1>Confirmação de e-mail</h1>
      @switch (state()) {
        @case ('pending') {
          <p>Confirmando…</p>
        }
        @case ('ok') {
          <p>E-mail confirmado. Obrigado!</p>
          <p><a routerLink="/" class="btn">Ir para a loja</a></p>
        }
        @case ('error') {
          <p class="form-error">{{ error() }}</p>
          <p>Entre na sua conta para pedir um novo link.</p>
        }
      }
    </section>
  `,
})
export class VerifyEmail implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  readonly token = input<string>();

  protected readonly state = signal<'pending' | 'ok' | 'error'>('pending');
  protected readonly error = signal('');

  async ngOnInit(): Promise<void> {
    const token = this.token();
    stripTokenFromUrl();
    if (!token) {
      this.error.set('Link inválido.');
      this.state.set('error');
      return;
    }
    try {
      await firstValueFrom(this.http.post('/api/auth/verify-email', { token }));
      const user = this.auth.user();
      if (user) this.auth.setUser({ ...user, emailVerified: true });
      this.state.set('ok');
    } catch (err) {
      this.error.set(apiErrorMessage(err));
      this.state.set('error');
    }
  }
}
