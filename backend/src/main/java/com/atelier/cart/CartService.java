package com.atelier.cart;

import com.atelier.catalog.domain.ProductVariant;
import com.atelier.identity.service.Tokens;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Carrinho no servidor para convidado (token) e cliente logado (PRD, seção 7).
 * Nunca reserva estoque (isso é do checkout); a cada leitura recalcula preço, estoque e cupom e avisa o que mudou.
 */
@Service
public class CartService {

    static final int MAX_PER_ITEM = 10;

    /**
     * Disponível para ESTE carrinho: livre + o que o próprio pedido pendente dele já reservou (senão, depois de
     * finalizar, a sacola acusaria falta do que está guardado para ela mesma). Parâmetro :cart; aliases i (inventory), v (variante).
     */
    private static final String AVAILABLE = """
            (coalesce(i.on_hand - i.reserved, 0) + coalesce((SELECT sum(oi.quantity) FROM order_item oi JOIN orders o ON o.id = oi.order_id
              WHERE o.cart_id = :cart AND o.status = 'PENDING_PAYMENT' AND oi.variant_id = v.id), 0))""";

    // ---- respostas ----

    public record SizeChoice(Long variantId, String size, boolean available) {}

    /** status: OK | QUANTITY_REDUCED (há menos que o pedido) | OUT_OF_STOCK | UNAVAILABLE (saiu de linha). */
    public record ItemView(Long id, Long variantId, String productSlug, String productName, String sku, String color,
                           String size, String imageUrl, long unitPrice, long listPrice, int quantity, long lineTotal,
                           String status, int maxQuantity, List<SizeChoice> sizes) {}

    public record TotalsView(long subtotal, long discount, Long shipping, long shippingDiscount, long total) {}

    public record CouponView(String code, String description) {}

    public record ShippingView(String postalCode, String selected, List<ShippingTable.Option> options) {}

    public record Warning(String type, Long itemId, String message) {}

    /** cartToken: só na resposta que criou um carrinho de convidado (o navegador guarda e reenvia em X-Cart-Token). */
    public record CartView(String cartToken, List<ItemView> items, int count, TotalsView totals, CouponView coupon,
                           ShippingView shipping, List<Warning> warnings, boolean canCheckout) {}

    private final CartRepository carts;
    private final CartItemRepository items;
    private final CouponRepository coupons;
    private final CouponService couponRules;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final long freeShippingAbove;

    CartService(CartRepository carts, CartItemRepository items, CouponRepository coupons, CouponService couponRules,
                NamedParameterJdbcTemplate jdbc, Clock clock,
                @Value("${app.cart.free-shipping-above:29900}") long freeShippingAbove) {
        this.carts = carts;
        this.items = items;
        this.coupons = coupons;
        this.couponRules = couponRules;
        this.jdbc = jdbc;
        this.clock = clock;
        this.freeShippingAbove = freeShippingAbove;
    }

    // ---- identificação ----

    /** Carrinho do usuário logado, ou do convidado pelo token. Null se não existe (e create = false). */
    private Cart find(Long userId, String guestToken) {
        if (userId != null) return carts.findByUserId(userId).orElse(null);
        if (guestToken != null && !guestToken.isBlank()) return carts.findByTokenHash(Tokens.sha256(guestToken)).orElse(null);
        return null;
    }

    private record Resolved(Cart cart, String newToken) {}

    private Resolved findOrCreate(Long userId, String guestToken) {
        Cart cart = find(userId, guestToken);
        if (cart != null) return new Resolved(cart, null);
        cart = new Cart();
        cart.id = UUID.randomUUID();
        String token = null;
        if (userId != null) {
            cart.userId = userId;
        } else {
            token = UUID.randomUUID().toString(); // 122 bits aleatórios: segredo de posse do carrinho
            cart.tokenHash = Tokens.sha256(token);
        }
        return new Resolved(carts.saveAndFlush(cart), token);
    }

    // ---- operações ----

    @Transactional
    public CartView view(Long userId, String guestToken) {
        Cart cart = find(userId, guestToken);
        return cart == null ? empty() : render(cart, userId, null);
    }

    @Transactional
    public CartView add(Long userId, String guestToken, long variantId, int quantity) {
        Resolved r = findOrCreate(userId, guestToken);
        Variant v = variant(r.cart().id, variantId).filter(Variant::sellable).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Produto indisponível"));
        CartItem item = items.findByCartIdAndVariantId(r.cart().id, variantId).orElse(null);
        int total = quantity + (item == null ? 0 : item.quantity);
        checkQuantity(total, v.available());
        if (item == null) {
            item = new CartItem();
            item.cartId = r.cart().id;
            item.variantId = variantId;
        }
        item.quantity = total;
        item.priceSnapshot = v.effectivePrice(clock.instant());
        items.save(item);
        touch(r.cart());
        return render(r.cart(), userId, r.newToken());
    }

    /** Muda quantidade e/ou troca tamanho/cor (variante do mesmo produto; se já estiver na sacola, junta). */
    @Transactional
    public CartView update(Long userId, String guestToken, long itemId, Integer quantity, Long variantId) {
        Cart cart = requireCart(userId, guestToken);
        CartItem item = items.findByIdAndCartId(itemId, cart.id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        int qty = quantity != null ? quantity : item.quantity;
        if (variantId != null && variantId != item.variantId.longValue()) {
            Variant current = variant(cart.id, item.variantId).orElseThrow();
            Variant target = variant(cart.id, variantId).filter(Variant::sellable).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Produto indisponível"));
            if (target.productId() != current.productId()) throw new BusinessException(ErrorCode.VARIANT_OF_OTHER_PRODUCT);
            CartItem existing = items.findByCartIdAndVariantId(cart.id, variantId).orElse(null);
            if (existing != null) {
                qty += existing.quantity;
                items.delete(existing);
                items.flush();
            }
            checkQuantity(qty, target.available());
            item.variantId = variantId;
            item.priceSnapshot = target.effectivePrice(clock.instant());
        } else {
            checkQuantity(qty, variant(cart.id, item.variantId).map(Variant::available).orElse(0));
        }
        item.quantity = qty;
        items.save(item);
        touch(cart);
        return render(cart, userId, null);
    }

    @Transactional
    public CartView remove(Long userId, String guestToken, long itemId) {
        Cart cart = find(userId, guestToken);
        if (cart == null) return empty();
        items.findByIdAndCartId(itemId, cart.id).ifPresent(items::delete);
        touch(cart);
        return render(cart, userId, null);
    }

    /** Aplica com erro específico (422) se não vale; uma vez aplicado, deixar de valer vira aviso na leitura. */
    @Transactional
    public CartView applyCoupon(Long userId, String guestToken, String code) {
        Cart cart = requireCart(userId, guestToken);
        Coupon coupon = couponRules.byCode(code);
        ErrorCode rejection = couponRules.rejection(coupon, userId);
        if (rejection != null) throw new BusinessException(rejection);
        Pricing.Totals totals = Pricing.calculate(lines(rows(cart.id), coupon), coupon.toDiscount(), null, freeShippingAbove);
        if (totals.rejection() == Pricing.Rejection.NO_ELIGIBLE_ITEMS) throw new BusinessException(ErrorCode.COUPON_NO_ELIGIBLE_ITEMS);
        if (totals.rejection() == Pricing.Rejection.MIN_AMOUNT_NOT_REACHED) {
            throw new BusinessException(ErrorCode.COUPON_MIN_AMOUNT_NOT_REACHED,
                    "Faltam R$ " + String.format(Locale.of("pt", "BR"), "%,.2f", totals.missingForMinimum() / 100.0) + " em produtos participantes");
        }
        cart.couponId = coupon.id;
        touch(cart);
        return render(cart, userId, null);
    }

    @Transactional
    public CartView removeCoupon(Long userId, String guestToken) {
        Cart cart = find(userId, guestToken);
        if (cart == null) return empty();
        cart.couponId = null;
        touch(cart);
        return render(cart, userId, null);
    }

    /** Guarda o CEP e a opção; as opções são recalculadas a cada leitura (peso muda com os itens). */
    @Transactional
    public CartView setShipping(Long userId, String guestToken, String postalCode, String option) {
        Resolved r = findOrCreate(userId, guestToken);
        // Reenviar o mesmo CEP/opção (ex.: ao abrir o checkout) não muda a versão: o pedido pendente continua valendo
        if (!postalCode.equals(r.cart().shippingPostalCode) || !Objects.equals(option, r.cart().shippingOption)) {
            r.cart().shippingPostalCode = postalCode;
            r.cart().shippingOption = option;
            touch(r.cart());
        }
        return render(r.cart(), userId, r.newToken());
    }

    /** Depois do login: itens do convidado entram no carrinho da conta (limitados a 10 por item). */
    @Transactional
    public CartView merge(long userId, String guestToken) {
        Cart guest = find(null, guestToken);
        Resolved mine = findOrCreate(userId, null);
        if (guest != null && !guest.id.equals(mine.cart().id)) {
            for (CartItem g : items.findByCartIdOrderByCreatedAtAsc(guest.id)) {
                CartItem target = items.findByCartIdAndVariantId(mine.cart().id, g.variantId).orElse(null);
                if (target == null) {
                    target = new CartItem();
                    target.cartId = mine.cart().id;
                    target.variantId = g.variantId;
                    target.priceSnapshot = g.priceSnapshot;
                }
                target.quantity = Math.min(MAX_PER_ITEM, target.quantity + g.quantity);
                items.save(target);
            }
            if (mine.cart().couponId == null) mine.cart().couponId = guest.couponId;
            if (mine.cart().shippingPostalCode == null) {
                mine.cart().shippingPostalCode = guest.shippingPostalCode;
                mine.cart().shippingOption = guest.shippingOption;
            }
            carts.delete(guest);
            touch(mine.cart());
        }
        return render(mine.cart(), userId, null);
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeAbandonedGuestCarts() {
        carts.deleteGuestsInactiveSince(clock.instant().minus(Duration.ofDays(30)));
    }

    // ---- leitura ----

    // ---- checkout ----

    /** Linha como vai para o pedido: preço efetivo de agora e desconto rateado. */
    public record QuoteLine(long productId, long variantId, String sku, String productName, String productSlug, String color,
                            String size, String imageUrl, long unitPrice, long listPrice, int quantity, long discount,
                            int weightGrams) {}

    public record QuoteShipping(String postalCode, String option, String carrier, String service, int days) {}

    /** cartVersion: o pedido só é criado se o carrinho não mudou desde esta cotação. */
    public record CheckoutQuote(UUID cartId, int cartVersion, List<QuoteLine> lines, Pricing.Totals totals, Coupon coupon,
                                QuoteShipping shipping, List<Warning> warnings, boolean canCheckout) {}

    /**
     * Recalcula o carrinho da conta com o CEP do endereço de entrega (PRD 9.2, passos 3–7). Transação própria:
     * avisos que mudam o carrinho (preço, cupom removido) ficam gravados mesmo que o checkout seja recusado.
     */
    @Transactional
    public CheckoutQuote checkoutQuote(long userId, String postalCode, String option) {
        Cart cart = carts.lockByUserId(userId).orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY));
        // Só grava se mudou: não mexer na versão faz o duplo clique cair no mesmo pedido pendente
        if (!postalCode.equals(cart.shippingPostalCode) || !Objects.equals(option, cart.shippingOption)) {
            cart.shippingPostalCode = postalCode;
            cart.shippingOption = option;
        }
        Computed c = compute(cart, userId, null);
        carts.saveAndFlush(cart);
        List<QuoteLine> lines = c.rows.stream().filter(Row::countsInTotal).map(r -> new QuoteLine(r.productId, r.variantId,
                r.sku, r.productName, r.productSlug, r.color, r.size, r.imageUrl, r.effective, r.listPrice, r.quantity,
                c.totals.allocation().getOrDefault(r.itemId, 0L), r.weightGrams)).toList();
        ShippingTable.Option o = c.shipping;
        return new CheckoutQuote(cart.id, cart.version, lines, c.totals, c.coupon,
                new QuoteShipping(postalCode, o.id(), o.carrier(), o.service(), o.days()), c.view.warnings(), c.view.canCheckout());
    }

    // ---- leitura ----

    private record Computed(CartView view, List<Row> rows, Pricing.Totals totals, Coupon coupon, ShippingTable.Option shipping) {}

    private CartView render(Cart cart, Long userId, String newToken) {
        return compute(cart, userId, newToken).view;
    }

    private Computed compute(Cart cart, Long userId, String newToken) {
        List<Row> rows = rows(cart.id);
        List<Warning> warnings = new ArrayList<>();

        // Preço mudou desde que o cliente viu: avisa uma vez e atualiza a referência.
        for (Row row : rows) {
            if (row.sellable() && row.snapshot != row.effective) {
                warnings.add(new Warning("PRICE_CHANGED", row.itemId,
                        row.productName + ": o preço mudou de " + brl(row.snapshot) + " para " + brl(row.effective)));
                jdbc.update("UPDATE cart_item SET price_snapshot = :p WHERE id = :id",
                        new MapSqlParameterSource("p", row.effective).addValue("id", row.itemId));
            }
            String status = row.status();
            if (status.equals("OUT_OF_STOCK")) warnings.add(new Warning(status, row.itemId, row.productName + " esgotou no tamanho " + row.size));
            if (status.equals("UNAVAILABLE")) warnings.add(new Warning(status, row.itemId, row.productName + " não está mais disponível"));
            if (status.equals("QUANTITY_REDUCED")) {
                warnings.add(new Warning(status, row.itemId, "Só temos " + row.available + " unidade(s) de " + row.productName + " em " + row.size));
            }
        }

        Coupon coupon = cart.couponId == null ? null : coupons.findById(cart.couponId).orElse(null);
        List<Pricing.Line> lines = lines(rows, coupon);
        Pricing.Shipping shippingChoice = null;
        ShippingView shippingView = null;
        ShippingTable.Option selected = null;
        if (cart.shippingPostalCode != null) {
            int weight = rows.stream().filter(Row::countsInTotal).mapToInt(r -> r.weightGrams * r.quantity).sum();
            List<ShippingTable.Option> options = ShippingTable.quote(cart.shippingPostalCode, Math.max(weight, 1));
            selected = options.stream().filter(o -> o.id().equals(cart.shippingOption)).findFirst().orElse(options.getFirst());
            shippingChoice = lines.isEmpty() ? null : new Pricing.Shipping(selected.price(), selected.freeAboveThreshold());
            shippingView = new ShippingView(cart.shippingPostalCode, selected.id(), options);
        }

        Pricing.Totals totals = Pricing.calculate(lines, coupon == null ? null : coupon.toDiscount(), shippingChoice, freeShippingAbove);
        if (coupon != null) {
            ErrorCode rejection = couponRules.rejection(coupon, userId);
            if (rejection == null && totals.rejection() != null) {
                rejection = totals.rejection() == Pricing.Rejection.NO_ELIGIBLE_ITEMS
                        ? ErrorCode.COUPON_NO_ELIGIBLE_ITEMS : ErrorCode.COUPON_MIN_AMOUNT_NOT_REACHED;
            }
            if (rejection != null) {
                warnings.add(new Warning("COUPON_REMOVED", null, "Cupom " + coupon.code + " removido: " + rejection.title));
                cart.couponId = null;
                coupon = null;
                totals = Pricing.calculate(lines, null, shippingChoice, freeShippingAbove);
            }
        }

        Map<String, List<SizeChoice>> sizes = sizeChoices(cart.id, rows);
        List<ItemView> views = rows.stream().map(r -> new ItemView(r.itemId, r.variantId, r.productSlug, r.productName, r.sku,
                r.color, r.size, r.imageUrl, r.effective, r.listPrice, r.quantity, r.effective * r.quantity, r.status(),
                Math.max(0, Math.min(MAX_PER_ITEM, r.available)), sizes.getOrDefault(r.productId + ":" + r.colorId, List.of()))).toList();

        boolean canCheckout = !rows.isEmpty() && rows.stream().allMatch(r -> r.status().equals("OK"));
        var view = new CartView(newToken, views, rows.stream().mapToInt(r -> r.quantity).sum(),
                new TotalsView(totals.subtotal(), totals.discount(), totals.shipping(), totals.shippingDiscount(), totals.total()),
                coupon == null ? null : new CouponView(coupon.code, coupon.description), shippingView, warnings, canCheckout);
        return new Computed(view, rows, totals, coupon, selected);
    }

    /** Linhas que entram no total (esgotados e indisponíveis ficam de fora) com a elegibilidade do cupom. */
    private List<Pricing.Line> lines(List<Row> rows, Coupon coupon) {
        List<String> couponPaths = coupon == null || coupon.categoryIds.isEmpty() ? List.of()
                : jdbc.queryForList("SELECT slug_path FROM category WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", coupon.categoryIds), String.class);
        return rows.stream().filter(Row::countsInTotal)
                .map(r -> new Pricing.Line(r.itemId, r.effective, r.quantity, eligible(r, coupon, couponPaths)))
                .toList();
    }

    private static boolean eligible(Row r, Coupon coupon, List<String> couponPaths) {
        if (coupon == null) return false;
        if (coupon.excludeSaleItems && r.effective < r.listPrice) return false;
        boolean restricted = !coupon.categoryIds.isEmpty() || !coupon.productIds.isEmpty();
        if (!restricted) return true;
        return coupon.productIds.contains(r.productId)
                || couponPaths.stream().anyMatch(p -> r.categoryPath.equals(p) || r.categoryPath.startsWith(p + "/"));
    }

    private Map<String, List<SizeChoice>> sizeChoices(UUID cartId, List<Row> rows) {
        Map<String, List<SizeChoice>> result = new HashMap<>();
        if (rows.isEmpty()) return result;
        jdbc.query("""
                SELECT v.id, v.product_id, v.color_id, s.name, {AVAILABLE} > 0
                  FROM product_variant v JOIN size s ON s.id = v.size_id LEFT JOIN inventory i ON i.variant_id = v.id
                 WHERE v.product_id IN (:ids) AND v.active
                 ORDER BY s.size_group, s.sort_order
                """.replace("{AVAILABLE}", AVAILABLE), new MapSqlParameterSource("ids", rows.stream().map(r -> r.productId).distinct().toList())
                .addValue("cart", cartId), rs -> {
            String key = rs.getLong(2) + ":" + rs.getLong(3);
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(new SizeChoice(rs.getLong(1), rs.getString(4), rs.getBoolean(5)));
        });
        return result;
    }

    // ---- dados ----

    private static final class Row {
        long itemId, variantId, productId, colorId, snapshot, effective, listPrice;
        int quantity, available, weightGrams;
        boolean variantActive, productActive;
        String sku, productSlug, productName, categoryPath, color, size, imageUrl;

        boolean sellable() {
            return variantActive && productActive;
        }

        String status() {
            if (!sellable()) return "UNAVAILABLE";
            if (available <= 0) return "OUT_OF_STOCK";
            if (quantity > available) return "QUANTITY_REDUCED";
            return "OK";
        }

        boolean countsInTotal() {
            return sellable() && available > 0;
        }
    }

    private List<Row> rows(UUID cartId) {
        Instant now = clock.instant();
        return jdbc.query("""
                SELECT ci.id, ci.variant_id, ci.quantity, ci.price_snapshot,
                       v.sku, v.active, v.price, v.sale_price, v.sale_starts_at, v.sale_ends_at, v.color_id,
                       p.id, p.slug, p.name, p.status = 'ACTIVE', p.base_price, p.weight_grams, cat.slug_path,
                       c.name, s.name, {AVAILABLE},
                       (SELECT pi.url FROM product_image pi WHERE pi.product_id = p.id
                         ORDER BY (pi.color_id = v.color_id) DESC NULLS LAST, pi.is_main DESC, pi.position LIMIT 1)
                  FROM cart_item ci
                  JOIN product_variant v ON v.id = ci.variant_id
                  JOIN product p ON p.id = v.product_id
                  JOIN category cat ON cat.id = p.main_category_id
                  JOIN color c ON c.id = v.color_id
                  JOIN size s ON s.id = v.size_id
                  LEFT JOIN inventory i ON i.variant_id = v.id
                 WHERE ci.cart_id = :cart
                 ORDER BY ci.created_at, ci.id
                """.replace("{AVAILABLE}", AVAILABLE), new MapSqlParameterSource("cart", cartId), (rs, n) -> {
            Row r = new Row();
            r.itemId = rs.getLong(1);
            r.variantId = rs.getLong(2);
            r.quantity = rs.getInt(3);
            r.snapshot = rs.getLong(4);
            r.sku = rs.getString(5);
            r.variantActive = rs.getBoolean(6);
            ProductVariant pv = priced(rs, 7);
            r.colorId = rs.getLong(11);
            r.productId = rs.getLong(12);
            r.productSlug = rs.getString(13);
            r.productName = rs.getString(14);
            r.productActive = rs.getBoolean(15);
            long base = rs.getLong(16);
            r.weightGrams = rs.getInt(17);
            r.categoryPath = rs.getString(18);
            r.color = rs.getString(19);
            r.size = rs.getString(20);
            r.available = rs.getInt(21);
            r.imageUrl = rs.getString(22);
            r.effective = pv.effectivePrice(base, now);
            r.listPrice = pv.price != null ? pv.price : base;
            return r;
        });
    }

    private record Variant(long productId, boolean sellable, ProductVariant priced, long basePrice, int available) {
        long effectivePrice(Instant now) {
            return priced.effectivePrice(basePrice, now);
        }
    }

    private Optional<Variant> variant(UUID cartId, long variantId) {
        return jdbc.query("""
                SELECT v.product_id, v.active AND p.status = 'ACTIVE', v.price, v.sale_price, v.sale_starts_at, v.sale_ends_at,
                       p.base_price, {AVAILABLE}
                  FROM product_variant v JOIN product p ON p.id = v.product_id LEFT JOIN inventory i ON i.variant_id = v.id
                 WHERE v.id = :id
                """.replace("{AVAILABLE}", AVAILABLE), new MapSqlParameterSource("id", variantId).addValue("cart", cartId),
                (rs, n) -> new Variant(rs.getLong(1), rs.getBoolean(2), priced(rs, 3), rs.getLong(7), rs.getInt(8))).stream().findFirst();
    }

    /** Colunas price, sale_price, sale_starts_at, sale_ends_at a partir de {@code first}: mesma regra de preço do catálogo. */
    private static ProductVariant priced(ResultSet rs, int first) throws SQLException {
        var v = new ProductVariant();
        v.price = (Long) rs.getObject(first);
        v.salePrice = (Long) rs.getObject(first + 1);
        v.saleStartsAt = instant(rs.getTimestamp(first + 2));
        v.saleEndsAt = instant(rs.getTimestamp(first + 3));
        return v;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static void checkQuantity(int quantity, int available) {
        if (quantity > MAX_PER_ITEM) throw new BusinessException(ErrorCode.MAX_QUANTITY);
        if (quantity > available) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    available <= 0 ? "Produto esgotado" : "Disponível: " + available + " unidade(s)");
        }
    }

    private Cart requireCart(Long userId, String guestToken) {
        Cart cart = find(userId, guestToken);
        if (cart == null) throw new BusinessException(ErrorCode.NOT_FOUND, "Sacola vazia");
        return cart;
    }

    private void touch(Cart cart) {
        cart.updatedAt = clock.instant();
        carts.saveAndFlush(cart); // a leitura do carrinho é SQL direto: precisa ver os itens já gravados
    }

    private static CartView empty() {
        return new CartView(null, List.of(), 0, new TotalsView(0, 0, null, 0, 0), null, null, List.of(), false);
    }

    private static String brl(long cents) {
        return "R$ " + String.format(Locale.of("pt", "BR"), "%,.2f", cents / 100.0);
    }
}
