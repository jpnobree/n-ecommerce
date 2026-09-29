import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, of } from 'rxjs';
import { AuthStore } from './core/auth/auth.store';
import { CategoryNode } from './core/catalog/catalog.models';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(AuthStore);
  protected readonly year = new Date().getFullYear();

  /** Menu principal: renderizado no SSR (links rastreáveis) e reaproveitado na hidratação. */
  protected readonly categories = toSignal(
    inject(HttpClient).get<CategoryNode[]>('/api/categories').pipe(catchError(() => of([] as CategoryNode[]))),
    { initialValue: [] as CategoryNode[] },
  );

  protected firstName(name: string): string {
    return name.split(' ')[0];
  }
}
