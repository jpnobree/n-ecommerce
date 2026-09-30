package com.atelier.order;

import com.atelier.order.PaymentService.IntentResponse;
import com.atelier.order.PaymentService.PaymentRow;
import com.atelier.order.RefundService.RefundRequest;
import com.atelier.order.RefundService.RefundView;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class PaymentController {

    private final PaymentService payments;
    private final RefundService refunds;

    PaymentController(PaymentService payments, RefundService refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    /** client_secret do pedido pendente (cria o PaymentIntent na primeira chamada). O navegador confirma com Stripe.js. */
    @PostMapping("/orders/{number}/payment-intent")
    ResponseEntity<IntentResponse> intent(@AuthenticationPrincipal Jwt jwt, @PathVariable String number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(payments.ensureIntent(Long.parseLong(jwt.getSubject()), number));
    }

    /** Público (autenticado pela assinatura). Corpo lido como texto cru, sem desserializar antes de verificar. */
    @PostMapping(value = "/payments/webhook", consumes = "application/json")
    ResponseEntity<Void> webhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String signature) {
        payments.receive(payload, signature);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/admin/payments")
    @PreAuthorize("hasRole('ADMIN')")
    List<PaymentRow> list(@RequestParam(required = false) String status) {
        return payments.list(status, null, null);
    }

    @PostMapping("/admin/payments/{id}/sync")
    @PreAuthorize("hasRole('ADMIN')")
    PaymentRow sync(@PathVariable long id) {
        return payments.sync(id);
    }

    /** Só ADMIN (operador não reembolsa). Idempotency-Key evita reembolso em dobro por clique repetido. */
    @PostMapping("/admin/orders/{number}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    RefundView refund(@AuthenticationPrincipal Jwt jwt, @PathVariable String number, @RequestHeader("Idempotency-Key") UUID key,
                      @Valid @RequestBody RefundRequest req) {
        return refunds.create(Long.parseLong(jwt.getSubject()), number, key, req);
    }
}
