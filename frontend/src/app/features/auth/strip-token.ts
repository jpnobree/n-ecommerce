/** Remove ?token= da barra de endereço (histórico, Referer, prints) depois de lido. */
export function stripTokenFromUrl(): void {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  if (!url.searchParams.has('token')) return;
  url.searchParams.delete('token');
  window.history.replaceState(window.history.state, '', url.pathname + url.search);
}
