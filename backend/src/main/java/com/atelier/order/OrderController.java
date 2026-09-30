package com.atelier.order;

import com.atelier.order.OrderService.CheckoutResponse;
import com.atelier.order.OrderService.OrderView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.atelier.shared.web.PageResponse;
import java.util.UUID;

/**
 * Checkout e pedidos do cliente. O corpo do checkout não tem preço, desconto, frete nem total: tudo é
 * recalculado no servidor. Idempotency-Key (UUID gerado pelo navegador por tentativa) é obrigatório.
 */
@RestController
@RequestMapping("/api")
class OrderController {

    record CheckoutRequest(@NotNull Long addressId, @Pattern(regexp = "pac|sedex") String shippingOption) {}

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/checkout")
    ResponseEntity<CheckoutResponse> checkout(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
                                              @Valid @RequestBody CheckoutRequest req, HttpServletRequest http) {
        var placed = orders.checkout(userId(jwt), key, req.addressId(), req.shippingOption(), http.getRemoteAddr(),
                http.getHeader("User-Agent"));
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(placed.order());
    }

    /** "Meus pedidos". */
    @GetMapping("/orders")
    ResponseEntity<PageResponse<OrderService.OrderSummary>> list(@AuthenticationPrincipal Jwt jwt,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.list(userId(jwt), Math.max(0, page), Math.clamp(size, 1, 50)));
    }

    @GetMapping("/orders/{number}")
    ResponseEntity<OrderView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.get(userId(jwt), number));
    }

    @PostMapping("/orders/{number}/cancel")
    OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String number) {
        return orders.cancel(userId(jwt), number);
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
