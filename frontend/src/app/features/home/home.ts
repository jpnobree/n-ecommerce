import { Component, afterNextRender, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';

interface ApiStatus {
  status: string;
  version: string;
}

@Component({
  selector: 'app-home',
  template: `
    <section class="hero">
      <h1>Nova coleção em breve</h1>
      <p>Moda feminina, masculina e acessórios.</p>
    </section>
    <p class="api-status" role="status">
      API: {{ apiStatus() }}
    </p>
  `,
})
export class Home {
  private readonly http = inject(HttpClient);
  protected readonly apiStatus = signal('verificando…');

  constructor() {
    // Só no navegador: no SSR não há origem para resolver a URL relativa /api.
    afterNextRender(() => {
      this.http.get<ApiStatus>('/api/status').subscribe({
        next: (s) => this.apiStatus.set(`${s.status} (${s.version})`),
        error: () => this.apiStatus.set('indisponível'),
      });
    });
  }
}
