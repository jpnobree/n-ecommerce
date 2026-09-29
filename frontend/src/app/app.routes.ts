import { Routes, UrlSegment } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

const catalog = () => import('./features/catalog/catalog-page').then((m) => m.CatalogPage);

/** /c/feminino, /c/feminino/vestidos... (até 3 níveis de categoria). */
function categoryMatcher(segments: UrlSegment[]) {
  return segments[0]?.path === 'c' && segments.length >= 2 && segments.length <= 4 ? { consumed: segments } : null;
}

export const routes: Routes = [
  { path: '', loadComponent: () => import('./features/home/home').then((m) => m.Home) },

  { matcher: categoryMatcher, data: { mode: 'category' }, loadComponent: catalog },
  { path: 'colecao/:slug', data: { mode: 'collection' }, loadComponent: catalog },
  { path: 'novidades', data: { mode: 'new' }, loadComponent: catalog },
  { path: 'promocoes', data: { mode: 'sale' }, loadComponent: catalog },
  { path: 'busca', data: { mode: 'search' }, loadComponent: catalog },
  { path: 'p/:slug', loadComponent: () => import('./features/product/product-page').then((m) => m.ProductPage) },

  { path: 'entrar', title: 'Entrar | Atelier', canActivate: [guestGuard], loadComponent: () => import('./features/auth/login').then((m) => m.Login) },
  { path: 'cadastro', title: 'Criar conta | Atelier', canActivate: [guestGuard], loadComponent: () => import('./features/auth/register').then((m) => m.Register) },
  { path: 'recuperar-senha', title: 'Recuperar senha | Atelier', loadComponent: () => import('./features/auth/forgot-password').then((m) => m.ForgotPassword) },
  { path: 'redefinir-senha', title: 'Nova senha | Atelier', loadComponent: () => import('./features/auth/reset-password').then((m) => m.ResetPassword) },
  { path: 'verificar-email', title: 'Confirmar e-mail | Atelier', loadComponent: () => import('./features/auth/verify-email').then((m) => m.VerifyEmail) },

  { path: 'conta', canActivate: [authGuard], loadChildren: () => import('./features/account/account.routes').then((m) => m.ACCOUNT_ROUTES) },

  // Painel administrativo (Fase 9). Só a porta de entrada e o controle de acesso por papel.
  { path: 'admin', title: 'Painel | Atelier', canActivate: [roleGuard(['ADMIN', 'OPERATOR'])], loadComponent: () => import('./features/admin/admin-home').then((m) => m.AdminHome) },

  { path: '**', title: 'Página não encontrada | Atelier', loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound) },
];
