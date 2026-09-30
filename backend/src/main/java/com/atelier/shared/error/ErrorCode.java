package com.atelier.shared.error;

import org.springframework.http.HttpStatus;

/** Códigos de erro estáveis da API; o frontend traduz pelo código, não pelo texto. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dados inválidos"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso não encontrado"),
    CONFLICT(HttpStatus.CONFLICT, "Conflito com o estado atual"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "O recurso foi alterado por outra operação"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Tente novamente mais tarde"),
    CSRF_CHECK_FAILED(HttpStatus.FORBIDDEN, "Requisição recusada"),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Conta bloqueada temporariamente por tentativas inválidas"),
    ACCOUNT_BLOCKED(HttpStatus.FORBIDDEN, "Conta bloqueada. Fale com o atendimento"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "E-mail já cadastrado"),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "Sessão expirada"),
    REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "Sessão encerrada por segurança"),
    TOKEN_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "Link inválido ou expirado"),
    WRONG_PASSWORD(HttpStatus.UNPROCESSABLE_CONTENT, "Senha atual incorreta"),
    CPF_IN_USE(HttpStatus.CONFLICT, "CPF já cadastrado em outra conta"),
    ADDRESS_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "Limite de endereços atingido"),

    SLUG_TAKEN(HttpStatus.CONFLICT, "Slug já utilizado"),
    SKU_TAKEN(HttpStatus.CONFLICT, "SKU já utilizado"),
    CATEGORY_DEPTH_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Categorias aceitam no máximo 3 níveis"),
    CATEGORY_CYCLE(HttpStatus.UNPROCESSABLE_CONTENT, "Uma categoria não pode ficar dentro de si mesma"),
    CATEGORY_IN_USE(HttpStatus.CONFLICT, "Categoria com subcategorias ou produtos"),
    PRODUCT_NOT_PUBLISHABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Produto não pode ser publicado"),
    PRODUCT_HAS_VARIANTS(HttpStatus.CONFLICT, "Produto com variantes não pode ser excluído; arquive-o"),
    INVALID_SALE_PRICE(HttpStatus.UNPROCESSABLE_CONTENT, "Preço promocional deve ser menor que o preço cheio"),
    STOCK_BELOW_RESERVED(HttpStatus.CONFLICT, "Estoque não pode ficar abaixo do reservado ou negativo"),
    INVALID_FILTER(HttpStatus.BAD_REQUEST, "Filtro inválido"),
    INVALID_IMAGE(HttpStatus.UNPROCESSABLE_CONTENT, "Imagem inválida"),
    INVALID_URL(HttpStatus.UNPROCESSABLE_CONTENT, "URL não permitida"),

    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Estoque insuficiente"),
    MAX_QUANTITY(HttpStatus.UNPROCESSABLE_CONTENT, "Máximo de 10 unidades por item"),
    VARIANT_OF_OTHER_PRODUCT(HttpStatus.UNPROCESSABLE_CONTENT, "A troca deve ser do mesmo produto"),
    WISHLIST_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT, "Limite de favoritos atingido"),
    COUPON_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "Cupom inválido"),
    COUPON_NOT_STARTED(HttpStatus.UNPROCESSABLE_CONTENT, "Este cupom ainda não está valendo"),
    COUPON_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Cupom expirado"),
    COUPON_REQUIRES_LOGIN(HttpStatus.UNPROCESSABLE_CONTENT, "Entre na sua conta para usar este cupom"),
    COUPON_FIRST_ORDER_ONLY(HttpStatus.UNPROCESSABLE_CONTENT, "Cupom válido só na primeira compra"),
    COUPON_USAGE_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "Cupom esgotado"),
    COUPON_USER_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "Você já usou este cupom"),
    COUPON_NO_ELIGIBLE_ITEMS(HttpStatus.UNPROCESSABLE_CONTENT, "Nenhum item da sacola participa deste cupom"),
    COUPON_MIN_AMOUNT_NOT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "Valor mínimo do cupom não atingido"),
    COUPON_CODE_EXISTS(HttpStatus.CONFLICT, "Já existe um cupom com este código"),
    COUPON_IN_USE(HttpStatus.CONFLICT, "Cupom já utilizado: só é possível desativar ou ajustar vigência e limites"),

    CART_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "Sua sacola está vazia"),
    CART_CHANGED(HttpStatus.CONFLICT, "Sua sacola mudou. Revise antes de finalizar"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Chave de idempotência usada em outra requisição"),
    ORDER_TOTAL_TOO_LOW(HttpStatus.UNPROCESSABLE_CONTENT, "O total mínimo do pedido é R$ 0,50"),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "Operação não permitida no status atual do pedido"),

    PAYMENT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Pagamento indisponível no momento. Tente de novo em instantes"),
    INVALID_SIGNATURE(HttpStatus.BAD_REQUEST, "Assinatura inválida"),
    REFUND_EXCEEDS_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "Valor maior que o saldo reembolsável do pedido");

    public final HttpStatus status;
    public final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }
}
