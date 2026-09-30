import { RenderMode, ServerRoute } from '@angular/ssr';

export const serverRoutes: ServerRoute[] = [
  // Páginas privadas ou de sessão: só no navegador (o servidor SSR não conhece a sessão e elas não são indexadas).
  { path: 'entrar', renderMode: RenderMode.Client },
  { path: 'cadastro', renderMode: RenderMode.Client },
  { path: 'recuperar-senha', renderMode: RenderMode.Client },
  { path: 'redefinir-senha', renderMode: RenderMode.Client },
  { path: 'verificar-email', renderMode: RenderMode.Client },
  { path: 'conta/**', renderMode: RenderMode.Client },
  { path: 'sacola', renderMode: RenderMode.Client },
  { path: 'checkout/**', renderMode: RenderMode.Client },
  { path: 'admin/**', renderMode: RenderMode.Client },
  // Catálogo e produtos mudam o tempo todo: renderização por requisição.
  { path: '**', renderMode: RenderMode.Server },
];
