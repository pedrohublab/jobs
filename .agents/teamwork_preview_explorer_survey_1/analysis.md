# Análise Estrutural e Arquitetural Profunda: Módulo `jobs-api`
**Projeto**: Jobs — NFS-e Emission Pipeline  
**Módulo**: `jobs-api`  
**Autor**: Teamwork Preview Explorer (Survey 1)  
**Data**: 2026-08-28  
**Tom/Formato**: Mentoria Técnica Sênior / Parecer Arquitetural  

---

## 1. Sumário Executivo & Visão Geral do Módulo

O módulo `jobs-api` tem a responsabilidade de ser a **porta de entrada REST (Driving Adapter)** do pipeline assíncrono de emissão de NFS-e (Notas Fiscais de Serviço Eletrônicas). Seu objetivo conceitual é:
1. Receber requisições HTTP contendo lotes/dados de NFS-e;
2. Validar o payload e instanciar um Job no domínio;
3. Persistir o estado inicial (`PENDING`) no banco de dados relacional (PostgreSQL);
4. Publicar um evento (`JobCreatedEvent`) no tópico Kafka `job-created` para processamento assíncrono pelo `jobs-consumer`;
5. Retornar uma resposta imediata (`202 Accepted`) com o identificador (`jobId`) para acompanhamento assíncrono.

Embora o módulo apresente a intenção de adotar **Arquitetura Hexagonal (Ports & Adapters)** e **Domain-Driven Design (DDD)** com Spring Boot e Java 21, a análise profunda revelou **inconsistências arquiteturais graves, riscos críticos de perda de dados e falhas de confiabilidade distribuída (Dual-Write Problem)**, além de dependências/configurações corrompidas e ausência quase total de testes.

Abaixo segue o diagnóstico detalhado com fundamentação teórica, impacto operacional e direções de correção recomendadas.

---

## 2. Diagnóstico Detalhado por Eixo Arquitetural

---

### Eixo 1: Organização de Pacotes, Arquitetura Hexagonal e DDD

#### Problema 1.1: Ausência de Portas de Entrada (Driving Ports / Use Cases) e Acoplamento no Application Service
- **O que está errado**:
  O `JobController` (`hub.pedro.jobs.api.web.api.in.JobController:19`) injeta diretamente a classe concreta `JobService` (`hub.pedro.jobs.api.app.service.JobService`). Não existe uma interface de porta de entrada (`ScheduleJobUseCase` ou `CreateJobPort`) no pacote `app.port.in`.
- **Por que é um problema (Impacto Arquitetural)**:
  Na Arquitetura Hexagonal (Cockburn) e Clean Architecture (Martin), o núcleo da aplicação (Application Core) expõe **Portas de Entrada (Inbound/Driving Ports)** que definem os casos de uso do sistema como contratos puros. Os adaptadores de entrada (como Controllers REST, CLI ou consumidores gRPC) devem depender exclusivamente dessas interfaces de caso de uso. O acoplamento direto a uma classe de serviço concreta quebra a inversão de dependência na camada de aplicação, dificulta a aplicação de decorators (ex.: métricas, auditoria, validação cruzada) e mistura a definição do caso de uso com sua implementação.
- **Direção de Correção Recomendada**:
  1. Criar o pacote `app.port.in` contendo a interface:
     ```java
     package hub.pedro.jobs.api.app.port.in;
     
     public interface ScheduleJobUseCase {
         UUID scheduleJob(ScheduleJobCommand command);
     }
     ```
  2. Implementar essa interface na classe `JobService` (renomeada para `JobApplicationService` ou `ScheduleJobService`).
  3. No `JobController`, injetar a interface `ScheduleJobUseCase`.

---

#### Problema 1.2: Inconsistência Estrutural nos Pacotes de Domínio e Portas de Saída
- **O que está errado**:
  A interface `JobRepository` foi colocada no pacote `hub.pedro.jobs.api.domain.interfaces.JobRepository`, enquanto existe um diretório vazio `domain/repository`. Ao mesmo tempo, a porta do publicador de eventos foi colocada em `hub.pedro.jobs.api.app.port.out.JobEventPublisher`.
  Além disso, o pacote web foi nomeado como `hub.pedro.jobs.api.web.api.in`, misturando nomenclaturas (`web.api.in` vs `infra.kafka.publisher`).
- **Por que é um problema (Impacto de Manutenibilidade e DDD)**:
  - O pacote `domain.interfaces` é um antipadrão clássico (designação por tipo técnico "interfaces" em vez de agrupamento por responsabilidade de domínio ou semântica arquitetural).
  - Em DDD tático, repositórios de agregados pertencem ao domínio (`domain.repository` ou `domain.model.JobRepository`). Em Hexagonal estrito, são portas de saída (`app.port.out.JobRepositoryPort`). A coexistência de `domain.interfaces` com `app.port.out` demonstra falta de clareza sobre onde residem os contratos de saída.
  - A pasta vazia `domain/repository` indica refatoração incompleta.
  - O pacote `web.api.in` é redundante e confuso.
- **Direção de Correção Recomendada**:
  Padronizar a estrutura hexagonal de pacotes:
  ```text
  hub.pedro.jobs.api/
  ├── domain/
  │   ├── model/ (Job, JobId, JobStatus, JobPayload)
  │   └── repository/ (JobRepository - contrato de domínio)
  ├── application/
  │   ├── port/
  │   │   ├── in/ (ScheduleJobUseCase, GetJobStatusQuery)
  │   │   └── out/ (JobEventPublisherPort)
  │   └── service/ (ScheduleJobService)
  ├── infrastructure/
  │   ├── adapter/
  │   │   ├── in/
  │   │   │   └── web/ (JobController, GlobalExceptionHandler, DTOs)
  │   │   └── out/
  │   │       ├── persistence/ (PostgresJobRepositoryAdapter, NfseJobJpaEntity, JpaRepository)
  │   │       └── messaging/ (KafkaJobEventPublisherAdapter)
  │   └── config/ (JacksonConfig, KafkaProducerConfig, WebConfig)
  ```

---

#### Problema 1.3: Modelo de Domínio Anêmico (Anemic Domain Model) e Falta de Value Objects
- **O que está errado**:
  A classe `Job` (`hub.pedro.jobs.api.domain.entity.Job`) é anotada com `@AggregateRoot` do jMolecules, mas se comporta como uma estrutura de dados anêmica:
  - Não possui métodos de negócio que expressem transições de estado ou invariantes (ex.: `startProcessing()`, `complete()`, `fail()`, `canRetry()`).
  - Utiliza tipos primitivos e genéricos: `id` é um `UUID` cru (sem Value Object `JobId`), `payload` é `String`/`byte[]` cru (sem `JobPayload`), `errors` é `List<String>` sem tipagem de erro.
  - A criação ocorre via static factory `createNewJob` e builder público que permite construir estados inconsistentes.
- **Por que é um problema (Impacto de Domínio e Encapsulamento)**:
  Um Aggregate Root deve garantir a integridade de suas invariantes em todas as mutações. Quando a entidade apenas expõe getters/setters/builders, a lógica de negócio acaba vazando para a camada de aplicação ou para os adaptadores. Se uma regra de transição de status (ex.: `PENDING -> PROCESSING -> DONE`) precisar ser aplicada, o modelo atual permite que qualquer classe altere os atributos sem validação.
- **Direção de Correção Recomendada**:
  Enriquecer o Aggregate Root `Job` com métodos que protejam invariantes de negócio e criar Value Objects para atributos semânticos:
  ```java
  public class Job {
      private final JobId id;
      private JobStatus status;
      private final JobPayload payload;
      private final Instant createdAt;
      private Instant updatedAt;
      private Instant finishedAt;
      private int attempts;
      private final List<JobExecutionError> errors;
      
      public static Job create(JobPayload payload) {
          Objects.requireNonNull(payload, "Payload is required");
          return new Job(JobId.generate(), JobStatus.PENDING, payload, Instant.now());
      }
      
      public void markAsProcessing() {
          if (this.status != JobStatus.PENDING) {
              throw new IllegalStateException("Only PENDING jobs can transition to PROCESSING");
          }
          this.status = JobStatus.PROCESSING;
          this.updatedAt = Instant.now();
      }
      // ... métodos de domínio ricos
  }
  ```

---

### Eixo 2: Mapeamento Domínio ↔ Persistência e Risco de Perda de Dados

#### Problema 2.1: Perda Total do Campo `errors` no Mapeamento JPA (Data Loss)
- **O que está errado**:
  No domínio, `Job.java:21` possui o atributo `private List<String> errors;`.  
  No entanto, na entidade JPA `Nfsejob.java` (`hub.pedro.jobs.api.infra.database.postgresql.persistance.Nfsejob`), **o atributo `errors` não existe**.  
  No adaptador `PostgresJobRepository.java:22-49`:
  - `save(Job job)` ignora solenemente `job.getErrors()`;
  - `findById(UUID id)` jamais popula a lista de erros no `Job.builder()`.
- **Por que é um problema (Impacto de Confiabilidade e Operação)**:
  **Perda irrecuperável de dados**. Quando um job falha ou acumula erros de validação e é persistido no banco de dados, todo o histórico de erros é descartado. Se um operador ou cliente consultar o job, será impossível diagnosticar o motivo da falha pelo banco PostgreSQL.
- **Direção de Correção Recomendada**:
  1. Adicionar o mapeamento na entidade JPA `NfseJobJpaEntity`:
     ```java
     @ElementCollection(fetch = FetchType.LAZY)
     @CollectionTable(name = "nfse_job_errors", joinColumns = @JoinColumn(name = "job_id"))
     @Column(name = "error_message", columnDefinition = "TEXT")
     private List<String> errors = new ArrayList<>();
     ```
  2. Mapear bidirecionalmente a lista no conversor/mapper de persistência.

---

#### Problema 2.2: Erro Ortográfico no Pacote (`persistance`) e Má Nomenclatura da Entidade JPA (`Nfsejob`)
- **O que está errado**:
  - O pacote está grafado como `hub.pedro.jobs.api.infra.database.postgresql.persistance` (com `a` em vez de `e` - `persistence`).
  - A classe chama-se `Nfsejob` (tudo em minúsculo após o prefixo, violando PascalCase).
  - Há inconsistência conceitual: o domínio fala em `Job`, a tabela em `nfse_job`, e a entidade em `Nfsejob`.
- **Por que é um problema (Legibilidade e Padrões de Código)**:
  Erros ortográficos em nomes de pacotes degradam a qualidade percebida do código, quebram convenções e complicam a busca e manutenção. A falta de PascalCase (`Nfsejob` vs `NfseJobEntity`) viola a convenção padrão Java (Oracle Code Conventions / Google Java Style Guide).
- **Direção de Correção Recomendada**:
  Renomear o pacote para `...infrastructure.adapter.out.persistence.postgresql` e a classe para `NfseJobJpaEntity` ou `JobJpaEntity`.

---

#### Problema 2.3: Mapeamento Manual Procedural em vez de MapStruct / Dedicated Mappers
- **O que está errado**:
  Em `PostgresJobRepository.java:22-49`, as conversões entre `Job` (domínio) e `Nfsejob` (JPA) são feitas manualmente por getters/setters imperativos espalhados dentro dos métodos de repositório. O MapStruct está declarado no POM pai (`pom.xml:65-74, 94-99`), mas não é utilizado no `jobs-api`.
- **Por que é um problema (Manutenibilidade e Propensão a Bugs)**:
  Mapeamento manual procedural é sujeito a falhas humanas sempre que novos campos são adicionados ao domínio (como ocorreu com o campo `errors`).
- **Direção de Correção Recomendada**:
  Implementar um mapper tipado (via MapStruct ou um `JobPersistenceMapper` com testes unitários dedicados).

---

#### Problema 2.4: Ausência de Controle de Concorrência Otimista (`@Version`) e Auditoria na Entidade JPA
- **O que está errado**:
  `Nfsejob` não possui atributo anotado com `@Version` (`private Long version;`) e utiliza `@PrePersist` / `@PreUpdate` manuais que sobrescrevem timestamps que deveriam ser determinados pelo domínio.
- **Por que é um problema (Concorrência e Integridade)**:
  Em um sistema distribuído onde a API cria o job e múltiplos consumidores podem atualizar seu status assincronamente (ex.: retry, cancelamento, processamento), a ausência de optimistic locking permite que atualizações concorrentes sofram **Lost Updates** (uma transação sobrescreve o estado da outra silenciosamente).
- **Direção de Correção Recomendada**:
  Incluir `@Version private Long version;` na entidade de banco e utilizar Spring Data JPA Auditing (`@EnableJpaAuditing`, `@CreatedDate`, `@LastModifiedDate`) ou sincronizar explicitamente com os timestamps calculados no Aggregate Root.

---

### Eixo 3: Confiabilidade Distribuída, Kafka Producer e o Problema do Dual-Write

#### Problema 3.1: O Problema do "Dual-Write" e Ausência de Transacionalidade (Risco Crítico)
- **O que está errado**:
  Em `JobService.java:27-39`:
  ```java
  public UUID scheduleNFsProcessing(byte[] payload) {
      Job job = Job.createNewJob(payload);
      repository.save(job); // 1. Salva no banco de dados relacional
      log.info("Job criado. id={} status={}", job.getId(), job.getStatus());

      JobCreatedEvent event = new JobCreatedEvent(
              job.getId(), job.getStatus(), payload, job.getCreatedAt().toString());

      publisher.publish(event); // 2. Publica no Kafka (sem garantia transacional atômica)

      log.info("Evento disparado para publicação no Kafka. id={}", job.getId());
      return job.getId();
  }
  ```
  O método **não possui `@Transactional`**, e mesmo que possuísse, banco de dados relacional e broker Kafka operam em contextos transacionais completamente separados (não há 2PC / XA distribuído viável).
- **Por que é um problema (Cenário de Falha em Produção)**:
  1. **Cenário A (Banco OK, Kafka Falha)**: O job é inserido no PostgreSQL com status `PENDING`. Em seguida, a chamada ao Kafka falha por timeout, partição sem líder ou indisponibilidade de rede. Resultado: O job fica eternamente com status `PENDING` no banco e nunca é processado.
  2. **Cenário B (Se houvesse `@Transactional` e commit falhasse)**: Se o evento for publicado no Kafka antes do commit do banco e o commit do banco falhar (ex.: violação de constraint, timeout de conexão JDBC), o consumidor receberá uma notificação de um `jobId` que não existe no PostgreSQL!
- **Direção de Correção Recomendada**:
  Adotar o padrão **Transactional Outbox Pattern**:
  1. Na mesma transação do banco relacional, persistir o `Job` e um registro na tabela `outbox_events`.
  2. Um processo assíncrono (como Debezium via CDC / Change Data Capture, ou um poller com Spring `@Scheduled` / Spring Modulith `@ApplicationModuleListener`) lê os eventos da tabela outbox e os publica confiavelmente no Kafka com garantia *at-least-once*.

---

#### Problema 3.2: Falha Silenciosa e Engolimento de Exceções no Publicador Kafka (Silent Failure)
- **O que está errado**:
  Em `KafkaJobEventPublisher.java:26-43`:
  ```java
  @Override
  public void publish(JobCreatedEvent job) {
      try {
          String payload = objectMapper.writeValueAsString(job);
          kafkaTemplate.send(TOPIC, job.id().toString(), payload)
                  .whenComplete((result, ex) -> {
                      if (ex != null) {
                          log.error("Falha ao publicar evento no Kafka. jobId={}", job.id(), ex);
                      } else {
                          log.info("Evento publicado...");
                      }
                  });
      } catch (Exception e) {
          log.error("Erro ao serializar ou disparar envio para o Kafka. jobId={}", job.id(), e);
      }
  }
  ```
- **Por que é um problema (Gravidade Operacional Máxima)**:
  - `kafkaTemplate.send(...)` é não-bloqueante (retorna `CompletableFuture`). O callback `whenComplete` executa em outra thread.
  - O método `publish` retorna `void` imediatamente para o `JobService`, que retorna o `jobId` para o `JobController`, que devolve `HTTP 202 Accepted` ao cliente HTTP.
  - Se a serialização JSON falhar ou se o envio ao Kafka falhar no callback, o erro é apenas registrado em log (`log.error`) e **completamente engolido**.
  - O cliente externo recebe uma resposta de sucesso (`202 Accepted`) com a promessa de que seu lote será processado, quando na verdade o evento foi perdido para sempre!
- **Direção de Correção Recomendada**:
  1. Se for adotada publicação síncrona sem Outbox: aguardar o retorno da Future com timeout explícito (`kafkaTemplate.send(...).get(5, TimeUnit.SECONDS)`) e propagar uma exceção de infraestrutura (`EventPublishingException`) para que o Controller responda `500/503` e não confirme o aceite.
  2. A solução ideal e definitiva é o Transactional Outbox (Problema 3.1).

---

#### Problema 3.3: Configurações de Resiliência do Produtor Kafka Ausentes
- **O que está errado**:
  No `application.properties:11-14`, as configurações do produtor Kafka limitam-se a:
  ```properties
  spring.kafka.bootstrap-servers=localhost:9094
  spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
  spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer
  ```
  Estão ausentes:
  - `acks` (padrão pode não ser `all`);
  - `retries` / `delivery.timeout.ms`;
  - `enable.idempotence=true`;
  - `max.in.flight.requests.per.connection`.
- **Por que é um problema (Perda e Reordenação de Mensagens)**:
  Sem `acks=all`, o broker pode confirmar o recebimento antes de replicar a mensagem para os réplicas em sincronia (ISRs), gerando perda de dados se o líder falhar. Sem idempotência (`enable.idempotence=true`), retentativas automáticas de rede podem duplicar mensagens ou alterar sua ordem na partição.
- **Direção de Correção Recomendada**:
  Configurar explicitamente o produtor Kafka para máxima confiabilidade:
  ```properties
  spring.kafka.producer.acks=all
  spring.kafka.producer.properties.enable.idempotence=true
  spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
  spring.kafka.producer.retries=Integer.MAX_VALUE
  spring.kafka.producer.properties.delivery.timeout.ms=120000
  ```

---

#### Problema 3.4: Contrato de Evento Pobre, Tópico Hardcoded e Serialização Inadequada
- **O que está errado**:
  - Em `JobCreatedEvent.java:5`: `public record JobCreatedEvent(UUID id, JobStatus status, byte[] payload, String createdAt)`:
    - `createdAt` é exposto como `String` (gerado por `job.getCreatedAt().toString()`) em vez de manter `Instant` tipado;
    - `payload` é `byte[]`, o que faz o Jackson serializá-lo como string Base64 no JSON resultante;
    - Não existem metadados essenciais de mensageria: `eventId`, `eventType`, `occurredAt`, `correlationId`/`traceId`, `schemaVersion`.
  - Em `KafkaJobEventPublisher.java:15`: `private static final String TOPIC = "job-created";` está fixo no código como constante estática privada.
- **Por que é um problema (Evolução de Schemas e Rastreabilidade Distribuída)**:
  - Em arquiteturas orientadas a eventos (EDA), eventos são contratos públicos. A ausência de metadados como `correlationId` impede o rastreamento ponta a ponta (distributed tracing via OpenTelemetry / Micrometer Tracing) entre a API e o Consumer.
  - Tópicos hardcoded impedem a parametrização por ambiente (ex.: `dev-job-created`, `prod-job-created`) e violam o princípio de configuração Twelve-Factor App (Config).
  - Serializar JSON via `KafkaTemplate<String, String>` com conversão manual no `ObjectMapper` ignora os serializadores nativos do Spring Kafka (`JsonSerializer<T>` ou Avro/Protobuf com Schema Registry).
- **Direção de Correção Recomendada**:
  1. Estruturar o evento como um Envelope de Mensageria com Metadados e Payload de Negócio.
  2. Parametrizar o tópico via `@Value("${app.kafka.topics.job-created:job-created}")` ou `@ConfigurationProperties`.
  3. Utilizar o `JsonSerializer` do Spring Kafka ou Schema Registry.

---

### Eixo 4: Configuração da Aplicação, Starters e Banco de Dados

#### Problema 4.1: Conflito Crítico de Driver de Banco de Dados (H2 vs PostgreSQL)
- **O que está errado**:
  No `application.properties:5-7`:
  ```properties
  spring.datasource.url=jdbc:h2:mem:jobsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
  spring.datasource.username=sa
  spring.datasource.password=
  ```
  No entanto, no `jobs-api/pom.xml:29-32`:
  ```xml
  <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
  </dependency>
  ```
  **A dependência `com.h2database:h2` NÃO existe no `pom.xml`!**
- **Por que é um problema (Falha Fatal de Inicialização)**:
  Ao iniciar a aplicação com a configuração atual, o Spring Boot tentará carregar o driver JDBC `org.h2.Driver` com base na URL `jdbc:h2:...`, mas falhará imediatamente com `java.lang.ClassNotFoundException: org.h2.Driver` / `Cannot load driver class: org.h2.Driver`. A aplicação **não sobe** sem que o driver H2 esteja no classpath ou sem que a URL seja alterada para um PostgreSQL real.
- **Direção de Correção Recomendada**:
  1. Para desenvolvimento local / testes: utilizar Testcontainers com PostgreSQL real ou definir perfis claros (`application-dev.yml` vs `application-test.yml`).
  2. No arquivo base, apontar para as variáveis de ambiente do PostgreSQL com valores padrão:
     ```properties
     spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/jobsdb}
     spring.datasource.username=${SPRING_DATASOURCE_USERNAME:postgres}
     spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:postgres}
     ```

---

#### Problema 4.2: Versão Fictícia/Inválida do Spring Boot no POM Pai (`4.1.1`)
- **O que está errado**:
  No `pom.xml:24` da raiz:
  ```xml
  <spring-boot.version>4.1.1</spring-boot.version>
  ```
- **Por que é um problema (Quebra de Ecossistema e Build)**:
  O Spring Boot 4.x não existe (a versão estável atual para Java 21 é Spring Boot 3.3.x / 3.4.x). O uso de uma versão inexistente pode causar falhas catastróficas de resolução de dependências no Maven ou comportamentos imprevisíveis caso seja resolvido por repositórios internos customizados.
- **Direção de Correção Recomendada**:
  Ajustar para a versão oficial mais recente e estável do Spring Boot (ex.: `3.4.3` ou `3.3.x`).

---

#### Problema 4.3: Artefatos de Dependência Inválidos ou Faltando no `pom.xml`
- **O que está errado**:
  No `jobs-api/pom.xml:45-47`:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-kafka</artifactId>
  </dependency>
  ```
  No `jobs-api/pom.xml:66-69`:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc-test</artifactId>
      <scope>test</scope>
  </dependency>
  ```
  E a dependência padrão `spring-boot-starter-test` está ausente!
- **Por que é um problema**:
  - `spring-boot-starter-kafka` não é o identificador padrão do starter do Spring para Kafka (`spring-kafka` com `groupId: org.springframework.kafka`).
  - `spring-boot-starter-webmvc-test` não é um starter oficial do Spring Boot (o pacote correto para testes é `spring-boot-starter-test`, que agrega JUnit 5, Mockito, AssertJ, Hamcrest e MockMvc).
- **Direção de Correção Recomendada**:
  Corrigir as dependências no `jobs-api/pom.xml`:
  ```xml
  <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
  </dependency>
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
  </dependency>
  ```

---

#### Problema 4.4: Uso de `ddl-auto=update` em vez de Ferramenta de Migração de Schema (Flyway / Liquibase)
- **O que está errado**:
  No `application.properties:8`:
  ```properties
  spring.jpa.hibernate.ddl-auto=update
  ```
- **Por que é um problema (Risco Operacional)**:
  `ddl-auto=update` do Hibernate nunca remove colunas excluídas, não altera tipos de dados de forma segura, gera locks automáticos imprevisíveis na inicialização da aplicação e não oferece controle de versionamento de banco de dados. Em produção, isso gera divergência de schemas entre instâncias de réplicas e ambientes (dev, staging, prod).
- **Direção de Correção Recomendada**:
  Desabilitar `ddl-auto` (`spring.jpa.hibernate.ddl-auto=validate` ou `none`) e introduzir o **Flyway** (`org.flywaydb:flyway-core` + `flyway-database-postgresql`) com scripts de migração versionados em `src/main/resources/db/migration/V1__create_tables.sql`.

---

#### Problema 4.5: Sobrescrita Global Agressiva do `ObjectMapper`
- **O que está errado**:
  Em `JacksonConfig.java:10-18`:
  ```java
  @Configuration
  public class JacksonConfig {
      @Bean
      public ObjectMapper objectMapper() {
          return new ObjectMapper()
                  .registerModule(new JavaTimeModule())
                  .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      }
  }
  ```
- **Por que é um problema (Configuração do Framework)**:
  Ao declarar um `@Bean public ObjectMapper objectMapper()` instanciando `new ObjectMapper()`, o Spring Boot desativa completamente sua auto-configuração padrão (`JacksonAutoConfiguration`). Com isso, configurações definidas via `application.properties` (`spring.jackson.*`), módulos descobertos no classpath e converters customizados são descartados.
- **Direção de Correção Recomendada**:
  Customizar o Jackson utilizando `Jackson2ObjectMapperBuilderCustomizer`:
  ```java
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
      return builder -> {
          builder.modules(new JavaTimeModule());
          builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      };
  }
  ```
  Ou configurar diretamente via properties:
  ```properties
  spring.jackson.serialization.write-dates-as-timestamps=false
  ```

---

### Eixo 5: Design da API REST, Validação, DTOs e Tratamento de Erros

#### Problema 5.1: Ausência de DTOs Tipados e Validação de Entrada na API REST
- **O que está errado**:
  Em `JobController.java:25-36`:
  ```java
  @PostMapping(value = "/nfs", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> processNfs(@RequestBody byte[] rawPayLoad) { ... }
  ```
- **Por que é um problema (Vulnerabilidade e Falha de Contrato)**:
  - O endpoint declara consumir `application/json`, mas recebe um `byte[] rawPayLoad` genérico sem converter para um modelo/objeto tipado.
  - Não há validação estrutural via Jakarta Bean Validation (`@Valid`, `@NotNull`, `@NotEmpty`, `@Size`).
  - Um cliente pode enviar um JSON vazio `{}` ou bytes aleatórios; o Spring aceitará a requisição e gerará um job com payload inválido, propagando o lixo para o Kafka e sobrecarregando o consumidor assíncrono com erros que deveriam ter sido rejeitados na borda com `HTTP 400 Bad Request`.
- **Direção de Correção Recomendada**:
  1. Definir um Request DTO com anotações de validação:
     ```java
     public record ScheduleJobRequest(
         @NotBlank(message = "CNPJ do tomador/prestador é obrigatório")
         @Pattern(regexp = "\\d{14}", message = "CNPJ deve conter 14 dígitos numéricos")
         String cnpj,
         
         @NotNull(message = "Valor é obrigatório")
         @Positive(message = "Valor deve ser positivo")
         BigDecimal valor,
         
         @NotEmpty(message = "A lista de itens da NFS-e não pode ser vazia")
         List<ItemNfseRequest> itens
     ) {}
     ```
  2. Utilizar `@Valid @RequestBody ScheduleJobRequest request` no controller.

---

#### Problema 5.2: Respostas HTTP Não Tipadas (`Map<String, Object>`) e Violação do RFC 7807 (Problem Details)
- **O que está errado**:
  O `JobController` retorna `ResponseEntity<Map<String, Object>>` instanciando `Map.of("jobId", jobId)` no sucesso e `Map.of("error", ...)` no erro.
- **Por que é um problema (Design de API e Contratos)**:
  Mapas heterogêneos impedem a geração de documentação OpenAPI/Swagger precisa, dificultam o consumo por clientes tipados (TypeScript, Java/Feign, Go) e quebram a consistência de respostas de erro da aplicação.
- **Direção de Correção Recomendada**:
  1. Criar um Response DTO:
     ```java
     public record JobScheduledResponse(UUID jobId, JobStatus status, Instant createdAt) {}
     ```
  2. Para respostas de erro, utilizar o padrão **RFC 7807 (ProblemDetail)**, suportado nativamente pelo Spring Boot 3+ (`org.springframework.http.ProblemDetail`).

---

#### Problema 5.3: Tratamento de Exceções Local com Try-Catch Genérico e Vazamento de Informações (CWE-209)
- **O que está errado**:
  Em `JobController.java:27-35`:
  ```java
  try {
      UUID jobId = jobService.scheduleNFsProcessing(rawPayLoad);
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
  } catch (Exception e) {
      return ResponseEntity.internalServerError()
              .body(Map.of("error", "Failed to process payload: " + e.getMessage()));
  }
  ```
- **Por que é um problema (Boas Práticas Spring e Segurança)**:
  - Fazer `try-catch` capturando `Exception` dentro de métodos de controle é um antipadrão no Spring MVC.
  - Ignora o mecanismo centralizado de tratamento de erros (`@RestControllerAdvice`).
  - Concatenar `e.getMessage()` na resposta externa (`"Failed to process payload: " + e.getMessage()`) é uma vulnerabilidade de segurança conhecida como **Information Exposure Through an Error Message (CWE-209)**, podendo expor detalhes de banco de dados, nomes de tabelas, senhas de conexão ou stack traces para clientes maliciosos.
- **Direção de Correção Recomendada**:
  Remover o `try-catch` do controller e criar um `@RestControllerAdvice`:
  ```java
  @RestControllerAdvice
  public class GlobalExceptionHandler {
      
      @ExceptionHandler(MethodArgumentNotValidException.class)
      public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
          ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
          // adicionar detalhes dos campos inválidos
          return problem;
      }
      
      @ExceptionHandler(Exception.class)
      public ProblemDetail handleGeneric(Exception ex) {
          log.error("Unhandled exception caught", ex);
          return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
      }
  }
  ```

---

#### Problema 5.4: Ausência de Endpoints de Consulta de Status (`GET /api/jobs/{id}`)
- **O que está errado**:
  A API possui apenas o endpoint de submissão `POST /api/jobs/nfs`. Embora o repositório possua o método `findById(UUID id)`, **não existe endpoint GET** para que os clientes verifiquem o progresso, status ou resultado de seus jobs.
- **Por que é um problema (Experiência do Consumidor da API)**:
  Quando uma API retorna `202 Accepted` com um identificador de recurso assíncrono, a convenção REST (e a RFC 9110) dita que ela deve fornecer um meio (via header `Location` ou endpoint de consulta) para o cliente consultar o estado do processamento. Sem o endpoint `GET`, o cliente recebe o `jobId`, mas fica impossibilitado de saber se o processamento foi concluído ou falhou.
- **Direção de Correção Recomendada**:
  Implementar o endpoint:
  ```java
  @GetMapping("/{id}")
  public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable UUID id) { ... }
  ```

---

#### Problema 5.5: Vulnerabilidade e Fragilidade no `PayloadSizeFilter`
- **O que está errado**:
  Em `PayloadSizeFilter.java:11-26`:
  ```java
  long maxSize = 102400L;
  long requestSize = request.getContentLengthLong();

  if (requestSize > maxSize) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.getWriter().write("Payload size exceeds maximum allowed limit of " + maxSize + " bytes.");
      return;
  }
  ```
- **Por que é um problema (Bypass de Segurança e Resposta Inconsistente)**:
  1. `request.getContentLengthLong()` lê o header HTTP `Content-Length` fornecido pelo cliente. Se o cliente enviar requisições com `Transfer-Encoding: chunked` ou omitir o header (valor `-1`), o `requestSize` será `-1`, e o filtro **será completamente burlado**, permitindo que payloads gigantes passem sem checagem!
  2. O limite de `100 KB` (`102400L`) está fixo no código (hardcoded), sem possibilidade de ajuste por ambiente.
  3. A resposta de erro é escrita como texto puro (`text/plain`) via `response.getWriter().write(...)`, sem cabeçalho `Content-Type: application/problem+json`, quebrando o formato JSON esperado pelos clientes da API.
- **Direção de Correção Recomendada**:
  1. Utilizar os limites nativos do servlet container no `application.properties`:
     ```properties
     server.tomcat.max-http-form-post-size=10MB
     spring.servlet.multipart.max-request-size=10MB
     ```
  2. Se um filtro customizado for necessário, envelopar a `InputStream` da requisição com um contador de bytes (como `BoundedInputStream` do Apache Commons IO) que encerre a leitura assim que o limite configurado for atingido.

---

### Eixo 6: Consistência de Nomenclatura e Mistura de Idiomas (PT-BR vs EN)

#### Problema 6.1: Mistura Excessiva de Português e Inglês no Código
- **O que está errado**:
  No mesmo módulo, há termos em português e inglês misturados:
  - **Mensagens de Log em Português** em classes escritas em inglês:
    - `log.info("Job criado. id={} status={}", ...)` (`JobService.java:30`)
    - `log.info("Evento disparado para publicação no Kafka. id={}", ...)` (`JobService.java:37`)
    - `log.error("Falha ao publicar evento no Kafka. jobId={}", ...)` (`KafkaJobEventPublisher.java:32`)
    - `log.info("Evento publicado. topic={} ...", ...)` (`KafkaJobEventPublisher.java:34`)
  - **Comentários de configuração em Português**:
    - `# Database Configuration (H2 in-memory em modo PostgreSQL)` (`application.properties:4`)
  - **Endpoints e tabelas com termos em Português/Siglas**:
    - `@PostMapping("/nfs")`, método `processNfs`, método `scheduleNFsProcessing`, tabela `nfse_job`.
  - **Inconsistências de Casing**:
    - Variável `rawPayLoad` (P maiúsculo e L maiúsculo, em vez do padrão `rawPayload`).
- **Por que é um problema (Padrões de Engenharia e Legibilidade)**:
  Em engenharia de software profissional, a mistura de idiomas cria ruído cognitivo, dificulta a padronização de queries em ferramentas de log centralizado (como Datadog, Splunk, ElasticSearch) e quebra convenções em times multidisciplinares ou globais.
- **Direção de Correção Recomendada**:
  Adotar a regra de **100% de código e logs em Inglês**.
  - Logs: `log.info("Job created successfully. jobId={}, status={}", job.getId(), job.getStatus());`
  - Métodos: `scheduleJobProcessing`, `createJob`.
  - Parâmetros: `rawPayload`.

---

### Eixo 7: Qualidade, Cobertura e Arquitetura da Suíte de Testes

#### Problema 7.1: Ausência Completa de Testes na Camada de Serviço (`JobServiceTest` Vazio)
- **O que está errado**:
  O arquivo `src/test/java/hub/pedro/jobs/api/app/service/JobServiceTest.java` tem exatamente **0 bytes** (está completamente vazio).
- **Por que é um problema (Cobertura Zero no Núcleo de Aplicação)**:
  O `JobService` é o coração da orquestração da API (cria o agregado, salva no repositório, constrói o evento e dispara o publisher). Ter 0% de testes no serviço principal significa que qualquer refatoração pode quebrar o fluxo central sem que o CI/CD detecte.
- **Direção de Correção Recomendada**:
  Implementar testes unitários isolados com Mockito:
  ```java
  @ExtendWith(MockitoExtension.class)
  class JobServiceTest {
      @Mock private JobRepository repository;
      @Mock private JobEventPublisher publisher;
      @InjectMocks private JobService jobService;

      @Test
      void shouldCreateJobAndPublishEventSuccessfully() {
          byte[] payload = "{\"teste\":1}".getBytes();
          UUID id = jobService.scheduleNFsProcessing(payload);

          assertThat(id).isNotNull();
          verify(repository).save(any(Job.class));
          verify(publisher).publish(any(JobCreatedEvent.class));
      }
  }
  ```

---

#### Problema 7.2: Teste do Controller Superficial e Incompleto
- **O que está errado**:
  `JobControllerTest.java` possui apenas 1 teste de caminho feliz (`shouldAcceptNfsProcessingRequest`).
- **Por que é um problema (Lacuna na Pirâmide de Testes)**:
  Não são testados:
  - Respostas a payloads com tamanho superior ao limite do filtro (cenário de erro 413);
  - Payloads com JSON inválido ou corpo vazio (cenários de erro 400);
  - Exceções disparadas pelo serviço (cenário de erro 500 / ProblemDetail);
  - Content-Type inválido (cenário de erro 415 Unsupported Media Type).
- **Direção de Correção Recomendada**:
  Expandir a suíte de testes de slice web para cobrir todos os fluxos de exceção e validação de contratos.

---

#### Problema 7.3: Dependências de Testcontainers Declaradas mas Nunca Utilizadas
- **O que está errado**:
  No `jobs-api/pom.xml:70-84`, foram adicionadas as dependências:
  - `spring-boot-testcontainers`
  - `testcontainers-junit-jupiter`
  - `testcontainers-kafka`
  Porém, **não existe um único teste de integração com Testcontainers** no módulo `jobs-api`.
- **Por que é um problema (Dependências Mortas vs Ausência de Integração Real)**:
  Adicionar dependências no POM sem utilizá-las aumenta o tempo de download do build e introduz complexidade desnecessária. Mais grave ainda: não há testes de integração reais que validem a comunicação com o PostgreSQL ou com o broker Kafka real.
- **Direção de Correção Recomendada**:
  Implementar uma classe base de teste de integração (`AbstractIntegrationTest`) utilizando `@Testcontainers` com containers reais de PostgreSQL e Kafka para validar o fluxo ponta a ponta (`POST /api/jobs/nfs` -> persistência no Postgres -> mensagem recebida no tópico Kafka).

---

## 3. Matriz de Priorização dos Problemas Encontrados

| ID | Descrição do Problema | Impacto Técnico | Categoria | Nível de Prioridade |
|---|---|---|---|---|
| **P1** | **Omissão do campo `errors` no JPA (`Nfsejob`) e repositório** | Perda irrecuperável de dados de erros de processamento no banco | Domínio / Persistência | 🔴 **Crítico** |
| **P2** | **Dual-Write Problem entre PostgreSQL e Kafka** | Inconsistência de dados: jobs salvos como PENDING nunca processados | Arquitetura Distribuída | 🔴 **Crítico** |
| **P3** | **Falha silenciosa e engolimento de erro no `KafkaJobEventPublisher`** | Cliente recebe 202 Accepted mesmo com falha total de envio no Kafka | Resiliência / API | 🔴 **Crítico** |
| **P4** | **Configuração de banco H2 sem driver H2 no classpath (`pom.xml`)** | Falha fatal de inicialização da aplicação (`ClassNotFoundException`) | Configuração / Build | 🔴 **Crítico** |
| **P5** | **`JobServiceTest` vazio (0 bytes) e ausência de testes de integração** | Cobertura 0% no núcleo da aplicação; regressões indetectáveis | Qualidade de Testes | 🟡 **Importante** |
| **P6** | **Ausência de DTOs tipados e validação Bean Validation na API** | Entrada de payloads corrompidos sem validação na borda | API REST / Segurança | 🟡 **Importante** |
| **P7** | **Try-catch genérico no Controller expondo mensagens internas (CWE-209)** | Vazamento de detalhes técnicos e quebra do padrão RFC 7807 | Segurança / API REST | 🟡 **Importante** |
| **P8** | **Ausência de Inbound Ports (`ScheduleJobUseCase`) em Hexagonal** | Acoplamento do Controller a classes concretas de serviço | Arquitetura Hexagonal | 🟡 **Importante** |
| **P9** | **Fragilidade do `PayloadSizeFilter` com `Transfer-Encoding: chunked`** | Bypass de limite de tamanho de payload por clientes | Segurança / Web Filter | 🟡 **Importante** |
| **P10** | **Versão inexistente do Spring Boot (`4.1.1`) e dependências erradas no POM** | Instabilidade de build e resolução incorreta de artefatos | Build / Maven | 🟡 **Importante** |
| **P11** | **Falta de endpoint de consulta (`GET /api/jobs/{id}`)** | Cliente impossibilitado de consultar o status assíncrono do job | API REST | 🟢 **Melhoria** |
| **P12** | **Mistura de Português e Inglês em logs, comentários e código** | Dificuldade de observabilidade e quebra de convenções | Qualidade / Clean Code | 🟢 **Melhoria** |
| **P13** | **Erros ortográficos em pacotes (`persistance`) e classes (`Nfsejob`)** | Débito técnico e violação de convenções Java | Clean Code | 🟢 **Melhoria** |
| **P14** | **Falta de optimistic locking (`@Version`) na entidade de persistência** | Risco de Lost Updates em atualizações concorrentes | Banco de Dados | 🟢 **Melhoria** |

---

## 4. Blueprint Arquitetural Alvo para o `jobs-api`

### Diagrama Estrutural Hexagonal Recomendado
```
                                +---------------------------------------------------+
                                |                     JOBS-API                      |
                                |                                                   |
 [ Client HTTP ]                |   [ Inbound Adapter ]                             |
       |                        |   JobController (REST)                            |
       | (POST /api/v1/jobs)    |            |                                      |
       v                        |            v (ScheduleJobCommand)                 |
 [ PayloadFilter / Auth ] ----> |   [ Inbound Port ]                                |
                                |   ScheduleJobUseCase                              |
                                |            |                                      |
                                |            v                                      |
                                |   [ Application Service ]                         |
                                |   ScheduleJobService                              |
                                |       |                   |                       |
                                |       v                   v                       |
                                |   [ Domain Model ]    [ Outbox Port ]             |
                                |   Job (Aggregate)     OutboxEventRepositoryPort   |
                                |   JobId, JobStatus                |               |
                                |       |                           |               |
                                |       +-------------+-------------+               |
                                |                     |                             |
                                |                     v                             |
                                |   [ Outbound Adapters ]                           |
                                |   - PostgresJobPersistenceAdapter                 |
                                |   - PostgresOutboxPersistenceAdapter              |
                                +---------------------|-----------------------------+
                                                      | (Atomic DB Transaction)
                                                      v
                                            [ PostgreSQL Database ]
                                            ├── table: nfse_job
                                            └── table: outbox_events
                                                      |
                                                      v (Debezium CDC / Poller)
                                             [ Kafka Event Publisher ]
                                                      |
                                                      v (Topic: job-created)
                                                [ Apache Kafka ]
```

---

## 5. Conclusão da Investigação do `jobs-api`

O módulo `jobs-api` possui uma base conceitual promissora ao visar Arquitetura Hexagonal e desacoplamento via mensageria. Contudo, para atingir maturidade de nível pleno/sênior e estar pronto para produção de missão crítica, requer as seguintes correções prioritárias:
1. **Garantir Confiabilidade Distribuída**: Eliminar a publicação síncrona/não-transacional direta no Kafka adotando o Transactional Outbox Pattern ou bloqueio com tratamento estrito de erro.
2. **Sanar Perda de Dados**: Mapear o campo `errors` e adicionar `@Version` na persistência relacional.
3. **Corrigir Build e Configurações**: Ajustar a versão do Spring Boot, corrigir starters e alinhar a URL do PostgreSQL com variáveis de ambiente.
4. **Padronizar API REST e Validações**: Adotar DTOs com Jakarta Bean Validation, ProblemDetail (RFC 7807) e tratamento centralizado com `@RestControllerAdvice`.
5. **Elevar a Cobertura de Testes**: Implementar testes unitários para a camada de serviço/domínio e testes de integração com Testcontainers.
