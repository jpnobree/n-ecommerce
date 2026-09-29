import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/core';
import { SeoService } from './seo.service';

describe('SeoService', () => {
  it('escapes JSON-LD so a product name cannot close the script tag (XSS)', () => {
    const seo = TestBed.inject(SeoService);
    const doc = TestBed.inject(DOCUMENT);

    seo.set({ title: 'X', jsonLd: [{ '@type': 'Product', name: '</script><script>alert(1)</script>' }] });

    const script = doc.head.querySelector('script[type="application/ld+json"]')!;
    expect(script.textContent).not.toContain('</script>');
    expect(JSON.parse(script.textContent!).name).toBe('</script><script>alert(1)</script>');
  });

  it('sets canonical, robots and replaces previous structured data', () => {
    const seo = TestBed.inject(SeoService);
    const doc = TestBed.inject(DOCUMENT);

    seo.set({ title: 'A', path: '/c/feminino', jsonLd: [{ '@type': 'A' }, { '@type': 'B' }] });
    seo.set({ title: 'B', path: '/c/masculino', noindex: true, jsonLd: [{ '@type': 'C' }] });

    expect(doc.head.querySelector('link[rel="canonical"]')!.getAttribute('href')).toMatch(/\/c\/masculino$/);
    expect(doc.head.querySelector('meta[name="robots"]')!.getAttribute('content')).toBe('noindex, follow');
    expect(doc.head.querySelectorAll('script[data-seo]').length).toBe(1);
  });
});
