package com.atelier.cart;

import com.atelier.cart.Pricing.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.atelier.cart.Pricing.DiscountType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Critério de saída da Fase 5: totais do carrinho validados. Valores em centavos. */
class PricingTest {

    private static final long FREE_ABOVE = 29_900;
    private static final Shipping PAC = new Shipping(2_000, true);
    private static final Shipping SEDEX = new Shipping(3_500, false);

    private static Line line(long key, long price, int qty) {
        return new Line(key, price, qty, true);
    }

    @Test
    void subtotalAndTotalWithoutDiscountOrShipping() {
        var t = Pricing.calculate(List.of(line(1, 9_990, 2), line(2, 4_990, 1)), null, null, FREE_ABOVE);
        assertThat(t.subtotal()).isEqualTo(24_970);
        assertThat(t.discount()).isZero();
        assertThat(t.shipping()).isNull();
        assertThat(t.total()).isEqualTo(24_970);
    }

    @Test
    void percentageRoundsHalfEvenAndRespectsCap() {
        // 10% de 12.345 = 1.234,5 -> 1.234 (half-even)
        var t = Pricing.calculate(List.of(line(1, 12_345, 1)), new Discount(PERCENTAGE, 10, null, null, null), null, FREE_ABOVE);
        assertThat(t.discount()).isEqualTo(1_234);
        // 10% de 12.355 = 1.235,5 -> 1.236 (half-even arredonda para o par)
        assertThat(Pricing.calculate(List.of(line(1, 12_355, 1)), new Discount(PERCENTAGE, 10, null, null, null), null, FREE_ABOVE)
                .discount()).isEqualTo(1_236);
        // 50% de 100.000 limitado a R$ 100
        var capped = Pricing.calculate(List.of(line(1, 100_000, 1)), new Discount(PERCENTAGE, 50, 10_000L, null, null), null, FREE_ABOVE);
        assertThat(capped.discount()).isEqualTo(10_000);
        assertThat(capped.total()).isEqualTo(90_000);
    }

    @Test
    void fixedDiscountNeverExceedsEligibleItems() {
        var t = Pricing.calculate(List.of(line(1, 3_000, 1)), new Discount(FIXED_AMOUNT, 5_000, null, null, null), null, FREE_ABOVE);
        assertThat(t.discount()).isEqualTo(3_000);
        assertThat(t.total()).isZero();
    }

    @Test
    void couponAppliesOnlyToEligibleLinesAndMinimumIsMeasuredOnThem() {
        var lines = List.of(new Line(1, 10_000, 1, true), new Line(2, 50_000, 1, false));
        var withMin = new Discount(PERCENTAGE, 20, null, null, 15_000L);
        var rejected = Pricing.calculate(lines, withMin, null, FREE_ABOVE);
        assertThat(rejected.rejection()).isEqualTo(Rejection.MIN_AMOUNT_NOT_REACHED);
        assertThat(rejected.missingForMinimum()).isEqualTo(5_000);
        assertThat(rejected.discount()).isZero();

        var ok = Pricing.calculate(lines, new Discount(PERCENTAGE, 20, null, null, null), null, FREE_ABOVE);
        assertThat(ok.discount()).isEqualTo(2_000);
        assertThat(ok.allocation()).containsOnlyKeys(1L);

        var none = Pricing.calculate(List.of(new Line(1, 10_000, 1, false)), withMin, null, FREE_ABOVE);
        assertThat(none.rejection()).isEqualTo(Rejection.NO_ELIGIBLE_ITEMS);
    }

    @Test
    void allocationSumsExactlyToTheDiscount() {
        var lines = List.of(line(1, 3_333, 1), line(2, 3_333, 1), line(3, 3_334, 1));
        var t = Pricing.calculate(lines, new Discount(FIXED_AMOUNT, 1_000, null, null, null), null, FREE_ABOVE);
        assertThat(t.allocation().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1_000);
        assertThat(t.allocation().get(3L)).isEqualTo(334); // sobra de centavos vai para a maior linha
    }

    @Test
    void freeShippingAboveThresholdUsesSubtotalAfterDiscountAndOnlyEligibleOption() {
        var lines = List.of(line(1, 30_000, 1));
        assertThat(Pricing.calculate(lines, null, PAC, FREE_ABOVE).shippingDiscount()).isEqualTo(2_000);
        assertThat(Pricing.calculate(lines, null, PAC, FREE_ABOVE).total()).isEqualTo(30_000);
        // Opção expressa não participa do frete grátis
        assertThat(Pricing.calculate(lines, null, SEDEX, FREE_ABOVE).total()).isEqualTo(33_500);
        // Com 10% de desconto o subtotal cai para 27.000: abaixo do mínimo, frete cobrado
        var discounted = Pricing.calculate(lines, new Discount(PERCENTAGE, 10, null, null, null), PAC, FREE_ABOVE);
        assertThat(discounted.shippingDiscount()).isZero();
        assertThat(discounted.total()).isEqualTo(27_000 + 2_000);
    }

    @Test
    void freeShippingCouponRespectsItsCap() {
        var lines = List.of(line(1, 10_000, 1));
        var full = Pricing.calculate(lines, new Discount(FREE_SHIPPING, 0, null, null, null), SEDEX, FREE_ABOVE);
        assertThat(full.shippingDiscount()).isEqualTo(3_500);
        assertThat(full.total()).isEqualTo(10_000);
        var capped = Pricing.calculate(lines, new Discount(FREE_SHIPPING, 0, null, 2_000L, null), SEDEX, FREE_ABOVE);
        assertThat(capped.total()).isEqualTo(11_500);
    }

    @Test
    void emptyCartIsZero() {
        var t = Pricing.calculate(List.of(), new Discount(PERCENTAGE, 10, null, null, null), null, FREE_ABOVE);
        assertThat(t.total()).isZero();
        assertThat(t.rejection()).isEqualTo(Rejection.NO_ELIGIBLE_ITEMS);
    }
}
