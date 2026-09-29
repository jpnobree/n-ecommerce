package com.atelier.cart;

import java.util.List;

/**
 * Frete por tabela: região (1º dígito do CEP) × peso. É o "fallback de tabela" do PRD (seção 28, pontos 1 e 2).
 * ponytail: valores de referência fixos no código; trocar por cotação do agregador (Melhor Envio/Frenet)
 * quando a transportadora for decidida, mantendo esta tabela como fallback se a API cair.
 */
final class ShippingTable {

    private ShippingTable() {}

    /** freeAboveThreshold: participa do frete grátis por valor mínimo (só a opção econômica). */
    record Option(String id, String carrier, String service, long price, int days, boolean freeAboveThreshold) {}

    // Por região: [PAC base, SEDEX base, PAC por kg extra, SEDEX por kg extra, prazo PAC, prazo SEDEX]
    private static final long[][] REGIONS = {
            {1_890, 2_990, 500, 900, 4, 1},   // 0 Grande SP
            {2_190, 3_490, 550, 1_000, 5, 2}, // 1 Interior SP
            {2_490, 3_990, 600, 1_100, 6, 2}, // 2 RJ/ES
            {2_490, 3_990, 600, 1_100, 6, 2}, // 3 MG
            {3_290, 5_490, 800, 1_500, 8, 3}, // 4 BA/SE
            {3_490, 5_990, 850, 1_600, 9, 3}, // 5 PE/AL/PB/RN
            {3_990, 6_990, 950, 1_900, 11, 4}, // 6 CE/PI/MA/PA/AP/AM/RR/AC
            {3_290, 5_490, 800, 1_500, 8, 3}, // 7 DF/GO/TO/MT/MS/RO
            {2_690, 4_490, 650, 1_200, 6, 2}, // 8 PR/SC
            {2_890, 4_790, 700, 1_300, 7, 3}, // 9 RS
    };

    static List<Option> quote(String postalCode, int weightGrams) {
        long[] r = REGIONS[postalCode.charAt(0) - '0'];
        long extraKg = Math.max(0, (weightGrams - 1) / 1000); // primeiro kg incluso
        return List.of(
                new Option("pac", "Correios", "PAC", r[0] + extraKg * r[2], (int) r[4], true),
                new Option("sedex", "Correios", "SEDEX", r[1] + extraKg * r[3], (int) r[5], false));
    }
}
