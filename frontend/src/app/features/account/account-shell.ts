import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth/auth.store';
import { apiErrorMessage } from '../../core/api-errors';

@Component({
  selector: 'app-account-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="account">
      <nav class="account-nav" aria-label="Minha conta">
        <a routerLink="dados" routerLinkActive="active">Meus dados</a>
        <a routerLink="enderecos" routerLinkActive="active">Endereços</a>
        <a routerLink="senha" routerLinkActive="active">Senha</a>
        <button type="button" class="link" (click)="auth.logout()">Sair</button>
      </nav>
      <div class="account-content">
        @if (auth.user(); as user) {
          @if (!user.emailVerified) {
            <p class="notice" role="status">
              Confirme seu e-mail ({{ user.email }}) para avaliar produtos e receber avisos de pedido.
              @if (resent()) {
                <strong>{{ resent() }}</strong>
              } @else {
                <button type="button" class="link" (click)="resend()">Reenviar link</button>
              }
            </p>
          }
        }
        <router-outlet />
      </div>
    </div>
  `,
})
export class AccountShell {
  protected readonly auth = inject(AuthStore);
  private readonly http = inject(HttpClient);
  protected readonly resent = signal<string | null>(null);

  async resend(): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/api/auth/resend-verification', null));
      this.resent.set('Link enviado.');
    } catch (err) {
      this.resent.set(apiErrorMessage(err));
    }
  }
}
