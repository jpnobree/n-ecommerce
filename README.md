# Atelier — e-commerce de moda

Spring Boot 4 (Java 21) + Angular 22 (SSR) + PostgreSQL 16 + Stripe. Requisitos e decisões: PRD no Claude Docs.

```
backend/    API REST (Maven, Flyway, Spring Security)
frontend/   Angular com SSR
docker-compose.yml   PostgreSQL (+ API em container)
.github/workflows/   CI
```

## Rodando localmente

Pré-requisitos: Docker e Node 24. JDK 21 + Maven só se for rodar a API fora do Docker.

```bash
docker compose up -d --build      # banco + API em http://localhost:8080
npm --prefix frontend install
npm --prefix frontend start       # loja em http://localhost:4200 (proxy /api -> 8080)
```

A home mostra `API: UP` quando frontend, API e banco estão conectados.

API fora do Docker (hot reload pela IDE): `docker compose up -d db` e rode `AtelierApplication`.

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
| `/actuator/health/liveness`, `/readiness` | probes (porta 8081 em hml/prod) |
| `/actuator/prometheus` | métricas (porta 8081 em hml/prod) |
| `/swagger-ui.html` | documentação OpenAPI (desligada em hml/prod) |

## Configuração por ambiente

Perfis Spring: padrão (dev), `hml`, `prod` (`SPRING_PROFILES_ACTIVE`). Em hml/prod: logs JSON, Swagger desligado, actuator na 8081.

| Variável | Onde | Descrição |
| --- | --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | API | conexão PostgreSQL |
| `APP_VERSION` | API | versão exibida em `/api/status` (usar o SHA do commit) |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT` | API | Sentry; vazio = desligado |
| `LOG_FORMAT` | API | `logstash` para logs JSON também em dev |
| `NG_ALLOWED_HOSTS` | SSR | hosts aceitos pelo servidor SSR (ex.: `www.loja.com.br`) |
| `PORT` | SSR | porta do servidor Node (padrão 4000) |

Segredos nunca vão para o repositório; em hml/prod vêm do secret manager do provedor.
