import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { AuthStore, safeReturnUrl } from './auth.store';

const session = (token: string) => ({
  accessToken: token,
  expiresIn: 900,
  user: { id: 1, email: 'a@b.c', name: 'Ana', phone: null, cpf: null, birthDate: null, emailVerified: true, marketingOptIn: false, roles: ['CUSTOMER'] },
});

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'entrar', children: [] }]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthStore);
    auth.setSession(session('velho') as never);
  });

  afterEach(() => backend.verify());

  it('sends bearer and X-Requested-With only to our API', () => {
    http.get('/api/me').subscribe();
    http.get('https://viacep.com.br/ws/01310100/json/').subscribe();

    const api = backend.expectOne('/api/me');
    expect(api.request.headers.get('Authorization')).toBe('Bearer velho');
    expect(api.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    const external = backend.expectOne((r) => r.url.startsWith('https://viacep'));
    expect(external.request.headers.has('Authorization')).toBe(false);
    api.flush({});
    external.flush({});
  });

  it('concurrent 401s trigger a single refresh and retry with the new token', async () => {
    const a = firstValueFrom(http.get('/api/me'));
    const b = firstValueFrom(http.get('/api/me/addresses'));

    backend.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/me/addresses').flush(null, { status: 401, statusText: 'Unauthorized' });

    const refreshes = backend.match('/api/auth/refresh');
    expect(refreshes.length).toBe(1);
    refreshes[0].flush(session('novo'));
    await Promise.resolve();
    await new Promise((r) => setTimeout(r));

    const retryA = backend.expectOne('/api/me');
    const retryB = backend.expectOne('/api/me/addresses');
    expect(retryA.request.headers.get('Authorization')).toBe('Bearer novo');
    retryA.flush({ ok: 'a' });
    retryB.flush({ ok: 'b' });
    expect(await a).toEqual({ ok: 'a' });
    expect(await b).toEqual({ ok: 'b' });
  });

  it('failed refresh clears the session and propagates the 401', async () => {
    const req = firstValueFrom(http.get('/api/me'));
    backend.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/auth/refresh').flush({ code: 'REFRESH_INVALID' }, { status: 401, statusText: 'Unauthorized' });

    await expect(req).rejects.toMatchObject({ status: 401 });
    expect(auth.isAuthenticated()).toBe(false);
  });
});

describe('safeReturnUrl', () => {
  it('only accepts internal paths', () => {
    expect(safeReturnUrl('/conta/enderecos')).toBe('/conta/enderecos');
    expect(safeReturnUrl('https://evil.com')).toBe('/conta');
    expect(safeReturnUrl('//evil.com')).toBe('/conta');
    expect(safeReturnUrl('/\\evil.com')).toBe('/conta');
    expect(safeReturnUrl(undefined)).toBe('/conta');
  });
});
