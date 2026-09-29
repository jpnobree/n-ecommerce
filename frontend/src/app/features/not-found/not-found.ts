import { Component, RESPONSE_INIT, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Página 404. No SSR devolve status 404 de verdade (não indexar "soft 404"). */
@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <section class="not-found">
      <h1>Página não encontrada</h1>
      <p>O endereço pode ter mudado ou o produto não está mais disponível.</p>
      <a routerLink="/" class="btn">Ir para a loja</a>
    </section>
  `,
})
export class NotFound {
  constructor() {
    const response = inject(RESPONSE_INIT, { optional: true });
    if (response) response.status = 404;
  }
}
