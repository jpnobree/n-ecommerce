package com.atelier.cart;

import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Regras de validade do cupom (PRD 13.2, passos 1–7) e cadastro no admin. Itens elegíveis e mínimo: {@link Pricing}. */
@Service
public class CouponService {

    public record CouponRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,40}$", message = "3 a 40 letras, números, _ ou -") String code,
            @Size(max = 200) String description,
            @NotNull Pricing.DiscountType type,
            @PositiveOrZero Long value,
            @Positive Long maxDiscountAmount,
            @Positive Long maxShippingDiscount,
            @Positive Long minOrderAmount,
            Boolean firstOrderOnly,
            Boolean excludeSaleItems,
            Instant startsAt,
            Instant endsAt,
            @Positive Integer usageLimit,
            @Positive Integer usageLimitPerUser,
            Boolean active,
            Set<Long> categoryIds,
            Set<Long> productIds) {
    }

    public record CouponResponse(Long id, String code, String description, Pricing.DiscountType type, long value,
                                 Long maxDiscountAmount, Long maxShippingDiscount, Long minOrderAmount,
                                 Boolean firstOrderOnly, boolean excludeSaleItems, Instant startsAt, Instant endsAt,
                                 Integer usageLimit, Integer usageLimitPerUser, boolean active, Set<Long> categoryIds,
                                 Set<Long> productIds, long timesUsed) {
    }

    private final CouponRepository coupons;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    CouponService(CouponRepository coupons, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.coupons = coupons;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Códigos são gravados em maiúsculas; normalizar aqui (parâmetro varchar contra citext compara como text). */
    Coupon byCode(String code) {
        return coupons.findByCode(code.trim().toUpperCase(Locale.ROOT)).orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }

    /** Validade independente do conteúdo da sacola. Retorna o motivo da recusa ou null se vale. */
    ErrorCode rejection(Coupon c, Long userId) {
        Instant now = clock.instant();
        if (!c.active) return ErrorCode.COUPON_NOT_FOUND;
        if (c.startsAt != null && c.startsAt.isAfter(now)) return ErrorCode.COUPON_NOT_STARTED;
        if (c.endsAt != null && !c.endsAt.isAfter(now)) return ErrorCode.COUPON_EXPIRED;
        if (userId == null && (c.firstOrderOnly || c.usageLimitPerUser != null)) return ErrorCode.COUPON_REQUIRES_LOGIN;
        // ponytail: primeira compra = cliente sem uso confirmado de cupom; passa a olhar pedidos pagos na Fase 6.
        if (c.firstOrderOnly && usages(c.id, userId, true) > 0) return ErrorCode.COUPON_FIRST_ORDER_ONLY;
        if (c.usageLimit != null && usages(c.id, null, false) >= c.usageLimit) return ErrorCode.COUPON_USAGE_LIMIT_REACHED;
        if (c.usageLimitPerUser != null && usages(c.id, userId, false) >= c.usageLimitPerUser) return ErrorCode.COUPON_USER_LIMIT_REACHED;
        return null;
    }

    /** Usos reservados + confirmados (cupom inteiro ou de um usuário; anyCoupon = qualquer cupom do usuário). */
    private long usages(Long couponId, Long userId, boolean anyCoupon) {
        var sql = new StringBuilder("SELECT count(*) FROM coupon_usage WHERE status <> 'RELEASED'");
        var params = new MapSqlParameterSource();
        if (anyCoupon) sql.append(" AND status = 'CONFIRMED'");
        else sql.append(" AND coupon_id = :coupon");
        if (userId != null) sql.append(" AND user_id = :user");
        params.addValue("coupon", couponId).addValue("user", userId);
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    // ---- admin ----

    @Transactional(readOnly = true)
    public List<CouponResponse> list() {
        return coupons.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CouponResponse save(Long id, CouponRequest req) {
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        Coupon c;
        if (id == null) {
            if (coupons.existsByCode(code)) throw new BusinessException(ErrorCode.COUPON_CODE_EXISTS);
            c = new Coupon();
            c.code = code;
        } else {
            c = coupons.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            // RN-43: cupom já usado não muda de regra, só de vigência, limites e status.
            boolean ruleChanged = !code.equalsIgnoreCase(c.code) || req.type() != c.type || !Objects.equals(req.value(), c.value)
                    || !Objects.equals(req.maxDiscountAmount(), c.maxDiscountAmount)
                    || !Objects.equals(req.minOrderAmount(), c.minOrderAmount);
            if (ruleChanged && usages(c.id, null, false) > 0) throw new BusinessException(ErrorCode.COUPON_IN_USE);
            if (!code.equalsIgnoreCase(c.code) && coupons.existsByCode(code)) throw new BusinessException(ErrorCode.COUPON_CODE_EXISTS);
            c.code = code;
        }
        long value = req.value() == null ? 0 : req.value();
        if (req.type() == Pricing.DiscountType.PERCENTAGE && (value < 1 || value > 100)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Percentual entre 1 e 100");
        }
        if (req.type() == Pricing.DiscountType.FIXED_AMOUNT && value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Informe o valor do desconto em centavos");
        }
        c.description = req.description();
        c.type = req.type();
        c.value = req.type() == Pricing.DiscountType.FREE_SHIPPING ? 0 : value;
        c.maxDiscountAmount = req.maxDiscountAmount();
        c.maxShippingDiscount = req.maxShippingDiscount();
        c.minOrderAmount = req.minOrderAmount();
        c.firstOrderOnly = Boolean.TRUE.equals(req.firstOrderOnly());
        c.excludeSaleItems = Boolean.TRUE.equals(req.excludeSaleItems());
        c.startsAt = req.startsAt();
        c.endsAt = req.endsAt();
        c.usageLimit = req.usageLimit();
        c.usageLimitPerUser = req.usageLimitPerUser();
        if (req.active() != null) c.active = req.active();
        c.categoryIds.clear();
        if (req.categoryIds() != null) c.categoryIds.addAll(req.categoryIds());
        c.productIds.clear();
        if (req.productIds() != null) c.productIds.addAll(req.productIds());
        return toResponse(coupons.saveAndFlush(c));
    }

    private CouponResponse toResponse(Coupon c) {
        return new CouponResponse(c.id, c.code, c.description, c.type, c.value, c.maxDiscountAmount, c.maxShippingDiscount,
                c.minOrderAmount, c.firstOrderOnly, c.excludeSaleItems, c.startsAt, c.endsAt, c.usageLimit,
                c.usageLimitPerUser, c.active, c.categoryIds, c.productIds, usages(c.id, null, false));
    }
}
