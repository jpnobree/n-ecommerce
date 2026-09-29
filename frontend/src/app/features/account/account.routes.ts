import { Routes } from '@angular/router';
import { AccountShell } from './account-shell';

export const ACCOUNT_ROUTES: Routes = [
  {
    path: '',
    component: AccountShell,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dados' },
      { path: 'dados', title: 'Meus dados | Atelier', loadComponent: () => import('./profile').then((m) => m.Profile) },
      { path: 'enderecos', title: 'Endereços | Atelier', loadComponent: () => import('./addresses').then((m) => m.Addresses) },
      { path: 'favoritos', title: 'Favoritos | Atelier', loadComponent: () => import('./favorites').then((m) => m.Favorites) },
      { path: 'senha', title: 'Senha | Atelier', loadComponent: () => import('./change-password').then((m) => m.ChangePassword) },
    ],
  },
];
