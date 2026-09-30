package com.atelier.order;

import com.atelier.cart.CartService;
import com.atelier.cart.CartService.CheckoutQuote;
import com.atelier.cart.CartService.QuoteLine;
import com.atelier.cart.CouponService;
import com.atelier.catalog.service.InventoryService;
import com.atelier.catalog.service.ProductDenormalizer;
import com.atelier.identity.domain.Address;
import com.atelier.identity.repository.AddressRepository;
import com.atelier.identity.service.Tokens;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Checkout (PRD 9.2): transforma o carrinho em pedido PENDING_PAYMENT com snapshot e estoque reservado por 30 min.
 * Valores vêm só do banco; o cliente manda endereço, opção de frete e a chave de idempotência.
 * O PaymentIntent da Stripe entra na Fase 7, depois do commit (nunca dentro da transação).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final Duration RESERVATION = Duration.ofMinutes(30);
    static final long MIN_TOTAL = 50; // limite mínimo da Stripe em BRL
    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

    public record CheckoutResponse(Long orderId, String orderNumber, String status, long total, Instant expiresAt) {}

    /** created = false quando devolve um pedido já existente (mesma chave ou mesmo carrinho). */
    public record Placed(boolean created, CheckoutResponse order) {}

    public record OrderItemView(String productSlug, String productName, String sku, String color, String size, String imageUrl,
                                long unitPrice, long listPrice, int quantity, long discount, long lineTotal) {}

    public record OrderView(String orderNumber, String status, Instant placedAt, Instant expiresAt, Instant cancelledAt,
                            String cancelReason, List<OrderItemView> items, long subtotal, long discount, long shipping,
                            long shippingDiscount, long total, String couponCode, JsonNode shippingAddress, JsonNode shippingMethod) {}

    private final CartService carts;
    private final CouponService coupons;
    private final InventoryService inventory;
    private final ProductDenormalizer denormalizer;
    private final AddressRepository addresses;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    OrderService(CartService carts, CouponService coupons, InventoryService inventory, ProductDenormalizer denormalizer,
                 AddressRepository addresses, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json, Clock clock) {
        this.carts = carts;
        this.coupons = coupons;
        this.inventory = inventory;
        this.denormalizer = denormalizer;
        this.addresses = addresses;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    // ---- checkout ----

    public Placed checkout(long userId, UUID key, long addressId, String option, String ip, String userAgent) {
        String hash = Tokens.sha256(addressId + "|" + (option == null ? "pac" : option));
        Placed replay = byKey(userId, key, hash);
        if (replay != null) return replay;

        Address address = addresses.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Endereço não encontrado"));
        CheckoutQuote quote = carts.checkoutQuote(userId, address.postalCode, option);
        if (quote.lines().isEmpty() && quote.warnings().isEmpty()) throw new BusinessException(ErrorCode.CART_EMPTY);
        if (!quote.warnings().isEmpty() || !quote.canCheckout()) {
            throw new BusinessException(ErrorCode.CART_CHANGED,
                    quote.warnings().stream().map(CartService.Warning::message).collect(Collectors.joining(". ")));
        }
        return tx.execute(s -> place(userId, key, hash, address, quote, ip, userAgent));
    }

    private Placed place(long userId, UUID key, String hash, Address address, CheckoutQuote quote, String ip, String userAgent) {
        // Trava o carrinho: checkouts do mesmo cliente passam um de cada vez daqui em diante
        Integer version = jdbc.queryForObject("SELECT version FROM cart WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", quote.cartId()), Integer.class);
        Placed replay = byKey(userId, key, hash); // a mesma chave pode ter terminado enquanto esperávamos a trava
        if (replay != null) return replay;
        if (version == null || version != quote.cartVersion()) {
            throw new BusinessException(ErrorCode.CART_CHANGED, "ela foi alterada em outra aba ou dispositivo");
        }

        var pending = jdbc.query("""
                SELECT id, cart_version, request_hash FROM orders WHERE cart_id = :cart AND status = 'PENDING_PAYMENT'
                """, new MapSqlParameterSource("cart", quote.cartId()),
                (rs, n) -> new Object[]{rs.getLong(1), rs.getInt(2), rs.getString(3)}).stream().findFirst();
        if (pending.isPresent()) {
            long pendingId = (long) pending.get()[0];
            if ((int) pending.get()[1] == quote.cartVersion() && hash.equals(pending.get()[2])) {
                return new Placed(false, response(pendingId)); // nova aba / outra chave com o mesmo carrinho
            }
            cancelLocked(pendingId, "REPLACED"); // cliente mudou a sacola ou o endereço: o pedido novo substitui
        }

        var coupon = quote.coupon();
        if (coupon != null) {
            jdbc.queryForObject("SELECT id FROM coupon WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", coupon.id), Long.class);
            ErrorCode rejection = coupons.rejection(coupon, userId);
            if (rejection != null) throw new BusinessException(rejection);
        }

        var t = quote.totals();
        if (t.total() < MIN_TOTAL) throw new BusinessException(ErrorCode.ORDER_TOTAL_TOO_LOW);
        long shipping = t.shipping() == null ? 0 : t.shipping();
        Instant now = clock.instant();

        var params = new MapSqlParameterSource()
                .addValue("user", userId).addValue("cart", quote.cartId()).addValue("cartVersion", quote.cartVersion())
                .addValue("key", key).addValue("hash", hash)
                .addValue("subtotal", t.subtotal()).addValue("discount", t.discount()).addValue("shipping", shipping)
                .addValue("shippingDiscount", t.shippingDiscount()).addValue("total", t.total())
                .addValue("couponId", coupon == null ? null : coupon.id).addValue("couponCode", coupon == null ? null : coupon.code)
                .addValue("couponSnapshot", coupon == null ? null : json.writeValueAsString(couponSnapshot(coupon)))
                .addValue("address", json.writeValueAsString(addressSnapshot(address)))
                .addValue("method", json.writeValueAsString(Map.of("carrier", quote.shipping().carrier(),
                        "service", quote.shipping().service(), "option", quote.shipping().option(),
                        "days", quote.shipping().days(), "price", shipping)))
                .addValue("ip", ip).addValue("userAgent", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())))
                .addValue("year", String.valueOf(now.atZone(BRT).getYear()))
                .addValue("placedAt", Timestamp.from(now)).addValue("expiresAt", Timestamp.from(now.plus(RESERVATION)));
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders (order_number, user_id, cart_id, cart_version, idempotency_key, request_hash, status,
                                    customer_name, customer_email, customer_phone, customer_document,
                                    subtotal, discount_total, shipping_total, shipping_discount, total,
                                    coupon_id, coupon_code, coupon_snapshot, shipping_address, shipping_method,
                                    ip, user_agent, placed_at, expires_at)
                SELECT 'AT-' || :year || '-' || lpad(nextval('order_number_seq')::text, 6, '0'),
                       u.id, :cart, :cartVersion, :key, :hash, 'PENDING_PAYMENT', u.full_name, u.email, u.phone, u.cpf,
                       :subtotal, :discount, :shipping, :shippingDiscount, :total,
                       :couponId, :couponCode, CAST(:couponSnapshot AS jsonb), CAST(:address AS jsonb), CAST(:method AS jsonb),
                       :ip, :userAgent, :placedAt, :expiresAt
                  FROM app_user u WHERE u.id = :user
                RETURNING id
                """, params, Long.class);

        jdbc.batchUpdate("""
                INSERT INTO order_item (order_id, product_id, variant_id, sku, product_name, product_slug, color_name, size_name,
                                        image_url, unit_price, list_price, quantity, discount_allocated, line_total, weight_grams)
                VALUES (:order, :product, :variant, :sku, :name, :slug, :color, :size, :image, :unit, :list, :qty, :discount, :total, :weight)
                """, quote.lines().stream().map(l -> new MapSqlParameterSource()
                .addValue("order", orderId).addValue("product", l.productId()).addValue("variant", l.variantId())
                .addValue("sku", l.sku()).addValue("name", l.productName()).addValue("slug", l.productSlug())
                .addValue("color", l.color()).addValue("size", l.size()).addValue("image", l.imageUrl())
                .addValue("unit", l.unitPrice()).addValue("list", l.listPrice()).addValue("qty", l.quantity())
                .addValue("discount", l.discount()).addValue("total", l.unitPrice() * l.quantity() - l.discount())
                .addValue("weight", l.weightGrams())).toArray(MapSqlParameterSource[]::new));

        List<Long> missing = inventory.reserve(quote.lines().stream()
                .collect(Collectors.toMap(QuoteLine::variantId, QuoteLine::quantity)), orderId);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "Acabou enquanto você finalizava: " + quote.lines().stream()
                    .filter(l -> missing.contains(l.variantId())).map(l -> l.productName() + " (" + l.size() + ")")
                    .collect(Collectors.joining(", ")));
        }

        if (coupon != null) {
            jdbc.update("""
                    INSERT INTO coupon_usage (coupon_id, user_id, order_id, status, discount_amount)
                    VALUES (:coupon, :user, :order, 'RESERVED', :amount)
                    """, new MapSqlParameterSource("coupon", coupon.id).addValue("user", userId).addValue("order", orderId)
                    .addValue("amount", t.discount() + t.shippingDiscount()));
        }
        denormalizer.recompute(quote.lines().stream().map(QuoteLine::productId).distinct().toList());
        return new Placed(true, response(orderId));
    }

    private Placed byKey(long userId, UUID key, String hash) {
        return jdbc.query("SELECT id, request_hash FROM orders WHERE user_id = :user AND idempotency_key = :key",
                new MapSqlParameterSource("user", userId).addValue("key", key), (rs, n) -> {
                    if (!hash.equals(rs.getString(2))) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    return new Placed(false, response(rs.getLong(1)));
                }).stream().findFirst().orElse(null);
    }

    private CheckoutResponse response(long orderId) {
        return jdbc.queryForObject("SELECT id, order_number, status, total, expires_at FROM orders WHERE id = :id",
                new MapSqlParameterSource("id", orderId), (rs, n) -> new CheckoutResponse(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getTimestamp(5).toInstant()));
    }

    private static Map<String, Object> addressSnapshot(Address a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("recipientName", a.recipientName);
        m.put("phone", a.phone);
        m.put("postalCode", a.postalCode);
        m.put("state", a.state);
        m.put("city", a.city);
        m.put("district", a.district);
        m.put("street", a.street);
        m.put("number", a.number);
        m.put("complement", a.complement);
        m.put("reference", a.reference);
        return m;
    }

    private static Map<String, Object> couponSnapshot(com.atelier.cart.Coupon c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", c.type);
        m.put("value", c.value);
        m.put("maxDiscountAmount", c.maxDiscountAmount);
        m.put("maxShippingDiscount", c.maxShippingDiscount);
        m.put("minOrderAmount", c.minOrderAmount);
        m.put("excludeSaleItems", c.excludeSaleItems);
        m.put("categoryIds", c.categoryIds);
        m.put("productIds", c.productIds);
        return m;
    }

    // ---- consulta e cancelamento ----

    public OrderView get(long userId, String number) {
        var params = new MapSqlParameterSource("user", userId).addValue("number", number);
        OrderView order = jdbc.query("""
                SELECT id, order_number, status, placed_at, expires_at, cancelled_at, cancel_reason, subtotal, discount_total,
                       shipping_total, shipping_discount, total, coupon_code, shipping_address::text, shipping_method::text
                  FROM orders WHERE order_number = :number AND user_id = :user
                """, params, (rs, n) -> new OrderView(rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getString(7), items(rs.getLong(1)), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11),
                rs.getLong(12), rs.getString(13), json.readTree(rs.getString(14)), json.readTree(rs.getString(15))))
                .stream().findFirst().orElse(null);
        // Pedido de outro cliente é "não encontrado", não "proibido" (não revela que existe)
        if (order == null) throw new BusinessException(ErrorCode.NOT_FOUND, "Pedido não encontrado");
        return order;
    }

    private List<OrderItemView> items(long orderId) {
        return jdbc.query("""
                SELECT product_slug, product_name, sku, color_name, size_name, image_url, unit_price, list_price, quantity,
                       discount_allocated, line_total
                  FROM order_item WHERE order_id = :id ORDER BY id
                """, new MapSqlParameterSource("id", orderId), (rs, n) -> new OrderItemView(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8),
                rs.getInt(9), rs.getLong(10), rs.getLong(11)));
    }

    /** Cliente cancela um pedido ainda não pago (PRD 11.4). */
    public OrderView cancel(long userId, String number) {
        tx.executeWithoutResult(s -> {
            Long id = jdbc.query("SELECT id FROM orders WHERE order_number = :number AND user_id = :user FOR UPDATE",
                    new MapSqlParameterSource("user", userId).addValue("number", number), (rs, n) -> rs.getLong(1))
                    .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Pedido não encontrado"));
            if (!cancelLocked(id, "CUSTOMER")) throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        });
        return get(userId, number);
    }

    /**
     * Pedidos não pagos no prazo são cancelados e devolvem estoque e cupom. SKIP LOCKED: várias instâncias
     * dividem o trabalho sem esperar umas pelas outras. Fase 7: cancelar o PaymentIntent antes.
     */
    @Scheduled(fixedDelayString = "${app.checkout.expire-interval:60s}")
    public void expireOverdue() {
        Integer expired = tx.execute(s -> {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND expires_at < :now
                     ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED
                    """, new MapSqlParameterSource("now", Timestamp.from(clock.instant())), Long.class);
            ids.forEach(id -> cancelLocked(id, "PAYMENT_TIMEOUT"));
            return ids.size();
        });
        if (expired != null && expired > 0) log.info("{} pedido(s) expirado(s) por falta de pagamento", expired);
    }

    /** Só sai de PENDING_PAYMENT (idempotente): devolve reserva e uso do cupom. */
    private boolean cancelLocked(long orderId, String reason) {
        int changed = jdbc.update("""
                UPDATE orders SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = :reason,
                                  version = version + 1, updated_at = now()
                 WHERE id = :id AND status = 'PENDING_PAYMENT'
                """, new MapSqlParameterSource("id", orderId).addValue("reason", reason));
        if (changed == 0) return false;
        Map<Long, Integer> reserved = new HashMap<>();
        Set<Long> products = new HashSet<>();
        jdbc.query("SELECT variant_id, quantity, product_id FROM order_item WHERE order_id = :id AND variant_id IS NOT NULL",
                new MapSqlParameterSource("id", orderId), rs -> {
                    reserved.merge(rs.getLong(1), rs.getInt(2), Integer::sum);
                    products.add(rs.getLong(3));
                });
        inventory.release(reserved, orderId);
        jdbc.update("UPDATE coupon_usage SET status = 'RELEASED' WHERE order_id = :id AND status = 'RESERVED'",
                new MapSqlParameterSource("id", orderId));
        denormalizer.recompute(products);
        return true;
    }
}
