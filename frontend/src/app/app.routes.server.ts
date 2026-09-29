import { RenderMode, ServerRoute } from '@angular/ssr';

// Catálogo e produtos mudam o tempo todo: renderização por requisição, não prerender.
export const serverRoutes: ServerRoute[] = [{ path: '**', renderMode: RenderMode.Server }];
