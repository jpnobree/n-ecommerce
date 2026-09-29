import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

export interface SeoData {
  title: string;
  description?: string | null;
  /** Caminho canônico ("/c/feminino"); sem ele, a página atual sem query string. */
  path?: string;
  image?: string | null;
  type?: 'website' | 'product';
  noindex?: boolean;
  /** Dados estruturados (schema.org): Product, BreadcrumbList, Organization... */
  jsonLd?: object[];
}

const SITE = 'Atelier';

/**
 * Metadados da página (PRD, seção 21): title, description, canonical absoluto, Open Graph, robots e JSON-LD.
 * Roda no SSR, então buscadores e redes sociais recebem tudo no HTML.
 */
@Injectable({ providedIn: 'root' })
export class SeoService {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly document = inject(DOCUMENT);

  set(data: SeoData): void {
    const fullTitle = data.title === SITE ? SITE : `${data.title} | ${SITE}`;
    const url = this.absolute(data.path ?? this.document.location.pathname);
    const description = data.description?.slice(0, 160) ?? null;

    this.title.setTitle(fullTitle);
    this.tag('name', 'description', description);
    this.tag('name', 'robots', data.noindex ? 'noindex, follow' : 'index, follow');
    this.tag('property', 'og:title', fullTitle);
    this.tag('property', 'og:description', description);
    this.tag('property', 'og:type', data.type ?? 'website');
    this.tag('property', 'og:url', url);
    this.tag('property', 'og:site_name', SITE);
    this.tag('property', 'og:image', data.image ? this.absolute(data.image) : null);
    this.tag('name', 'twitter:card', data.image ? 'summary_large_image' : 'summary');

    this.link('canonical', url);
    this.jsonLd(data.jsonLd ?? []);
  }

  absolute(pathOrUrl: string): string {
    return /^https?:\/\//.test(pathOrUrl) ? pathOrUrl : this.document.location.origin + pathOrUrl;
  }

  private tag(attr: 'name' | 'property', key: string, content: string | null): void {
    const selector = `${attr}="${key}"`;
    if (content) this.meta.updateTag({ [attr]: key, content }, selector);
    else this.meta.removeTag(selector);
  }

  private link(rel: string, href: string): void {
    let el = this.document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
    if (!el) {
      el = this.document.createElement('link');
      el.setAttribute('rel', rel);
      this.document.head.appendChild(el);
    }
    el.setAttribute('href', href);
  }

  /** "<" escapado: um nome de produto com "</script>" não consegue sair do bloco JSON (XSS). */
  private jsonLd(items: object[]): void {
    this.document.head.querySelectorAll('script[data-seo]').forEach((s) => s.remove());
    for (const item of items) {
      const script = this.document.createElement('script');
      script.setAttribute('type', 'application/ld+json');
      script.setAttribute('data-seo', '');
      script.textContent = JSON.stringify({ '@context': 'https://schema.org', ...item }).replace(/</g, '\\u003c');
      this.document.head.appendChild(script);
    }
  }
}

/** BreadcrumbList a partir de migalhas [{name, path}]. */
export function breadcrumbLd(seo: SeoService, crumbs: { name: string; path: string }[]): object {
  return {
    '@type': 'BreadcrumbList',
    itemListElement: crumbs.map((c, i) => ({ '@type': 'ListItem', position: i + 1, name: c.name, item: seo.absolute(c.path) })),
  };
}
