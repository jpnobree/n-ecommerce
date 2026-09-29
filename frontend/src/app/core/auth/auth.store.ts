import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

export type Role = 'CUSTOMER' | 'OPERATOR' | 'ADMIN';

export interface User {
  id: number;
  email: string;
  name: string;
  phone: string | null;
  cpf: string | null;
  birthDate: string | null;
  emailVerified: boolean;
  marketingOptIn: boolean;
  roles: Role[];
}

export interface AuthResponse {
  accessToken: string;
  expiresIn: number;
  user: User;
}

export interface RegisterData {
  name: string;
  email: string;
  password: string;
  acceptTerms: boolean;
  marketingOptIn: boolean;
}

/**
 * Sessão do usuário. O access token fica só em memória (nunca em localStorage, PRD 8.2);
 * o refresh token vive num cookie HttpOnly que o JavaScript não enxerga.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly _user = signal<User | null>(null);
  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);

  private token: string | null = null;
  private refreshing: Promise<boolean> | null = null;
  private ready: Promise<unknown> = Promise.resolve();

  /** Tenta recuperar a sessão pelo cookie sem bloquear a renderização; os guards aguardam {@link whenReady}. */
  init(): void {
    this.ready = this.refresh();
  }

  whenReady(): Promise<unknown> {
    return this.ready;
  }

  accessToken(): string | null {
    return this.token;
  }

  hasAnyRole(roles: Role[]): boolean {
    return this._user()?.roles.some((r) => roles.includes(r)) ?? false;
  }

  /** Um único refresh em voo, compartilhado por todas as requisições que receberam 401. */
  refresh(): Promise<boolean> {
    this.refreshing ??= firstValueFrom(this.http.post<AuthResponse>('/api/auth/refresh', null))
      .then((res) => {
        this.setSession(res);
        return true;
      })
      .catch(() => {
        this.clear();
        return false;
      })
      .finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  async login(email: string, password: string): Promise<void> {
    this.setSession(await firstValueFrom(this.http.post<AuthResponse>('/api/auth/login', { email, password })));
  }

  async register(data: RegisterData): Promise<void> {
    this.setSession(await firstValueFrom(this.http.post<AuthResponse>('/api/auth/register', data)));
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/api/auth/logout', null));
    } finally {
      this.clear();
      await this.router.navigateByUrl('/');
    }
  }

  setSession(res: AuthResponse): void {
    this.token = res.accessToken;
    this._user.set(res.user);
  }

  setUser(user: User): void {
    this._user.set(user);
  }

  /** Sessão perdida no meio da navegação: volta ao login e retorna para onde estava. */
  sessionExpired(): void {
    this.clear();
    void this.router.navigate(['/entrar'], { queryParams: { returnUrl: this.router.url } });
  }

  private clear(): void {
    this.token = null;
    this._user.set(null);
  }
}

/** Só caminhos internos: evita open redirect via ?returnUrl=https://site-malicioso. */
export function safeReturnUrl(url: string | null | undefined, fallback = '/conta'): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/\\') ? url : fallback;
}
