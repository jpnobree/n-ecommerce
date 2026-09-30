package com.atelier.order;

import com.atelier.order.OrderAdminService.AdminOrderSummary;
import com.atelier.order.OrderAdminService.AdminOrderView;
import com.atelier.order.OrderStateMachine.Actor;
import com.atelier.order.OrderStateMachine.ActorType;
import com.atelier.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** Pedidos no painel (PRD 15.3). Leitura, envio e notas: OPERATOR e ADMIN (regra de /api/admin/**); cancelar: só ADMIN. */
@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController {

    record TransitionRequest(@NotBlank @Pattern(regexp = "PROCESSING|SHIPPED|DELIVERED") String to, @NotNull Long version,
                             @Size(max = 200) String reason, @Size(max = 40) String carrier, @Size(max = 40) String trackingCode) {}

    record NoteRequest(@NotBlank @Size(max = 2000) String text) {}

    record CancelRequest(@NotBlank @Size(max = 200) String reason) {}

    private final OrderAdminService admin;

    AdminOrderController(OrderAdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    PageResponse<AdminOrderSummary> search(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String fulfillment, @RequestParam(required = false) Instant from,
                                           @RequestParam(required = false) Instant to, @RequestParam(required = false) Boolean hasDispute,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return admin.search(q, status, fulfillment, from, to, hasDispute, page, size);
    }

    @GetMapping("/{number}")
    AdminOrderView detail(@PathVariable String number) {
        return admin.detail(number);
    }

    @PostMapping("/{number}/transitions")
    AdminOrderView transition(@AuthenticationPrincipal Jwt jwt, @PathVariable String number, @Valid @RequestBody TransitionRequest req) {
        return admin.transition(number, req.to(), req.version(), actor(jwt), req.reason(), req.carrier(), req.trackingCode());
    }

    @PostMapping("/{number}/notes")
    AdminOrderView note(@AuthenticationPrincipal Jwt jwt, @PathVariable String number, @Valid @RequestBody NoteRequest req) {
        return admin.addNote(number, Long.parseLong(jwt.getSubject()), req.text());
    }

    @PostMapping("/{number}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    AdminOrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String number, @Valid @RequestBody CancelRequest req) {
        return admin.cancel(number, Long.parseLong(jwt.getSubject()), req.reason());
    }

    @PostMapping("/{number}/resend-confirmation")
    AdminOrderView resend(@PathVariable String number) {
        return admin.resendConfirmation(number);
    }

    private static Actor actor(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        var type = roles != null && roles.contains("ADMIN") ? ActorType.ADMIN : ActorType.OPERATOR;
        return new Actor(type, Long.parseLong(jwt.getSubject()));
    }
}
