# Jobs — NFS-e Emission Pipeline
> **Guia Arquitetural, Referência Técnica e Mentoria de Engenharia de Software**  
> *Stack: Java 21 | Spring Boot | Apache Kafka | PostgreSQL | Apache Cassandra | Hexagonal Architecture | DDD*

---

## 1. Visão Geral e Propósito do Projeto: Arquitetura para Alta Volumetria em Fintechs

O **Jobs — Asynchronous Processing Pipeline** é uma solução arquitetural focada em resolver um dos problemas mais críticos de engenharia em sistemas financeiros (Fintechs) e E-commerces: **a ingestão e o processamento assíncrono de Transações e Pedidos em altíssima volumetria sem perda de dados.**

### O Problema de Negócio (A Dor Real)
Em cenários de alto tráfego (como Black Friday ou picos de transações bancárias), uma arquitetura monolítica síncrona que depende de APIs de terceiros (Gateways de Pagamento, Adquirentes ou APIs legadas) enfrenta gargalos fatais:
1. **Fila de Espera:** A comunicação com integrações externas sofre latência variável, segurando a resposta do usuário.
2. **Esgotamento de Recursos:** Manter milhares de conexões HTTP abertas esperando respostas esgota rapidamente o *thread pool* dos servidores, gerando quedas em cascata (*cascading failures*).
3. **Inconsistência de Dados:** Se a aplicação cai durante a autorização de um pagamento ou processamento do job, o status é perdido, gerando prejuízos financeiros severos.

### A Solução Arquitetural (Design Orientado a Eventos)
Para garantir resiliência e disponibilidade de 99.99%, este projeto propõe uma **Arquitetura Distribuída e Orientada a Eventos (EDA)** com as seguintes decisões de System Design:

1. **Ingestion Gateway (API):** A API atua como um funil ultra-rápido. Ela recebe a payload crua (`byte[]`) e a aceita imediatamente (`HTTP 202 Accepted`), livrando a thread do cliente em milissegundos sem gastar CPU com deserialização na borda.
2. **Transactional Outbox Pattern:** Garante que a intenção de processamento seja salva no PostgreSQL na mesma transação que dispara o evento, eliminando o risco de *Dual-Write Hazard* e perda da transação.
3. **Load Leveling com Apache Kafka:** O Kafka amortece o pico de requisições. O *worker* consome as mensagens no seu próprio ritmo, protegendo sistemas internos ou APIs de terceiros contra ataques de negação de serviço (DDoS) involuntários.
4. **Armazenamento Híbrido (Polyglot Persistence):** O estado transacional (PENDING, DONE) fica no PostgreSQL, enquanto logs pesados e documentos associados são absorvidos pelo **Apache Cassandra**, otimizado para gravações massivas em disco.

---

## 2. Diagrama de Arquitetura e Fluxo de Dados Ponta a Ponta

O diagrama a seguir ilustra o fluxo completo de uma requisição desde a recepção pelo cliente HTTP até o processamento no worker de emissão e atualização do estado final:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       FLUXO PONTA A PONTA (E2E)                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

 [ Cliente / ERP ]
        │
        │ 1. POST /api/jobs/nfs (Payload JSON da NFS-e)
        ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│ MÓDULO: jobs-api (Porta 8080)                                                               │
│                                                                                             │
│  [ Driving Adapter / Web ]                                                                  │
│  └── JobController (Validação estrutural do payload)                                        │
│             │                                                                               │
│             │ 2. Invoca caso de uso                                                         │
│             ▼                                                                               │
│  [ Application Core ]                                                                       │
│  └── JobService (Instancia Job com status PENDING)                                          │
│             │                                                                               │
│             ├── 3. Persiste Estado Inicial ────────► [ Driven Adapter: PostgreSQL ]         │
│             │                                        └── PostgresJobRepository (jobsdb)     │
│             │                                                                               │
│             └── 4. Publica Evento ─────────────────► [ Driven Adapter: Kafka Producer ]     │
│                                                      └── KafkaJobEventPublisher             │
│                                                                     │                       │
│  5. Retorna HTTP 202 Accepted { "jobId": "uuid" }                   │                       │
└─────────────────────────────────────────────────────────────────────┼───────────────────────┘
                                                                      │
                                                       Tópico Kafka:  │ (JobCreatedEvent)
                                                       "job-created"  ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│ MÓDULO: jobs-consumer (Worker Assíncrono)                                                   │
│                                                                                             │
│  [ Driving Adapter: Kafka Consumer ]                                                        │
│  └── JobEventoConsumer (Consome mensagem da partição)                                       │
│             │                                                                               │
│             │ 6. Despacha execução                                                          │
│             ▼                                                                               │
│  [ Application Core ]                                                                       │
│  └── JobProcessingService (Transição: PENDING -> PROCESSING)                                │
│             │                                                                               │
│             ├── 7. Consulta Job no Banco ──────────► [ Driven Adapter: PostgreSQL ]         │
│             │                                        └── PostgresJobRepository (jobsdb)     │
│             │                                                                               │
│             ├── 8. Emite NFS-e ────────────────────► [ Driven Adapter: Gateway Fiscal ]     │
│             │                                        └── Simulador SEFAZ / Prefeitura       │
│             │                                                                               │
│             ├── 9. Persiste Documentos ────────────► [ Driven Adapter: Cassandra ]          │
│             │    (XML DPS, XML Autorizado, PDF)       └── NfseDocumentoRepository            │
│             │                                             (chave_acesso como partition key)  │
│             │                                                                               │
│             └── 10. Atualiza Estado Final ─────────► [ Driven Adapter: PostgreSQL ]         │
│                    (DONE com protocolo OU                                                   │
│                     FAILED com lista de erros)                                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Ciclo de Vida e Máquina de Estados do Job

```
                   ┌──────────────┐
                   │  Submissão   │
                   └──────┬───────┘
                          │
                          ▼
                  ┌───────────────┐
                  │    PENDING    │  Job gravado no PostgreSQL e evento postado no Kafka.
                  └───────┬───────┘
                          │
                          │ Consumidor inicia leitura do lote
                          ▼
                 ┌─────────────────┐
                 │   PROCESSING    │  Lock otimista, payload em validação e envio fiscal.
                 └────────┬────────┘
                          │
            ┌─────────────┴─────────────┐
            │                           │
  Autorizado pela SEFAZ         Rejeição fiscal ou erro
            ▼                           ▼
     ┌──────────────┐            ┌──────────────┐
     │  COMPLETED   │            │    FAILED    │  Erros registrados no histórico
     │    (DONE)    │            │              │  e mensagem encaminhada para DLT.
     └──────────────┘            └──────────────┘
```

---

## 3. Estrutura de Módulos e Camadas Hexagonais

O projeto adota os princípios da **Arquitetura Hexagonal (Ports and Adapters)** combinados com preceitos táticos do **Domain-Driven Design (DDD)**. O código é estruturado de forma que o núcleo de domínio e as regras de aplicação não dependam de nenhum detalhe de infraestrutura (banco de dados, brokers de mensageria ou frameworks web).

```
jobs/
├── pom.xml                                 # Root Reactor POM (Gestão central de dependências)
├── compose.yaml                            # Ambiente conteinerizado (PostgreSQL, Kafka, Zookeeper)
├── Dockerfile                              # Imagem multi-stage build para os serviços
├── jobs-api/                               # Microsserviço de Entrada REST e Ingestão
│   └── src/main/java/hub/pedro/jobs/api/
│       ├── domain/                         # Domínio: Entidades, Value Objects e Contratos
│       │   ├── entity/Job.java             # Aggregate Root de Negócio
│       │   ├── shared/JobStatus.java       # Enum de Estados do Ciclo de Vida
│       │   └── interfaces/JobRepository.java # Outbound Port de Persistência
│       ├── app/                            # Aplicação: Casos de Uso e Orquestração
│       │   ├── service/JobService.java     # Application Service
│       │   └── port/out/JobEventPublisher.java # Outbound Port de Mensageria
│       ├── infra/                          # Infraestrutura: Adaptadores Concretos
│       │   ├── database/postgresql/        # Adaptador de Banco Relacional (JPA/Hibernate)
│       │   └── kafka/publisher/            # Adaptador de Publicação Kafka
│       ├── web/                            # Adaptador Web de Entrada (Driving Adapter)
│       │   └── api/in/JobController.java   # Controller REST
│       └── config/                         # Configurações de Beans do Spring Boot
└── jobs-consumer/                          # Microsserviço Worker de Processamento
    └── src/main/java/hub/pedro/jobs/consumer/
        ├── domain/                         # Domínio do Worker
        │   ├── entity/Job.java             # Entidade de Negócio com transições de estado
        │   └── repository/JobRepository.java # Outbound Port
        ├── app/                            # Aplicação do Worker
        │   └── service/JobProcessingService.java # Orquestração do Processamento Fiscal
        ├── infra/                          # Infraestrutura do Consumer
        │   ├── database/postgresql/        # Adaptador JPA
        │   └── kafka/consumer/             # Driving Adapter Kafka Listener
        └── config/                         # Configurações de Threads e Serialização
```

### Detalhamento das Camadas Hexagonais

| Camada | Responsabilidade | Dependências Permitidas | O que deve conter |
|---|---|---|---|
| `domain` | **Coração do Negócio**: Regras fiscais, invariantes de estado, entidades e interfaces de repositório. | **Nenhuma**. Código Java puro sem anotações de frameworks web ou JPA. | `Job`, `JobStatus`, `JobId`, `JobRepository` (Port). |
| `app` (application) | **Casos de Uso**: Orquestra o fluxo de dados entre o domínio e as portas externas. | Depende apenas da camada `domain`. | `ScheduleJobUseCase` (Port In), `JobEventPublisher` (Port Out), `JobService`. |
| `infra` (infrastructure) | **Adaptadores de Saída (Driven Adapters)**: Implementações técnicas que conectam o sistema ao mundo externo. | Depende de `app`, `domain` e bibliotecas externas (Spring Data, Kafka, JDBC). | `PostgresJobRepository`, `KafkaJobEventPublisher`, `JobJpaEntity`. |
| `web` | **Adaptador de Entrada (Driving Adapter)**: Expõe a API para o mundo externo via HTTP/REST. | Depende das portas de entrada de `app` e DTOs de transporte. | `JobController`, `PayloadSizeFilter`, DTOs de Request/Response. |
| `config` | **Composição de Infraestrutura**: Configurações de framework e injeção de dependência. | Spring Framework, Jackson, etc. | `JacksonConfig`, `KafkaProducerConfig`, `AsyncConfig`. |

---

## 4. Modelo de Dados e Contratos de Eventos

### Modelo Relacional (PostgreSQL — Tabela `nfse_job`)

O estado transacional de cada lote/job é armazenado na tabela relacional `nfse_job` no PostgreSQL:

```sql
CREATE TABLE nfse_job (
    id            UUID PRIMARY KEY,
    payload       TEXT NOT NULL,
    status        VARCHAR(30) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE,
    scheduled_at  TIMESTAMP WITH TIME ZONE,
    finished_at   TIMESTAMP WITH TIME ZONE,
    attempts      INTEGER NOT NULL DEFAULT 0,
    errors        TEXT, -- ou tabela associativa / JSONB para histórico de falhas
    version       BIGINT NOT NULL DEFAULT 0 -- Controle de Concorrência Otimista (@Version)
);

-- Índices recomendados para alta performance operacional:
CREATE INDEX idx_nfse_job_status ON nfse_job (status);
CREATE INDEX idx_nfse_job_created_at ON nfse_job (created_at DESC);
```

### Dicionário de Dados

| Coluna | Tipo | Nullable | Descrição |
|---|---|:---:|---|
| `id` | `UUID` | Não | Chave primária identificadora única do Job (gerada pelo cliente ou pela API). |
| `payload` | `TEXT` | Não | Conteúdo estruturado (JSON/XML) contendo os dados da DPS (Declaração de Prestação de Serviço). |
| `status` | `VARCHAR(30)` | Não | Estado atual do processamento: `PENDING`, `PROCESSING`, `DONE`, `FAILED`. |
| `created_at` | `TIMESTAMPTZ` | Não | Timestamp UTC de recebimento e persistência inicial pela API. |
| `updated_at` | `TIMESTAMPTZ` | Sim | Timestamp UTC da última modificação de status. |
| `scheduled_at`| `TIMESTAMPTZ` | Sim | Timestamp UTC programado para execução ou reprocessamento com backoff. |
| `finished_at` | `TIMESTAMPTZ` | Sim | Timestamp UTC de finalização com sucesso (`DONE`) ou encerramento por falha (`FAILED`). |
| `attempts` | `INTEGER` | Não | Contador cumulativo de tentativas de processamento executadas. |
| `errors` | `TEXT` / `JSONB` | Sim | Histórico detalhado de mensagens de erro e rejeições tributárias. |
| `version` | `BIGINT` | Não | Versão para bloqueio otimista contra atualizações concorrentes (*Lost Updates*). |

---

### Modelo de Documentos (Apache Cassandra — Tabela `nfse_documento`)

O Cassandra é utilizado como camada de persistência otimizada para **alto volume de escrita** de documentos fiscais (XMLs e PDFs). Enquanto o PostgreSQL gerencia o ciclo de vida transacional, o Cassandra absorve os payloads pesados com acesso direto por chave de partição:

```cql
CREATE KEYSPACE IF NOT EXISTS nfse_keyspace
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE nfse_keyspace.nfse_documento (
    chave_acesso  TEXT,
    xml_dps       TEXT,
    xml_autorizado TEXT,
    pdf_danfse    BLOB,
    created_at    TIMESTAMP,
    PRIMARY KEY (chave_acesso)
);
```

| Coluna | Tipo | Descrição |
|---|---|---|
| `chave_acesso` | `TEXT` (PK) | Chave de acesso da NFS-e — partition key para leitura direta O(1). |
| `xml_dps` | `TEXT` | XML da DPS (Declaração de Prestação de Serviço) enviada à SEFAZ. |
| `xml_autorizado` | `TEXT` | XML retornado pela SEFAZ após autorização. |
| `pdf_danfse` | `BLOB` | PDF do DANFSE gerado para o contribuinte. |
| `created_at` | `TIMESTAMP` | Timestamp de armazenamento do documento. |

> **Por que Cassandra para documentos?**
> - **Write-optimized**: O modelo LSM-tree do Cassandra torna inserções massivas extremamente rápidas — ideal para picos de emissão de fim de mês.
> - **Acesso por chave**: Consultas por `chave_acesso` são O(1) sem necessidade de índices secundários.
> - **Escalabilidade horizontal**: Adicionar nós distribui a carga linearmente, sem rebalanceamento complexo.
> - **Separação de responsabilidades**: O PostgreSQL fica livre de payloads pesados (XMLs de 50-200KB, PDFs de 100-500KB), mantendo performance para queries relacionais de status e relatórios.

---

### Contrato de Mensageria (Apache Kafka — Tópico `job-created`)

O evento publicado no Kafka funciona como o contrato canônico de integração assíncrona entre o produtor (`jobs-api`) e o consumidor (`jobs-consumer`):

```json
{
  "eventId": "a7c2e8b4-9341-4e78-9df2-5d9c72e38101",
  "eventType": "JOB_CREATED_EVENT",
  "schemaVersion": "1.0.0",
  "occurredAt": "2026-08-28T19:30:00.000Z",
  "correlationId": "b182d3f9-7104-4861-9c3f-4e6f9d2a15c8",
  "payload": {
    "jobId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "status": "PENDING",
    "createdAt": "2026-08-28T19:30:00.000Z",
    "dps": {
      "cnpjPrestador": "12345678000195",
      "cnpjTomador": "98765432000109",
      "valorServico": 2500.00,
      "codigoTributacaoMunicipio": "1.01",
      "discriminacao": "Serviços de consultoria em arquitetura de software"
    }
  }
}
```

#### Headers Recomendados no Registro Kafka:
- `X-Correlation-Id`: Identificador único de rastreabilidade para *Distributed Tracing* (OpenTelemetry / Jaeger).
- `X-Idempotency-Key`: Chave para evitar reprocessamento duplicado no consumidor.
- `X-Source-Service`: Identifica a aplicação emissora (`jobs-api`).

---

## 5. Decisões Técnicas e Trade-offs Arquiteturais

### 1. Por que Apache Kafka em vez de Mensageria Tradicional (RabbitMQ / SQS)?
- **Capacidade de Replay (Log Imutável)**: O Kafka armazena as mensagens em disco de forma persistente e ordenada. Se uma prefeitura ficar 4 horas fora do ar ou se o *worker* sofrer um bug de implementação, é possível reposicionar o ponteiro de leitura (*offset reset*) e reprocessar todas as NFS-e sem sobrecarregar a API nem perder dados.
- **Particionamento e Paralelismo Determinístico**: O particionamento permite distribuir a carga entre múltiplos consumidores usando chaves semânticas (como o `cnpjPrestador`), garantindo que notas da mesma empresa sejam processadas na ordem estrita de emissão, enquanto emissores diferentes rodam em paralelo.
- **Alta Vazão e Absorção de Picos (Load Leveling)**: O Kafka opera facilmente na casa de dezenas de milhares de eventos por segundo, servindo de colchão de amortecimento contra picos sazonais de fim de mês.

### 2. Por que Arquitetura Hexagonal?
- **Desacoplamento Tecnológico**: Se o banco de dados for migrado do PostgreSQL para Spanner ou se a mensageria mudar de Kafka para AWS Kinesis/Pulsar, as regras de negócio de emissão de NFS-e permanecem 100% intactas.
- **Testabilidade Superior**: É possível testar todas as regras de transição de status do `Job` em milissegundos através de testes unitários puros, sem precisar levantar o contexto Spring, containers Docker ou bancos de dados reais.

### 3. Consistência Eventual vs. Consistência Imediata (ACID)
- No momento da ingestão, a consistência entre o banco da API e o tópico Kafka é assíncrona. O cliente recebe uma garantia de **aceite de processamento** (`HTTP 202 Accepted`), e não a confirmação final da SEFAZ.
- **O Desafio do Dual-Write**: Gravar no PostgreSQL e publicar no Kafka em operações separadas sem atomicidade gera risco de inconsistência. A solução padrão de mercado para mitigar esse trade-off é o **Transactional Outbox Pattern** (detalhado na seção de diagnóstico).

### 4. Comparativo Arquitetural: Design Original vs. Estado Atual

| Aspecto | Proposta Conceitual Inicial | Estado Atual da Base de Código | Recomendação Sênior |
|---|---|---|---|
| **Persistência de Dados** | Dual Database: PostgreSQL (Metadados) + Apache Cassandra (XMLs/PDFs) | PostgreSQL Unificado (`jobsdb`); Cassandra configurado no K8s mas não integrado no código | **Implementar a estratégia Dual Database conforme planejado.** O PostgreSQL gerencia o ciclo de vida transacional (status, timestamps, chave de acesso) com consultas relacionais. O Cassandra absorve alto volume de inserções de documentos fiscais (XML DPS, XML autorizado, PDF DANFSE) explorando sua otimização nativa para *write-heavy workloads* e acesso por chave de partição (`chave_acesso`). Essa separação é um excelente exercício de *Polyglot Persistence* e reflete padrões reais de produção em sistemas fiscais de alto throughput. |
| **Integração API → Worker** | Event-Carried State Transfer (Payload completo no evento) | Híbrido Inconsistente (Envia bytes no evento, mas busca no banco pelo ID) | **Adotar Event-Carried State Transfer claro** com DTOs fiscais estruturados no evento para evitar dependência síncrona de banco entre módulos. |
| **Simulador Fiscal** | SDK Nacional de NFS-e integrado com fallback Mock | Status alterado em memória sem simulação de rede ou chamadas externas | **Implementar Gateway de Emissão com Resilience4j** contendo simulação de latência (200ms-1500ms) e taxas de erro controladas para teste de resiliência. |

---

## 6. Guia de Execução Local e Testes Manuais

### Pré-requisitos
- **Java Development Kit (JDK)**: Versão **21** (Temurin, Corretto ou OpenJDK)
- **Apache Maven**: Versão **3.9+**
- **Docker & Docker Compose**: Docker 24+ com Compose v2 habilitado

---

### Passo a Passo de Execução

#### 1. Subir a Infraestrutura de Suporte
Execute o Docker Compose para inicializar o PostgreSQL e o cluster Kafka:
```bash
docker compose up -d
```
*Serviços disponíveis:*
- PostgreSQL: `localhost:5432` (Database: `jobsdb`, User: `postgres`, Pass: `postgres`)
- Kafka Broker: `localhost:9094` (Listeners: `PLAINTEXT://localhost:9094`)
- Zookeeper: `localhost:2181`

#### 2. Compilar os Módulos do Projeto
Compile todo o projeto a partir da raiz do repositório:
```bash
mvn clean package -DskipTests
```

#### 3. Executar as Aplicações

**Terminal 1 — Executando a API:**
```bash
# Executa o módulo jobs-api
java -jar jobs-api/target/jobs-api-0.0.1-SNAPSHOT.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/jobsdb \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --spring.kafka.bootstrap-servers=localhost:9094
```

**Terminal 2 — Executando o Worker Consumer:**
```bash
# Executa o módulo jobs-consumer
java -jar jobs-consumer/target/jobs-consumer-0.0.1-SNAPSHOT.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/jobsdb \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --spring.kafka.bootstrap-servers=localhost:9094
```

---

### Exemplos Práticos de Requisições via `curl`

#### Cenário A: Submeter Lote de Emissão de NFS-e (Caminho Feliz)

```bash
curl -X POST http://localhost:8080/api/jobs/nfs \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d" \
  -d '{
    "prestador": {
      "cnpj": "12345678000195",
      "inscricaoMunicipal": "123456"
    },
    "tomador": {
      "cnpj": "98765432000109",
      "razaoSocial": "Empresa Tomadora de Servicos LTDA",
      "email": "financeiro@tomador.com.br"
    },
    "servico": {
      "codigoTributacaoMunicipio": "1.01",
      "discriminacao": "Desenvolvimento e manutencao de software sob medida",
      "valorServicos": 15000.00,
      "aliquotaIss": 0.05
    }
  }'
```

**Resposta Esperada (`HTTP 202 Accepted`):**
```json
{
  "jobId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

#### Cenário B: Inspeção das Mensagens no Tópico Kafka
Para validar que o evento foi publicado no Kafka:
```bash
docker exec -it $(docker ps -qf "name=kafka") /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic job-created \
  --from-beginning
```

---

## 7. Diagnóstico Estrutural e Mentoria Técnica

Abaixo está o inventário técnico completo dos **17 problemas arquiteturais e estruturais** identificados no projeto, agrupados por nível de criticidade com explicações pedagógicas detalhadas e orientações de correção no padrão de liderança técnica sênior.

---

### 🔴 Problemas Críticos (Nível 1 — Risco de Perda de Dados, Inconsistência Severa e Build Quebrado)

---

#### 1. Perda de Dados Crítica no Consumer: `@Async` no `@KafkaListener` com Auto-Commit
- **O que está no código atual**:
  - `JobEventoConsumer.java:33-41`: O método listener recebe o evento do Kafka e chama `jobProcessingService.processarNf(evento.id())`.
  - `JobProcessingService.java:24-31`: O método está anotado com `@Async("jobProcessingExecutor")`.
  - `AsyncConfig.java:10-21`: Define um `ThreadPoolTaskExecutor` com fila em memória limitada a 100 itens (`queueCapacity=100`) e política de rejeição padrão.
- **Por que é um problema (Visão Sênior / Mentoria)**:
  1. **Commit Prematuro de Offset**: O Spring Kafka gerencia o commit do offset. Quando o método `consumirEvento` despacha a tarefa para a thread secundária e retorna `void`, o container do Spring Kafka interpreta que a mensagem foi processada com sucesso e **comita o offset imediatamente no broker**.
  2. **Perda Permanente de Mensagens**: Se a aplicação reiniciar durante um deploy, sofrer shutdown forçado no Kubernetes ou estourar a memória (OOM), todas as tarefas enfileiradas na memória do pool (até 100) são descartadas. Como o offset já foi comitado, essas notas fiscais **nunca mais serão lidas pelo Kafka**.
  3. **Neutralização de Retry e DLT**: Exceções lançadas na thread assíncrona não sobem para o listener do Kafka, desativando completamente os mecanismos de Dead Letter Topic e recuperação de falhas do Spring Kafka.
  4. **Quebra da Ordenação por Partição**: O paralelismo de threads em memória quebra a garantia de ordem estrita por partição que o Kafka oferece nativamente.
- **Direção de Correção Recomendada**:
  - **Eliminar completamente o `@Async` do fluxo do Kafka**. O processamento deve ocorrer de forma síncrona dentro da thread do listener.
  - Utilizar o paralelismo nativo do Kafka via concorrência de partições (`spring.kafka.listener.concurrency=3`).
  - Habilitar o modo de confirmação explícito (`AckMode.MANUAL_IMMEDIATE` ou `RECORD`):
  ```java
  @KafkaListener(topics = "job-created", groupId = "jobs-consumer-group")
  public void onMessage(@Payload JobCreatedEvent event, Acknowledgment ack) {
      processJobUseCase.execute(event.id());
      if (ack != null) ack.acknowledge(); // Confirma offset apenas após persistência com sucesso
  }
  ```

---

#### 2. Dual-Write Hazard e Falha Silenciosa no Producer (`jobs-api`)
- **O que está no código atual**:
  - `JobService.java:27-39`: O método `scheduleNFsProcessing` salva o job no banco (`repository.save(job)`) e logo em seguida chama `publisher.publish(event)` sem nenhuma anotação `@Transactional` ou garantia de atomicidade.
  - `KafkaJobEventPublisher.java:26-43`: O envio usa `kafkaTemplate.send()` assíncrono. Em caso de falha de conexão com o Kafka, o erro cai no callback `whenComplete` ou no `catch`, onde é apenas logado com `log.error` e **completamente engolido**.
- **Por que é um problema**:
  - Se a inserção no banco funcionar mas a comunicação com o Kafka falhar, o job ficará eternamente no PostgreSQL como `PENDING` sem nunca ser processado.
  - O cliente HTTP recebe a resposta de sucesso `202 Accepted` garantindo o processamento de uma nota que, na realidade, foi perdida no broker.
- **Direção de Correção Recomendada**:
  - Implementar o padrão **Transactional Outbox Pattern**: salvar o registro do `Job` e o evento em uma tabela `outbox_events` dentro da **mesma transação ACID relacional**. Um processo assíncrono de relay (Debezium CDC ou poller agendado com Spring Modulith) lê a outbox e publica no Kafka com garantia *at-least-once*.
  - Como mitigação imediata (se não usar Outbox): sincronizar o envio com `.get(5, TimeUnit.SECONDS)` e propagar a exceção para retornar `HTTP 500/503` em vez de `202 Accepted`.

---

#### 3. Descarte Total do Campo `errors` no Mapeamento JPA e Repositório
- **O que está no código atual**:
  - No domínio, `Job.java:21-22` e `Job.java:46-52` (`markAsFailed`) mantêm uma lista `List<String> errors`.
  - Na entidade JPA `Nfsejob.java` e nos adaptadores `PostgresJobRepository.java:22-49` de ambos os módulos, **o atributo `errors` não existe e não é mapeado**.
- **Por que é um problema**:
  - Quando um job falha durante a validação ou rejeição na SEFAZ, o status é alterado para `FAILED`, mas **o motivo da falha é permanentemente descartado**. Operadores de suporte e clientes da API não terão como descobrir a causa do erro pelo banco de dados.
- **Direção de Correção Recomendada**:
  - Adicionar o mapeamento de erros na entidade JPA (via `@ElementCollection` com tabela associativa `nfse_job_errors` ou coluna `TEXT`/`JSONB` `last_error`):
  ```java
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "nfse_job_errors", joinColumns = @JoinColumn(name = "job_id"))
  @Column(name = "error_message", columnDefinition = "TEXT")
  private List<String> errors = new ArrayList<>();
  ```

---

#### 4. Conflito Crítico de Drivers de Banco de Dados (URL H2 sem Driver no Classpath)
- **O que está no código atual**:
  - `jobs-api/src/main/resources/application.properties:5-7` e `jobs-consumer/src/main/resources/application.properties:5-7` configuram:
    `spring.datasource.url=jdbc:h2:mem:jobsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`.
  - Em ambos os arquivos `pom.xml`, a dependência do driver H2 (`com.h2database:h2`) **não existe** (apenas `org.postgresql:postgresql` está presente).
- **Por que é um problema**:
  - Executar a aplicação fora do Docker (na IDE ou via `mvn spring-boot:run`) resulta em falha fatal na inicialização: `ClassNotFoundException: org.h2.Driver` ou `Cannot load driver class: org.h2.Driver`.
- **Direção de Correção Recomendada**:
  - Padronizar a URL padrão para PostgreSQL local e criar perfis claros do Spring (`application-local.properties`, `application-test.properties`):
  ```properties
  spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/jobsdb}
  spring.datasource.username=${SPRING_DATASOURCE_USERNAME:postgres}
  spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:postgres}
  ```

---

#### 5. Versão Inexistente do Spring Boot (`4.1.1`) e Starters Corrompidos no POM
- **O que está no código atual**:
  - `pom.xml:24` (raiz): `<spring-boot.version>4.1.1</spring-boot.version>`.
  - `jobs-api/pom.xml:45-47`: Dependência inexistente `spring-boot-starter-kafka`.
  - `jobs-api/pom.xml:66-69`: Dependência inexistente `spring-boot-starter-webmvc-test` (e `spring-boot-starter-test` ausente).
- **Por que é um problema**:
  - O Spring Boot 4.x não existe no ecossistema oficial. Declarar versões e starters fictícios quebra a resolução de dependências do Maven, gerando falhas de compilação ou conflitos binários imprevisíveis.
- **Direção de Correção Recomendada**:
  - Atualizar para a versão estável oficial (ex: `3.4.3` ou `3.3.x`).
  - Corrigir os nomes dos artefatos: `spring-kafka` (groupId `org.springframework.kafka`) e `spring-boot-starter-test`.

---

#### 6. Ausência Física do Módulo `jobs-shared` e Duplicação Massiva de Código
- **O que está no código atual**:
  - O `pom.xml` da raiz declara `jobs-shared` no `dependencyManagement`, mas o módulo físico **não existe** no disco.
  - Como consequência, as classes `Job`, `JobStatus`, `JobCreatedEvent`, `Nfsejob`, `PostgresJobRepository`, `PostgresJpaRepository` e `JacksonConfig` foram clonadas integralmente em ambos os módulos.
- **Por que é um problema**:
  - Duplicação de regras de negócio, quebra de contratos em tempo de execução (*Schema Drift*) e manutenção duplicada.
- **Direção de Correção Recomendada**:
  - Criar o módulo físico `jobs-shared` no repositório e centralizar os contratos de eventos (`JobCreatedEvent`), enums de domínio (`JobStatus`) e tipos compartilhados.

---

#### 7. Pipeline de CI/CD do GitHub Actions Quebrado com Java 11
- **O que está no código atual**:
  - `.github/workflows/maven-publish.yml:20-29` configura `java-version: '11'`, enquanto o projeto exige Java 21 (`<java.version>21</java.version>`).
  - O workflow é disparado apenas em criação de releases (`release: [created]`), ignorando commits em `push` e `pull_request`.
- **Por que é um problema**:
  - O CI falha imediatamente ao tentar compilar recursos do Java 21 em um JDK 11: `Fatal error compiling: invalid target release: 21`. O código fica sem nenhuma verificação contínua em PRs.
- **Direção de Correção Recomendada**:
  - Atualizar `java-version: '21'` e adicionar gatilhos para `on: [push, pull_request]`.

---

### 🟡 Problemas Importantes (Nível 2 — Violações Arquiteturais, Fragilidades de Segurança e Débitos de Qualidade)

---

#### 8. Actuator e Métricas Fantasmas no `jobs-consumer` (Porta 8081 Inoperante)
- **O que está no código atual**:
  - `jobs-consumer/pom.xml:29-38` inclui `spring-boot-starter-actuator`, mas **NÃO inclui** `spring-boot-starter-web`.
  - `application.properties:20-22` e `compose.yaml:34` configuram `server.port=8081` e expõem a porta.
- **Por que é um problema**:
  - Sem um starter web no classpath, o Spring Boot inicializa em modo headless (`WebApplicationType.NONE`). Nenhuma porta HTTP é aberta. As sondas de saúde do Kubernetes (*Liveness/Readiness Probes*) falharão com conexão recusada, matando o container em loop infinito (*CrashLoopBackOff*).
- **Direção de Correção Recomendada**:
  - Adicionar `spring-boot-starter-web` ao `jobs-consumer/pom.xml` para habilitar a exposição das métricas Prometheus e health checks do Actuator.

---

#### 9. Vácuo Absoluto de Cobertura de Testes Automatizados
- **O que está no código atual**:
  - No `jobs-consumer`: O diretório `src/test` **não existe** (0% de cobertura).
  - No `jobs-api`: `JobServiceTest.java` possui **0 bytes** (arquivo vazio) e apenas um teste simples de controller existe.
  - Dependências de Testcontainers estão declaradas nos POMs, mas nunca são importadas por nenhum teste.
- **Por que é um problema**:
  - Falta total de confiabilidade. Qualquer refatoração nas regras de negócio, serialização ou queries de banco pode introduzir bugs graves sem que a suíte automatizada detecte.
- **Direção de Correção Recomendada**:
  - Criar testes unitários para as entidades de domínio e services com JUnit 5 + Mockito.
  - Criar testes de integração com `@Testcontainers` utilizando instâncias reais de PostgreSQL e Kafka para validar o fluxo ponta a ponta.

---

#### 10. Ausência de Inbound Ports e Modelo de Domínio Anêmico
- **O que está no código atual**:
  - `JobController` e `JobEventoConsumer` injetam diretamente as classes concretas `JobService` e `JobProcessingService`.
  - A entidade `Job` possui anotações do jMolecules, mas se comporta como uma estrutura de dados anêmica (getters/setters/builder público sem proteção de invariantes de negócio).
- **Por que é um problema**:
  - Viola a inversão de dependência da Arquitetura Hexagonal. Impede a interceptação de casos de uso por decorators e permite que transições de estado inválidas sejam executadas sem validação de domínio.
- **Direção de Correção Recomendada**:
  - Criar interfaces de casos de uso: `ScheduleJobUseCase` na API e `ProcessJobUseCase` no Consumer.
  - Enriquecer o Aggregate Root `Job` com métodos que protejam transições de estado (`markAsProcessing()`, `complete()`, `markAsFailed()`).

---

#### 11. Ausência de DTOs Tipados, Bean Validation e Vazamento de Informações (CWE-209)
- **O que está no código atual**:
  - `JobController.java:25-36` recebe `byte[] rawPayLoad` genérico e não utiliza validações Jakarta (`@Valid`, `@NotNull`).
  - Respostas HTTP retornam `ResponseEntity<Map<String, Object>>` em vez de records tipados.
  - O controller captura `Exception` genérica e concatena `"Failed to process payload: " + e.getMessage()`.
- **Por que é um problema**:
  - A API aceita JSONs inválidos na borda, sobrecarregando o Kafka e os workers.
  - Concatenar mensagens de erro brutas expõe nomes de tabelas, SQLs e detalhes de infraestrutura para clientes externos (**CWE-209**).
- **Direção de Correção Recomendada**:
  - Criar DTOs tipados com validação: `record ScheduleJobRequest(...)`.
  - Implementar um `@RestControllerAdvice` retornando **RFC 7807 (ProblemDetail)** para padronizar erros sem expor detalhes internos.

---

#### 12. Ausência de Migrações de Banco de Dados (Flyway) e Uso Inseguro de `ddl-auto=update`
- **O que está no código atual**:
  - `application.properties` define `spring.jpa.hibernate.ddl-auto=update`. Não há nenhum script SQL versionado no repositório.
- **Por que é um problema**:
  - O Hibernate nunca deleta colunas antigas, não renomeia campos com segurança e gera concorrência imprevisível de DDL na inicialização de réplicas em produção.
- **Direção de Correção Recomendada**:
  - Desativar `ddl-auto` (`validate` ou `none`) e introduzir o **Flyway** (`flyway-core` + `flyway-database-postgresql`) com scripts versionados em `src/main/resources/db/migration/`.

---

#### 13. Ausência de Controle de Concorrência Otimista (`@Version`)
- **O que está no código atual**:
  - A entidade JPA `Nfsejob` não possui atributo anotado com `@Version`.
- **Por que é um problema**:
  - Em um ambiente distribuído onde workers consom mensagens concorrentemente ou realizam retentativas, atualizações simultâneas no mesmo Job sofrem de **Lost Updates** (uma transação sobrescreve o estado da outra silenciosamente).
- **Direção de Correção Recomendada**:
  - Incluir `@Version private Long version;` na entidade JPA e no modelo de domínio.

---

### 🟢 Melhorias e Boas Práticas (Nível 3 — Clean Code, Nomenclatura e Infraestrutura)

---

#### 14. Inconsistências Linguísticas (Mistura PT/EN), Erros de Grafia e Casing
- **O que está no código atual**:
  - Mistura de português e inglês: `finalizar()` vs `markAsFailed()`, `consumirEvento()` vs `onMessage()`, `doProcessamento()`, mensagens de log em português em classes com nomes em inglês.
  - Erro ortográfico no pacote: `...infra.database.postgresql.persistance` (com "a" em vez de "e").
  - Violação de PascalCase na classe JPA `Nfsejob` (em vez de `NfseJob` ou `JobJpaEntity`).
- **Por que é um problema**:
  - Viola as convenções da linguagem Java, dificulta a indexação de logs em sistemas centralizados (Datadog/ElasticSearch) e gera ruído cognitivo para o time.
- **Direção de Correção Recomendada**:
  - Padronizar 100% do código, métodos, logs e pacotes em **Inglês**, reservando o Português apenas para termos estritos de negócio (como a sigla `Nfse`).

---

#### 15. Integração Pendente do Cassandra e Manifests K8s Incompletos
- **O que está no código atual**:
  - O diretório `k8s/` possui manifests para `jobs-api`, `kafka` e `cassandra.yaml`, mas **não possui deployment para o `jobs-consumer`** nem para o PostgreSQL.
  - O `cassandra.yaml` está presente no K8s, mas a integração no código Java (Spring Data Cassandra, repositório `NfseDocumentoRepository`) ainda não foi implementada.
  - O script `scripts/start-env.sh` tenta executar comandos `cqlsh` para criar o keyspace, mas depende de um container Cassandra no Docker Compose que foi removido.
- **Por que é um problema**:
  - A estratégia Dual Database (PostgreSQL + Cassandra) é parte essencial da arquitetura planejada, mas está incompleta: o K8s e o script estão prontos, o código não. Isso cria uma desconexão entre infra e aplicação.
- **Direção de Correção Recomendada**:
  - Adicionar o Cassandra ao `compose.yaml` para desenvolvimento local.
  - Implementar `NfseDocumentoRepository` com Spring Data Cassandra no `jobs-consumer`.
  - Criar `k8s/consumer-deployment.yaml` e `k8s/postgres-deployment.yaml`.
  - Atualizar `scripts/start-env.sh` para alinhar com o `compose.yaml` atual.

---

#### 16. Sobrescrita Destrutiva da Auto-Configuração do `ObjectMapper`
- **O que está no código atual**:
  - `JacksonConfig.java:10-18` declara `@Bean public ObjectMapper objectMapper() { return new ObjectMapper()... }`.
- **Por que é um problema**:
  - Instanciar manualmente `new ObjectMapper()` desativa a auto-configuração do Spring Boot (`JacksonAutoConfiguration`), descartando configurações do `application.properties` e módulos descobertos no classpath.
- **Direção de Correção Recomendada**:
  - Utilizar `Jackson2ObjectMapperBuilderCustomizer` para customizar o bean sem desativar a auto-configuração nativa do Spring.

---

#### 17. Fragilidade no Filtro de Tamanho de Payload (`PayloadSizeFilter`)
- **O que está no código atual**:
  - `PayloadSizeFilter.java:11-26` lê o header `request.getContentLengthLong()` e compara com `102400L` fixo no código.
- **Por que é um problema**:
  - Se o cliente enviar uma requisição com `Transfer-Encoding: chunked` ou sem o cabeçalho `Content-Length`, o valor lido é `-1` e o filtro é **completamente burlado**, permitindo payloads arbitrários.
- **Direção de Correção Recomendada**:
  - Utilizar as propriedades nativas do container web (`server.tomcat.max-http-form-post-size=10MB`) ou envelopar o `InputStream` com `BoundedInputStream`.

---

## 8. Roadmap de Evolução Técnica (Nível Pleno → Sênior)

Este roadmap organiza as melhorias em uma sequência pedagógica de 4 fases incrementais, transformando a POC de estudo em uma plataforma de missão crítica pronta para produção corporativa:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                             ROADMAP DE EVOLUÇÃO TÉCNICA (PLENO → SÊNIOR)                         │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘

   FASE 1: Integridade de Dados & Estabilização de Build
   ├── Corrigir Root POM (Spring Boot estável 3.4.x) e CI/CD para Java 21
   ├── Remover @Async do Kafka Consumer; configurar ACK manual e concorrência por partição
   ├── Adicionar mapeamento do campo 'errors' no JPA e repositório PostgreSQL
   └── Implementar Transactional Outbox Pattern no jobs-api para eliminar o Dual-Write

   FASE 2: Arquitetura Hexagonal Rigorosa & Qualidade de Código
   ├── Criar o módulo físico 'jobs-shared' para centralizar eventos e enums
   ├── Introduzir Inbound Ports formais (ScheduleJobUseCase, ProcessJobUseCase)
   ├── Enriquecer o Aggregate Root 'Job' com transições protegidas e Value Objects
   └── Implementar DTOs tipados com Jakarta Bean Validation e RFC 7807 ProblemDetail

   FASE 3: Observabilidade, Infraestrutura & Integração Cassandra
   ├── Introduzir Flyway para versionamento seguro do banco de dados relacional
   ├── Adicionar Spring Web no consumer para expor métricas Prometheus e Actuator na porta 8081
   ├── Integrar Apache Cassandra ao compose.yaml e implementar NfseDocumentoRepository (Spring Data Cassandra)
   ├── Implementar persistência de XML DPS, XML autorizado e PDF DANFSE no Cassandra após emissão
   ├── Implementar rastreabilidade distribuída (Distributed Tracing com OpenTelemetry e Correlation-Id)
   └── Atualizar manifests Kubernetes (Consumer Deployment, PostgreSQL StatefulSet, Health Probes)

   FASE 4: Resiliência Avançada & Escalabilidade de Alto Nível
   ├── Integrar Resilience4j (Circuit Breaker, Rate Limiter e Timeouts na emissão fiscal)
   ├── Configurar Retry Topics com Backoff Exponencial e Dead Letter Topic (DLT)
   ├── Implementar controle de Idempotência Distribuída no consumidor com chave de acesso
   └── Estabelecer testes automatizados de governança arquitetural com ArchUnit
```

---

## Conclusão e Filosofia de Engenharia

O projeto **Jobs — NFS-e Emission Pipeline** reúne conceitos modernos essenciais para a formação de um engenheiro de software sênior: desacoplamento por eventos, isolamento de domínio via Arquitetura Hexagonal e processamento assíncrono resiliente. 

A transição de um desenvolvedor Pleno para Sênior reside no entendimento profundo dos **modos de falha distribuídos**: reconhecer que em sistemas reais a rede oscila, brokers falham, prefeituras ficam fora do ar e a integridade dos dados deve ser preservada em cada transição de estado.
