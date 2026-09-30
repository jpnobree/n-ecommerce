import { Routes } from '@angular/router';
import { AccountShell } from './account-shell';

export const ACCOUNT_ROUTES: Routes = [
  {
    path: '',
    component: AccountShell,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dados' },
      { path: 'dados', title: 'Meus dados | Atelier', loadComponent: () => import('./profile').then((m) => m.Profile) },
      { path: 'pedidos', title: 'Pedidos | Atelier', loadComponent: () => import('./orders').then((m) => m.Orders) },
      { path: 'pedidos/:number', title: 'Pedido | Atelier', loadComponent: () => import('../checkout/order-confirmation').then((m) => m.OrderConfirmation) },
      { path: 'enderecos', title: 'Endereços | Atelier', loadComponent: () => import('./addresses').then((m) => m.Addresses) },
      { path: 'favoritos', title: 'Favoritos | Atelier', loadComponent: () => import('./favorites').then((m) => m.Favorites) },
      { path: 'senha', title: 'Senha | Atelier', loadComponent: () => import('./change-password').then((m) => m.ChangePassword) },
    ],
  },
];
