/** Remove máscara: "(11) 98765-4321" -> "11987654321". A API recebe só dígitos. */
export const digits = (value: string | null | undefined): string => (value ?? '').replace(/\D/g, '');
