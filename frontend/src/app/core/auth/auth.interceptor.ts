import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthStore } from './auth.store';

/** Rotas que não devem disparar refresh automático ao receber 401. */
const NO_REFRESH = /^\/api\/auth\/(login|register|refresh)$/;

/**
 * Catálogo público: sem token. Com Authorization a resposta não é reaproveitada do SSR (transfer cache)
 * nem pode ser guardada por CDN/caches compartilhados.
 */
const PUBLIC_GET = /^\/api\/(products|categories|collections)(\/|\?|$)/;

/**
 * Só para a nossa API: anexa o Bearer e o X-Requested-With (exigido pelas rotas que usam o cookie).
 * Em 401, tenta um refresh (compartilhado) e repete a requisição uma única vez.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') || (req.method === 'GET' && PUBLIC_GET.test(req.url))) return next(req);

  const auth = inject(AuthStore);
  const withAuth = (r: HttpRequest<unknown>) => {
    let headers = r.headers.set('X-Requested-With', 'XMLHttpRequest');
    const token = auth.accessToken();
    if (token) headers = headers.set('Authorization', `Bearer ${token}`);
    return r.clone({ headers });
  };

  return next(withAuth(req)).pipe(
    catchError((err: unknown) => {
      const expired = err instanceof HttpErrorResponse && err.status === 401 && auth.accessToken() !== null;
      if (!expired || NO_REFRESH.test(req.url)) return throwError(() => err);

      return from(auth.refresh()).pipe(
        switchMap((ok) => {
          if (!ok) {
            auth.sessionExpired();
            return throwError(() => err);
          }
          return next(withAuth(req));
        }),
      );
    }),
  );
};
