import {
  AngularNodeAppEngine,
  createNodeRequestHandler,
  isMainModule,
  writeResponseToNodeResponse,
} from '@angular/ssr/node';
import express from 'express';
import { join } from 'node:path';

const browserDistFolder = join(import.meta.dirname, '../browser');

const app = express();
const angularApp = new AngularNodeAppEngine();

/** API vista de dentro do servidor (rede interna). SITE_URL = domínio público usado nas URLs do sitemap. */
const apiUrl = process.env['API_URL'] ?? 'http://localhost:8080';
const siteUrl = (req: express.Request) => process.env['SITE_URL'] ?? `${req.protocol}://${req.get('host')}`;

/**
 * Sem load balancer na frente (execução local do build de produção), o SSR precisa alcançar /api.
 * Só GET: é o que as páginas renderizadas no servidor consomem; em produção o LB roteia /api direto à API.
 */
if (process.env['API_PROXY'] === 'true') {
  app.get('/api/{*path}', async (req, res, next) => {
    try {
      const upstream = await fetch(apiUrl + req.originalUrl, { headers: { accept: 'application/json' } });
      res.status(upstream.status).type(upstream.headers.get('content-type') ?? 'application/json');
      const cache = upstream.headers.get('cache-control');
      if (cache) res.set('Cache-Control', cache);
      res.send(Buffer.from(await upstream.arrayBuffer()));
    } catch (e) {
      next(e);
    }
  });
}

/** Homologação: SITE_INDEXING=false bloqueia tudo (PRD 21.4). */
app.get('/robots.txt', (req, res) => {
  const body =
    process.env['SITE_INDEXING'] === 'false'
      ? 'User-agent: *\nDisallow: /\n'
      : ['User-agent: *', 'Disallow: /conta', 'Disallow: /checkout', 'Disallow: /sacola', 'Disallow: /busca',
          'Disallow: /entrar', 'Disallow: /cadastro', 'Disallow: /admin', 'Disallow: /api/',
          `Sitemap: ${siteUrl(req)}/sitemap.xml`, ''].join('\n');
  res.type('text/plain').set('Cache-Control', 'public, max-age=3600').send(body);
});

// ponytail: um arquivo só (limite de 50 mil URLs); índice com vários arquivos quando o catálogo passar disso.
app.get('/sitemap.xml', async (req, res, next) => {
  try {
    const entries: { path: string; lastModified: string }[] = await (await fetch(`${apiUrl}/api/seo/sitemap`)).json();
    const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    const urls = [{ path: '/', lastModified: new Date().toISOString() }, ...entries]
      .map((e) => `<url><loc>${esc(siteUrl(req) + e.path)}</loc><lastmod>${e.lastModified.slice(0, 10)}</lastmod></url>`)
      .join('');
    res
      .type('application/xml')
      .set('Cache-Control', 'public, max-age=3600')
      .send(`<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${urls}</urlset>`);
  } catch (e) {
    next(e);
  }
});

/**
 * Serve static files from /browser
 */
app.use(
  express.static(browserDistFolder, {
    maxAge: '1y',
    index: false,
    redirect: false,
  }),
);

/**
 * Handle all other requests by rendering the Angular application.
 */
app.use((req, res, next) => {
  angularApp
    .handle(req)
    .then((response) =>
      response ? writeResponseToNodeResponse(response, res) : next(),
    )
    .catch(next);
});

/**
 * Start the server if this module is the main entry point, or it is ran via PM2.
 * The server listens on the port defined by the `PORT` environment variable, or defaults to 4000.
 */
if (isMainModule(import.meta.url) || process.env['pm_id']) {
  const port = process.env['PORT'] || 4000;
  app.listen(port, (error) => {
    if (error) {
      throw error;
    }

    console.log(`Node Express server listening on http://localhost:${port}`);
  });
}

/**
 * Request handler used by the Angular CLI (for dev-server and during build) or Firebase Cloud Functions.
 */
export const reqHandler = createNodeRequestHandler(app);
