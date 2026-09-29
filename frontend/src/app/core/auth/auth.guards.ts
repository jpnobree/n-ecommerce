import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStore, Role } from './auth.store';

// Guards são UX: quem garante o acesso é o backend. Todos esperam a recuperação inicial da sessão.

export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  await auth.whenReady();
  return auth.isAuthenticated() || router.createUrlTree(['/entrar'], { queryParams: { returnUrl: state.url } });
};

export const guestGuard: CanActivateFn = async () => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  await auth.whenReady();
  return !auth.isAuthenticated() || router.createUrlTree(['/conta']);
};

export const roleGuard =
  (roles: Role[]): CanActivateFn =>
  async (_route, state) => {
    const auth = inject(AuthStore);
    const router = inject(Router);
    await auth.whenReady();
    if (!auth.isAuthenticated()) return router.createUrlTree(['/entrar'], { queryParams: { returnUrl: state.url } });
    return auth.hasAnyRole(roles) || router.createUrlTree(['/']);
  };
