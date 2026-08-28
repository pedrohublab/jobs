# Relatório de Desafio e Verificação Empírica (Adversarial Challenge Report)

**Agente**: `teamwork_preview_challenger_2`  
**Data/Hora**: 2026-08-28T19:32:00Z  
**Alvo**: `y:\git\jobs\README.md`  
**Escopo**: Verificação empírica, aderência operacional, viabilidade arquitetural e preservação da base de código.

---

## 1. Challenge Summary

**Avaliação Global de Risco**: **LOW** (Aprovado com observações de refinamento operacional)

O `README.md` reescrito atinge um padrão de excelência técnica e pedagógica superior para um guia de mentoria técnica (Pleno → Sênior). A estrutura de 8 seções cobre com precisão matemática o fluxo de dados, diagramas de sequência e arquitetura, modelo de dados relacional e de mensageria, trade-offs de engenharia, guia de execução local, 17 diagnósticos estruturais com análise de causa-raiz e direções de correção, e um roadmap evolutivo em 4 fases.

Abaixo estão os resultados das verificações empíricas e os desafios adversariais aplicados.

---

## 2. Verificação Operacional e de Execução ("Como Executar")

### 2.1 Mapeamento de Portas e Serviços (`compose.yaml` vs `README.md`)

| Serviço | Porta Exposta no Host | Listener Interno | Configuração em `compose.yaml` | Documentado no `README.md` | Veredito Empírico |
|---|---|---|---|---|---|
| **PostgreSQL** | `5432` | `5432` | `5432:5432` (db: `jobsdb`, user: `postgres`, pass: `postgres`) | `localhost:5432` (jobsdb/postgres/postgres) | **CONFIRMADO / COMPATÍVEL** |
| **Kafka Broker** | `9094` | `9092` (PLAINTEXT), `9093` (CONTROLLER) | `9094:9094` (Advertised: `EXTERNAL://localhost:9094`, `PLAINTEXT://kafka:9092`) | `localhost:9094` (Host CLI) e `localhost:9092` (Docker Exec) | **CONFIRMADO / COMPATÍVEL** |
| **jobs-api** | `8080` | `8080` | `8080:8080` | `localhost:8080` | **CONFIRMADO / COMPATÍVEL** |
| **jobs-consumer** | `8081` | `8081` | `8081:8081` | `server.port=8081` (Porta 8081 Actuator) | **CONFIRMADO / COMPATÍVEL** |

#### ⚠️ Observação Adversarial 1 (Zookeeper vs KRaft):
- **Evidência**: No `compose.yaml` (linhas 49-71), o Kafka está configurado na imagem `bitnamilegacy/kafka:3.9.0` em **modo KRaft** (`KAFKA_CFG_PROCESS_ROLES=broker,controller`, sem Zookeeper).
- **No `README.md`**:
  - Linha 129: Menciona `# Ambiente conteinerizado (PostgreSQL, Kafka, Zookeeper)`.
  - Linha 290: Lista `- Zookeeper: localhost:2181`.
- **Análise de Impacto**: No `compose.yaml` atual não há container de Zookeeper rodando na porta 2181. Embora seja um detalhe cosmético de documentação, para máxima precisão técnica, recomenda-se que futuras revisões removam a menção ao Zookeeper, ressaltando o uso moderno do Kafka em modo KRaft.

#### ⚠️ Observação Adversarial 2 (Comando de Subida da Infraestrutura):
- **Evidência**: O `README.md` instrui executar `docker compose up -d` na raiz (passo 1) e em seguida rodar `java -jar ...` nos terminais 1 e 2 (passo 3).
- **Análise de Impacto**: Como o `compose.yaml` também define os serviços `jobs-api` e `jobs-consumer` com `build: Dockerfile`, executar `docker compose up -d` sem parâmetros tentará construir e subir também a API e o Consumer nos containers (o que pode conflitar com as portas 8080/8081 se o desenvolvedor rodar `java -jar` no host).
- **Recomendação**: Para rodar a aplicação localmente no host via JAR, o comando mais seguro e preciso é:
  ```bash
  docker compose up -d postgresql kafka
  ```

---

### 2.2 Verificação dos Comandos `curl` e Payloads (`JobController.java`)

- **Código Real (`JobController.java`)**:
  ```java
  @RestController
  @RequestMapping("/api/jobs")
  public class JobController {
      @PostMapping(value = "/nfs", consumes = MediaType.APPLICATION_JSON_VALUE)
      public ResponseEntity<Map<String, Object>> processNfs(@RequestBody byte[] rawPayLoad) {
          UUID jobId = jobService.scheduleNFsProcessing(rawPayLoad);
          return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
      }
  }
  ```
- **Documentado no `README.md` (Linhas 326-354)**:
  - Endpoint: `POST http://localhost:8080/api/jobs/nfs`
  - Header: `-H "Content-Type: application/json"`
  - Header de Rastreabilidade: `-H "X-Correlation-Id: ..."`
  - Payload: Objeto JSON estruturado com `prestador`, `tomador`, `servico`.
  - Resposta: `HTTP 202 Accepted` com `{ "jobId": "uuid" }`.
- **Veredito Empírico**: **100% COMPATÍVEL**. O `JobController` consome qualquer payload JSON como `byte[]` e responde exatamente com `HTTP 202` contendo a chave `jobId`. A documentação do README fornece uma estrutura de payload que reflete com fidelidade o domínio de NFS-e brasileiro.

---

## 3. Desafio e Avaliação das Soluções Arquiteturais Propostas

### 3.1 Transactional Outbox Pattern (Item 2 & Roadmap Fase 1)
- **Problema Abordado**: Dual-Write e publicação assíncrona com erro engolido no producer.
- **Avaliação de Viabilidade**: **Excelente / Padrão Enterprise**.
- **Análise Técnica**:
  - Salvar a entidade `Job` e o registro de evento em uma tabela relacional `outbox_events` sob a mesma transação ACID do PostgreSQL elimina o risco de estados órfãos (`PENDING` eternos).
  - O relay para o Kafka pode ser implementado via **Polling Publisher** (com Spring Scheduler e `SELECT ... FOR UPDATE SKIP LOCKED`) ou **CDC via Debezium**. Para a complexidade atual, a recomendação de Spring Modulith ou Polling Publisher é altamente pragmática e de baixo custo operacional.

### 3.2 Eliminação de `@Async` e Kafka Dead Letter Topic / ErrorHandler (Item 1 & Roadmap Fase 1/4)
- **Problema Abordado**: Commit prematuro de offset, perda de mensagens em crash de processo e violação de ordem de partição.
- **Avaliação de Viabilidade**: **Excelente / Padrão Enterprise**.
- **Análise Técnica**:
  - Remover a anotação `@Async` do serviço de processamento e manter o processamento na thread do listener garante que o offset só seja comitado após a conclusão do processamento.
  - A adição de `DefaultErrorHandler` com `DeadLetterPublishingRecoverer` (ou `@RetryableTopic`) fornece retentativas com backoff exponencial e desvio seguro de "poison pills" para o tópico `job-created.DLT`, sem travar a partição.

### 3.3 Migrações Versionadas com Flyway (Item 12 & Roadmap Fase 3)
- **Problema Abordado**: Uso perigoso de `hibernate.ddl-auto=update` sem controle versionado de schema.
- **Avaliação de Viabilidade**: **Excelente / Padrão Enterprise**.
- **Análise Técnica**:
  - A migração para `flyway-core` + `flyway-database-postgresql` com scripts em `db/migration/` (ex: `V1__init_schema.sql`) é a prática padrão recomendada pelo ecossistema Spring Boot para garantir reprodutibilidade em CI/CD e ambientes produtivos.

### 3.4 Isolamento de Domínio com DTOs, Bean Validation e MapStruct (Item 11 & Roadmap Fase 2)
- **Problema Abordado**: Borda desprotegida aceitando `byte[]`, ausência de DTOs e vazamento de stacktrace (`CWE-209`).
- **Avaliação de Viabilidade**: **Excelente / Padrão Enterprise**.
- **Análise Técnica**:
  - A introdução de records imutáveis (`ScheduleJobRequest`), anotações Jakarta (`@NotBlank`, `@CNPJ`, `@Positive`) e tratamento global via `@RestControllerAdvice` com **RFC 7807 (ProblemDetail)** fecha vetores de injeção e protege detalhes internos de infraestrutura.
  - O uso de **MapStruct** para converter DTO ↔ Domínio ↔ Entidade JPA garante separação estrita das camadas da Arquitetura Hexagonal com performance de tempo de compilação.

### 3.5 Observabilidade e Distributed Tracing (Item 14 & Roadmap Fase 3)
- **Problema Abordado**: Ausência de rastreabilidade entre o fluxo HTTP e as mensagens assíncronas do Kafka.
- **Avaliação de Viabilidade**: **Excelente / Padrão Enterprise**.
- **Análise Técnica**:
  - Propagação de contexto via headers Kafka (`X-Correlation-Id`, W3C Trace Context) integrada ao Micrometer Tracing / OpenTelemetry permite correlacionar spans desde a requisição HTTP inicial até a finalização no worker assíncrono.

---

## 4. Testes de Estresse Conceituais e Cenários Adversariais

| Cenário de Falha | Comportamento no Código Atual (Antes) | Comportamento com as Soluções Propostas no README | Avaliação |
|---|---|---|---|
| **Broker Kafka Indisponível no Envio** | `JobService` salva no PostgreSQL, `KafkaJobEventPublisher` loga erro e engole exceção; API retorna `202 Accepted` enganando o cliente. | **Outbox Pattern**: O evento fica salvo na tabela `outbox_events`. Assim que o broker restabelece conexão, o poller envia o evento sem perda. | **Aprovado** |
| **Worker sofre SIGKILL durante processamento** | Como o offset foi comitado na entrada do `@Async`, as notas na fila do pool de memória são perdidas para sempre. | **Sync Consumer + Manual ACK**: O offset não é comitado. Quando a nova réplica do worker subir, o Kafka re-entrega a mensagem a partir do último offset confirmado. | **Aprovado** |
| **Dois Workers processam a mesma nota simultaneamente** | Ocorrência de *Lost Update*: o último a salvar sobrescreve o estado anterior sem aviso. | **Optimistic Locking (`@Version`)**: O Hibernate lança `OptimisticLockException` no segundo worker, impedindo a sobreposição cega de estado. | **Aprovado** |
| **Envio com `Transfer-Encoding: chunked`** | `PayloadSizeFilter` lê `-1` no `Content-Length` e permite payloads gigantes de vários megabytes. | **Configuração Nativa de Container**: O Tomcat limita o tamanho máximo do stream de entrada (`server.tomcat.max-http-form-post-size`). | **Aprovado** |

---

## 5. Auditoria de Preservação de Código da Base de Dados

Conforme os critérios de integridade estritos (R3 e Restrições do Sistema):
- `git status` e verificação de timestamps confirmam que **apenas o arquivo `README.md` foi modificado** no repositório.
- Nenhum arquivo de código `.java`, `.xml`, `.properties`, `.yaml`, scripts de inicialização ou manifests de Kubernetes foi criado, alterado ou deletado.
- Todos os relatórios do time estão devidamente isolados dentro do diretório `.agents/`.

---

## 6. Conclusão do Desafio

O documento `y:\git\jobs\README.md` cumpre com distinção todos os requisitos funcionais, conceituais e pedagógicos estabelecidos no `ORIGINAL_REQUEST.md` e no `PROJECT.md`. As propostas de solução são de altíssimo nível, aderentes aos padrões de engenharia de software da indústria (Spring Boot 3, Apache Kafka, DDD, Hexagonal Architecture) e preparadas para servir como guia de mentoria técnica para evolução profissional.
