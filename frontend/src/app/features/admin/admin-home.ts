import { Component, inject } from '@angular/core';
import { AuthStore } from '../../core/auth/auth.store';

@Component({
  selector: 'app-admin-home',
  template: `
    <h1>Painel administrativo</h1>
    <p>Olá, {{ auth.user()?.name }}. Os módulos do painel chegam na Fase 9.</p>
  `,
})
export class AdminHome {
  protected readonly auth = inject(AuthStore);
}
