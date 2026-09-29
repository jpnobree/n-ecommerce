package com.atelier.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Único cálculo de valores do carrinho e do checkout (PRD RN-04, RN-07, RN-40, RN-41, RN-62).
 * Função pura: centavos entram, centavos saem. Quem decide elegibilidade e validade do cupom é o chamador.
 */
public final class Pricing {

    private Pricing() {}

    /** unitPrice = preço efetivo agora; eligible = conta para o cupom (categoria/produto/promoção). */
    public record Line(long key, long unitPrice, int quantity, boolean eligible) {
        long total() {
            return unitPrice * quantity;
        }
    }

    public enum DiscountType { PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING }

    public record Discount(DiscountType type, long value, Long maxDiscount, Long maxShippingDiscount, Long minOrderAmount) {}

    /** Frete escolhido: preço e se a opção participa do frete grátis por valor mínimo. */
    public record Shipping(long price, boolean freeAboveThreshold) {}

    public enum Rejection { NO_ELIGIBLE_ITEMS, MIN_AMOUNT_NOT_REACHED }

    /**
     * allocation = desconto rateado por linha (para reembolso parcial e nota fiscal por item).
     * rejection != null: o cupom não se aplica a este carrinho (missingForMinimum diz quanto falta).
     */
    public record Totals(long subtotal, long discount, Long shipping, long shippingDiscount, long total,
                         Map<Long, Long> allocation, Rejection rejection, long missingForMinimum) {}

    public static Totals calculate(List<Line> lines, Discount discount, Shipping shipping, long freeShippingThreshold) {
        long subtotal = lines.stream().mapToLong(Line::total).sum();
        long eligibleTotal = lines.stream().filter(Line::eligible).mapToLong(Line::total).sum();

        long itemsDiscount = 0;
        boolean couponFreeShipping = false;
        Rejection rejection = null;
        long missing = 0;
        if (discount != null) {
            if (eligibleTotal == 0) {
                rejection = Rejection.NO_ELIGIBLE_ITEMS;
            } else if (discount.minOrderAmount() != null && eligibleTotal < discount.minOrderAmount()) {
                rejection = Rejection.MIN_AMOUNT_NOT_REACHED;
                missing = discount.minOrderAmount() - eligibleTotal;
            } else {
                itemsDiscount = switch (discount.type()) {
                    case PERCENTAGE -> BigDecimal.valueOf(eligibleTotal * discount.value())
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_EVEN).longValue();
                    case FIXED_AMOUNT -> discount.value();
                    case FREE_SHIPPING -> 0;
                };
                if (discount.maxDiscount() != null) itemsDiscount = Math.min(itemsDiscount, discount.maxDiscount());
                itemsDiscount = Math.min(itemsDiscount, eligibleTotal);
                couponFreeShipping = discount.type() == DiscountType.FREE_SHIPPING;
            }
        }

        long afterDiscount = subtotal - itemsDiscount;
        Long shippingPrice = shipping == null ? null : shipping.price();
        long shippingDiscount = 0;
        if (shipping != null) {
            if (couponFreeShipping) {
                long cap = discount.maxShippingDiscount() != null ? discount.maxShippingDiscount() : shipping.price();
                shippingDiscount = Math.min(shipping.price(), cap);
            }
            if (shipping.freeAboveThreshold() && afterDiscount >= freeShippingThreshold) {
                shippingDiscount = shipping.price();
            }
        }

        long total = afterDiscount + (shippingPrice == null ? 0 : shippingPrice) - shippingDiscount;
        return new Totals(subtotal, itemsDiscount, shippingPrice, shippingDiscount, total,
                allocate(lines, itemsDiscount, eligibleTotal), rejection, missing);
    }

    /** Rateio proporcional ao valor de cada linha elegível; os centavos que sobram vão para a maior linha. */
    private static Map<Long, Long> allocate(List<Line> lines, long discount, long eligibleTotal) {
        Map<Long, Long> result = new HashMap<>();
        if (discount == 0) return result;
        long given = 0;
        Line largest = null;
        for (Line line : lines) {
            if (!line.eligible()) continue;
            long share = discount * line.total() / eligibleTotal;
            result.put(line.key(), share);
            given += share;
            if (largest == null || line.total() > largest.total()) largest = line;
        }
        result.merge(largest.key(), discount - given, Long::sum);
        return result;
    }
}
