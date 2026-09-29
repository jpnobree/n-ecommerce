import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./features/home/home').then((m) => m.Home) },

  { path: 'entrar', title: 'Entrar | Atelier', canActivate: [guestGuard], loadComponent: () => import('./features/auth/login').then((m) => m.Login) },
  { path: 'cadastro', title: 'Criar conta | Atelier', canActivate: [guestGuard], loadComponent: () => import('./features/auth/register').then((m) => m.Register) },
  { path: 'recuperar-senha', title: 'Recuperar senha | Atelier', loadComponent: () => import('./features/auth/forgot-password').then((m) => m.ForgotPassword) },
  { path: 'redefinir-senha', title: 'Nova senha | Atelier', loadComponent: () => import('./features/auth/reset-password').then((m) => m.ResetPassword) },
  { path: 'verificar-email', title: 'Confirmar e-mail | Atelier', loadComponent: () => import('./features/auth/verify-email').then((m) => m.VerifyEmail) },

  { path: 'conta', canActivate: [authGuard], loadChildren: () => import('./features/account/account.routes').then((m) => m.ACCOUNT_ROUTES) },

  // Painel administrativo (Fase 9). Só a porta de entrada e o controle de acesso por papel.
  { path: 'admin', title: 'Painel | Atelier', canActivate: [roleGuard(['ADMIN', 'OPERATOR'])], loadComponent: () => import('./features/admin/admin-home').then((m) => m.AdminHome) },
];
