# Atelier — e-commerce de moda

Spring Boot 4 (Java 21) + Angular 22 (SSR) + PostgreSQL 16 + Stripe. Requisitos e decisões: PRD no Claude Docs.

```
backend/    API REST (Maven, Flyway, Spring Security + JWT)
frontend/   Angular com SSR
docker-compose.yml   PostgreSQL, Mailpit (SMTP de dev) e a API em container
.github/workflows/   CI
```

## Rodando localmente

Pré-requisitos: Docker e Node 24. JDK 21 + Maven só se for rodar a API fora do Docker.

```bash
docker compose up -d --build      # banco + Mailpit + API em http://localhost:8080
npm --prefix frontend install
npm --prefix frontend start       # loja em http://localhost:4200 (proxy /api -> 8080)
```

- A home mostra `API: UP` quando frontend, API e banco estão conectados.
- E-mails (confirmação, redefinição de senha) aparecem em http://localhost:8025.
- Admin de desenvolvimento: criado no primeiro start com `BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD` do `docker-compose.yml`.
- A API do compose sobe com o perfil `seed`: catálogo de demonstração com 2 mil produtos, 11 categorias, cores,
  tamanhos, coleções, estoque e promoções (`db/seed/R__dev_seed.sql`; só roda se não houver produtos).
  Fora do Docker, ative com `SPRING_PROFILES_ACTIVE=seed`. **Nunca em produção.**
- Sem `JWT_PRIVATE_KEY` a API gera um par de chaves efêmero: sessões caem a cada restart da API (só em dev; hml/prod recusam subir sem chaves).

API fora do Docker (hot reload pela IDE): `docker compose up -d db mail` e rode `AtelierApplication`.

## Testes

```bash
cd backend && mvn verify                 # sobe PostgreSQL via Testcontainers (precisa de Docker)
cd frontend && npx ng test --watch=false
```

Sem JDK 21 local, o backend roda dentro de um container:

```bash
docker run --rm -v "$PWD/backend:/src" -v atelier-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /src maven:3.9-eclipse-temurin-21 mvn -B verify
```

## Endpoints úteis (dev)

| URL | Uso |
| --- | --- |
| `GET /api/status` | status e versão |
| `/api/auth/*`, `/api/me/*` | autenticação, conta e endereços (contrato no Swagger) |
| `GET /api/products` | listagem com filtros e facetas: `category`, `collection`, `sizes`, `colors`, `gender`, `minPrice`/`maxPrice` (centavos), `inStock`, `onSale`, `sort` (`newest`, `best_sellers`, `price_asc`, `price_desc`), `page`, `pageSize` (≤ 48) |
| `GET /api/categories`, `/api/categories/page?path=`, `/api/collections` | árvore de categorias, página de categoria, coleções |
| `/api/cart`, `/api/cart/items`, `/coupon`, `/shipping`, `/merge` | sacola de convidado (header `X-Cart-Token`) ou da conta (Bearer); frete por tabela CEP × peso, grátis acima de `APP_CART_FREE_SHIPPING_ABOVE` centavos (padrão 29900) |
| `POST /api/checkout` | cria o pedido (`PENDING_PAYMENT`) e reserva o estoque por 30 min; corpo `{addressId, shippingOption}` + header obrigatório `Idempotency-Key` (UUID). Valores sempre recalculados no servidor |
| `GET /api/orders/{número}`, `POST /api/orders/{número}/cancel` | pedido do cliente (snapshot) e cancelamento antes do pagamento (cancela o PaymentIntent); pedidos não pagos expiram sozinhos |
| `POST /api/orders/{número}/payment-intent` | `clientSecret` + chave publicável para o Payment Element (cria o PaymentIntent na primeira chamada) |
| `POST /api/payments/webhook` | eventos da Stripe (assinatura obrigatória); idempotente por `event.id` |
| `/api/admin/payments`, `/payments/{id}/sync`, `/orders/{número}/refunds` | pagamentos, reconciliação manual e reembolsos (ADMIN; reembolso exige `Idempotency-Key`) |
| `/api/me/wishlist` | favoritos |
| `/api/admin/coupons` | cupons (ADMIN) |
| `/api/admin/categories`, `/collections`, `/colors`, `/sizes`, `/products` | cadastro do catálogo (escrita: ADMIN; leitura e estoque: ADMIN e OPERATOR) |
| `/actuator/health/liveness`, `/readiness` | probes (porta 8081 em hml/prod) |
| `/actuator/prometheus` | métricas (porta 8081 em hml/prod) |
| `/swagger-ui.html` | documentação OpenAPI (desligada em hml/prod) |

## Pagamentos (Stripe) em dev

1. Crie `.env` na raiz (já está no `.gitignore`) com as chaves de **teste** da sua conta Stripe:
   `STRIPE_SECRET_KEY=sk_test_...` e `STRIPE_PUBLISHABLE_KEY=pk_test_...`.
2. Encaminhe os webhooks para a API com a [Stripe CLI](https://docs.stripe.com/stripe-cli):

```bash
stripe listen --forward-to localhost:8080/api/payments/webhook
```

3. Copie o `whsec_...` que ela mostra para `STRIPE_WEBHOOK_SECRET` no `.env` e rode `docker compose up -d api`.

Cartões de teste: `4242 4242 4242 4242` (aprovado), `4000 0000 0000 0002` (recusado), `4000 0027 6000 3184` (3DS).
O pedido só vira PAID pelo webhook; se ele se perder, a reconciliação (a cada 15 min) consulta a Stripe e corrige.
Pix aparece no Payment Element quando está ativado na conta Stripe (Brasil).

## Autenticação (resumo)

- Access token JWT RS256 de 15 min, só em memória no navegador.
- Refresh token opaco de 30 dias em cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`, rotacionado a cada uso.
  Reuso de um token já rotacionado (fora de uma janela de 10 s para abas simultâneas) derruba todas as sessões daquela família.
- Troca/redefinição de senha e "sair de todos" invalidam na hora os access tokens emitidos (claim `tv`).
- Rotas que leem o cookie exigem `X-Requested-With: XMLHttpRequest` (proteção CSRF).
- Rate limit por IP nas rotas de `/api/auth` e bloqueio progressivo da conta após 5 senhas erradas.

## Configuração por ambiente

Perfis Spring: padrão (dev), `hml`, `prod` (`SPRING_PROFILES_ACTIVE`). Em hml/prod: logs JSON, Swagger desligado,
actuator na 8081, IP do cliente lido de `X-Forwarded-For` (o load balancer deve sobrescrever esse header).

| Variável | Onde | Descrição |
| --- | --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | API | conexão PostgreSQL |
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | API | par RSA em PEM (PKCS#8 / X.509); **obrigatório em hml/prod** |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | API | SMTP (padrão: Mailpit em localhost:1025) |
| `APP_MAIL_FROM` | API | remetente dos e-mails |
| `APP_FRONTEND_URL` | API | base dos links enviados por e-mail |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | API | chaves da Stripe (secreta só no backend; vazia = pagamento indisponível) |
| `STRIPE_WEBHOOK_SECRET` | API | segredo `whsec_...` do endpoint de webhook |
| `STRIPE_LIVEMODE` | API | `true` só em produção (eventos de outro modo são ignorados) |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | API | cria o primeiro ADMIN se não houver nenhum (senha com 12+ caracteres) |
| `APP_VERSION` | API | versão exibida em `/api/status` (usar o SHA do commit) |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT` | API | Sentry; vazio = desligado |
| `LOG_FORMAT` | API | `logstash` para logs JSON também em dev |
| `STORAGE_ENDPOINT`, `STORAGE_REGION`, `STORAGE_BUCKET` | API | object storage S3/R2 das imagens (dev: S3 falso `adobe/s3mock` em `localhost:9000`) |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | API | credenciais do storage (vazias = credenciais padrão da AWS) |
| `STORAGE_PUBLIC_BASE_URL` | API | URL pública das imagens (em produção, a CDN) |
| `NG_ALLOWED_HOSTS` | SSR | hosts aceitos pelo servidor SSR (ex.: `www.loja.com.br`) |
| `API_URL` | SSR | API vista pelo servidor Node (sitemap). Padrão `http://localhost:8080` |
| `SITE_URL` | SSR | domínio público usado no sitemap e no robots (padrão: host da requisição) |
| `SITE_INDEXING` | SSR | `false` em homologação: robots.txt bloqueia tudo |
| `PORT` | SSR | porta do servidor Node (padrão 4000) |

Gerar o par de chaves JWT:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
```

Segredos nunca vão para o repositório; em hml/prod vêm do secret manager do provedor.
