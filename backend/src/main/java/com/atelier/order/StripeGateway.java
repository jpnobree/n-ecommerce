package com.atelier.order;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Única porta para a API da Stripe (nos testes, é substituída por um mock). Toda criação leva uma chave de
 * idempotência determinística: repetir a chamada devolve o mesmo objeto em vez de criar outro.
 * Objetos voltam como JSON no mesmo formato do data.object do webhook, para um único tratamento.
 */
@Component
public class StripeGateway {

    record Intent(String id, String clientSecret, String status) {}

    private final StripeClient client;
    private final JsonMapper json;

    StripeGateway(@Value("${app.stripe.secret-key:}") String secretKey, JsonMapper json) {
        this.client = secretKey.isBlank() ? null : new StripeClient(secretKey);
        this.json = json;
    }

    boolean enabled() {
        return client != null;
    }

    Intent createIntent(long orderId, int attempt, String orderNumber, long amount, String email) throws StripeException {
        var params = PaymentIntentCreateParams.builder()
                .setAmount(amount)
                .setCurrency("brl")
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                .setDescription("Pedido " + orderNumber)
                .setReceiptEmail(email)
                .putMetadata("order_id", String.valueOf(orderId))
                .putMetadata("order_number", orderNumber)
                .build();
        PaymentIntent pi = client.paymentIntents().create(params,
                RequestOptions.builder().setIdempotencyKey("pi-create-order-" + orderId + "-" + attempt).build());
        return new Intent(pi.getId(), pi.getClientSecret(), pi.getStatus());
    }

    JsonNode retrieveIntent(String intentId) throws StripeException {
        return json.readTree(client.paymentIntents().retrieve(intentId).toJson());
    }

    /** Cancela e devolve o status final; se já não dá para cancelar (pago, em processamento), devolve o status atual. */
    String cancelIntent(String intentId) throws StripeException {
        try {
            return client.paymentIntents().cancel(intentId).getStatus();
        } catch (StripeException e) {
            return client.paymentIntents().retrieve(intentId).getStatus();
        }
    }

    JsonNode createRefund(String intentId, long amount, long refundId) throws StripeException {
        var params = RefundCreateParams.builder()
                .setPaymentIntent(intentId)
                .setAmount(amount)
                .putMetadata("refund_id", String.valueOf(refundId))
                .build();
        return json.readTree(client.refunds().create(params,
                RequestOptions.builder().setIdempotencyKey("refund-" + refundId).build()).toJson());
    }
}
