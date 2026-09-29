package com.atelier.cart;

import com.atelier.cart.CartService.CartView;
import com.atelier.cart.CouponService.CouponRequest;
import com.atelier.cart.CouponService.CouponResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Carrinho de convidado (header X-Cart-Token) ou de cliente logado (Bearer). O token do convidado é criado
 * na primeira escrita e volta no header e no corpo (cartToken). Nada aqui é cacheável.
 */
@RestController
@RequestMapping("/api")
public class CartController {

    static final String TOKEN_HEADER = "X-Cart-Token";

    public record AddItemRequest(@NotNull Long variantId, @Min(1) @Max(10) int quantity) {}

    public record UpdateItemRequest(@Min(1) @Max(10) Integer quantity, Long variantId) {}

    public record CouponCodeRequest(@NotBlank @Size(max = 40) String code) {}

    public record ShippingRequest(@NotBlank @Pattern(regexp = "[0-9]{8}", message = "CEP deve ter 8 dígitos") String postalCode,
                                  @Pattern(regexp = "pac|sedex") String option) {}

    private final CartService cart;
    private final CouponService coupons;

    CartController(CartService cart, CouponService coupons) {
        this.cart = cart;
        this.coupons = coupons;
    }

    @GetMapping("/cart")
    ResponseEntity<CartView> view(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return respond(HttpStatus.OK, cart.view(userId(jwt), token));
    }

    @PostMapping("/cart/items")
    ResponseEntity<CartView> add(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                 @Valid @RequestBody AddItemRequest req) {
        return respond(HttpStatus.CREATED, cart.add(userId(jwt), token, req.variantId(), req.quantity()));
    }

    @PatchMapping("/cart/items/{itemId}")
    ResponseEntity<CartView> update(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @PathVariable long itemId, @Valid @RequestBody UpdateItemRequest req) {
        return respond(HttpStatus.OK, cart.update(userId(jwt), token, itemId, req.quantity(), req.variantId()));
    }

    @DeleteMapping("/cart/items/{itemId}")
    ResponseEntity<CartView> remove(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @PathVariable long itemId) {
        return respond(HttpStatus.OK, cart.remove(userId(jwt), token, itemId));
    }

    @PutMapping("/cart/coupon")
    ResponseEntity<CartView> applyCoupon(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                         @Valid @RequestBody CouponCodeRequest req) {
        return respond(HttpStatus.OK, cart.applyCoupon(userId(jwt), token, req.code()));
    }

    @DeleteMapping("/cart/coupon")
    ResponseEntity<CartView> removeCoupon(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return respond(HttpStatus.OK, cart.removeCoupon(userId(jwt), token));
    }

    /** CEP + opção (pac/sedex; padrão pac). A resposta traz as opções com preço e prazo. */
    @PutMapping("/cart/shipping")
    ResponseEntity<CartView> shipping(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @Valid @RequestBody ShippingRequest req) {
        return respond(HttpStatus.OK, cart.setShipping(userId(jwt), token, req.postalCode(), req.option()));
    }

    /** Chamado pelo frontend logo após login/cadastro, com o token do carrinho de convidado. */
    @PostMapping("/cart/merge")
    ResponseEntity<CartView> merge(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return respond(HttpStatus.OK, cart.merge(Long.parseLong(jwt.getSubject()), token));
    }

    // ---- cupons (admin) ----

    @GetMapping("/admin/coupons")
    @PreAuthorize("hasRole('ADMIN')")
    List<CouponResponse> coupons() {
        return coupons.list();
    }

    @PostMapping("/admin/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    CouponResponse createCoupon(@Valid @RequestBody CouponRequest req) {
        return coupons.save(null, req);
    }

    @PutMapping("/admin/coupons/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    CouponResponse updateCoupon(@PathVariable Long id, @Valid @RequestBody CouponRequest req) {
        return coupons.save(id, req);
    }

    private static Long userId(Jwt jwt) {
        return jwt == null ? null : Long.valueOf(jwt.getSubject());
    }

    private static ResponseEntity<CartView> respond(HttpStatus status, CartView view) {
        var res = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (view.cartToken() != null) res.header(TOKEN_HEADER, view.cartToken());
        return res.body(view);
    }
}
