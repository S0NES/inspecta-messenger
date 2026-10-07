# inspecta-messenger

Servico de mensageria do **Inspecta** — plataforma de vistoria imobiliaria. Java 21 (build com JDK 25) + Spring Boot 3 + AMQP.

## O que este projeto faz

Consome a fila `q.notify` diretamente (publicada pelo [inspecta-api](../inspecta-api) e pelo [inspecta-worker](../inspecta-worker) via evento `notify.requested.*`) e envia as notificacoes do sistema:

- **Dispatcher** com idempotencia (tabela `notify.entregas`), renderizacao de template via Handlebars (`{{var}}`) e retry com backoff (3 tentativas).
- **Canais/brokers**: Resend (email), Expo (push), Infobip (SMS — stub), WhatsApp (mock). Sem credencial configurada para um canal, o fallback e automaticamente mock — nada quebra em dev/staging.
- **HTTP exposto**: apenas `/v1/webhooks/{broker}` (status de entrega vindo dos brokers) e `/actuator/health`. Nao existe mais `POST /v1/messages` — a regra "API-only" foi removida, o disparo e so por fila.
- **Storage offload** (`q.storage`): empurra copia de arquivo pra S3/disco local (`FileStorage`).
- **Assinatura / Autentique** (`q.signature-create` + `q.signature-webhook`): dono do IO com a API
  da Autentique — monta+envia o `createDocument` multipart, processa o webhook (HMAC validado na
  borda) e baixa o PDF assinado. Broker por `SIGNATURE_BROKER` (`mock`/`autentique`) +
  `AUTENTIQUE_API_KEY` / `AUTENTIQUE_API_URL` / `AUTENTIQUE_SANDBOX`.
- Banco via [inspecta-core](../inspecta-core).

## Stack

Java 21/25, Spring Boot 3.5, Spring AMQP (RabbitMQ), Handlebars (templates), clientes HTTP para Resend/Expo/Infobip. Pacote base: `br.com.vistoria.messenger`; classe principal: `MessengerApplication`.

## Como rodar

### 1. Subir a infra local (na raiz do workspace `Inspecta/`)
```
docker compose up -d
```

### 2. Instalar a dependencia inspecta-core no repositorio Maven local
```
cd ../inspecta-core
mvn install
```

### 3. Rodar o inspecta-messenger
```
cd ../inspecta-messenger
mvn package
mvn spring-boot:run
```
Sobe com o profile `test` por padrao, na porta **4100**.

- Health check: http://localhost:4100/actuator/health
- Webhook de broker: `POST http://localhost:4100/v1/webhooks/{broker}`

### Docker (imagem de producao)
Build a partir da **raiz do workspace** (o contexto precisa enxergar o inspecta-core):
```
cd ..
docker build -f inspecta-messenger/Dockerfile -t inspecta-messenger .
```

### Variaveis de ambiente (profile `prod`)
Infra obrigatoria (fail-fast sem elas):

| Variavel | Descricao |
|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | Postgres |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | RabbitMQ |

Credenciais de broker (opcionais por canal — sem elas, fallback mock automatico):

| Variavel | Canal |
|---|---|
| `RESEND_API_KEY`, `RESEND_FROM` | email |
| `INFOBIP_API_KEY` | SMS |
| `EXPO_ACCESS_TOKEN` | push |

## Licenca

Software proprietario. Uso comercial sujeito a licenca — ver [LICENSE](./LICENSE).
