# Análise Estrutural e Arquitetural Profunda: Módulo `jobs-consumer`
**Projeto**: Jobs — NFS-e Emission Pipeline  
**Módulo**: `jobs-consumer` (Kafka Worker & Processamento Assíncrono)  
**Autor**: Teamwork Preview Explorer Survey 2 (Mentoria Técnica Arquitetural)  
**Data**: 2026-08-28  
**Nível de Avaliação**: Pleno → Sênior / Especialista  

---

## 1. Sumário Executivo

O módulo `jobs-consumer` foi concebido como um worker de processamento de eventos do pipeline de emissão de NFS-e, consumindo mensagens do tópico Kafka `job-created` e executando o processamento das notas fiscais. 

Embora o módulo apresente a intenção de seguir preceitos modernos (Spring Boot 3/4, Java 21, Arquitetura Hexagonal, jMolecules DDD e Kafka), a investigação identificou **falhas arquiteturais críticas, riscos severos de perda de dados em produção, inconsistências conceituais de DDD/Portas e Adaptadores, ausência total de cobertura de testes e desconexões operacionais**.

### Principais Destaques Críticos:
1. **Anti-pattern Crítico de Perda de Dados (Kafka Auto-Commit + `@Async` Fire-and-Forget)**: O listener Kafka despacha a execução para um `ThreadPoolTaskExecutor` em memória e retorna imediatamente, confirmando o offset no Kafka antes mesmo do processamento iniciar. Falhas de JVM, reinicializações ou rejeição na fila resultam em perda definitiva de eventos sem reprocessamento.
2. **Ruptura de Mapeamento Domínio ↔ Persistência**: O campo `errors` presente na entidade de domínio `Job` e populado no método `markAsFailed()` é completamente ignorado na entidade JPA `Nfsejob` e no repositório `PostgresJobRepository`. O motivo das falhas é permanentemente descartado.
3. **Isolamento de Banco de Dados Local (H2 In-Memory vs PostgreSQL)**: O arquivo `application.properties` está configurado para um banco H2 em memória (`jdbc:h2:mem:jobsdb`), enquanto o `jobs-api` grava no PostgreSQL. Fora do ambiente Docker Compose, o consumer nunca localiza os jobs gerados pela API (`Job id=... não encontrado na base de dados`).
4. **Actuator Fantasma (Sem Web Starter)**: `jobs-consumer` inclui `spring-boot-starter-actuator` e define `server.port=8081`, mas não inclui `spring-boot-starter-web` (ou WebFlux), impossibilitando a exposição de endpoints HTTP de health check (`/actuator/health`) e métricas Prometheus para o Kubernetes.
5. **Simulação de NFS-e Inexistente**: O processamento não executa chamadas externas, simulações de delay ou tratamento de resiliência (Circuit Breaker / Retry) — limitando-se a alterar o status do objeto para `DONE` em memória.
6. **Ausência Absoluta de Testes**: Não existe diretório `src/test` no módulo, apesar da presença de dependências de Testcontainers no `pom.xml`.

---

## 2. Diagnóstico Detalhado por Eixo Arquitetural

---

### EIXO 1: Organização de Pacotes, Arquitetura Hexagonal e DDD

#### 1.1 Estrutura Atual de Pacotes
```
hub.pedro.jobs.consumer
├── ConsumerApplication.java
├── app/
│   ├── port/
│   │   └── in/
│   │       └── JobCreatedEvent.java          <-- [Inconsistência] Record DTO alocado em port.in
│   └── service/
│       └── JobProcessingService.java         <-- [Inconsistência] Serviço concreto sem implementar interface de porta
├── config/
│   ├── AsyncConfig.java
│   └── JacksonConfig.java
├── domain/
│   ├── entity/
│   │   └── Job.java                          <-- Entidade de domínio (AggregateRoot)
│   ├── interfaces/
│   │   └── JobStatus.java                    <-- [Inconsistência] Enum alocado no pacote 'interfaces'
│   └── repository/
│       └── JobRepository.java                <-- Porta de saída (Outbound Port)
└── infra/
    ├── database/
    │   └── postgresql/
    │       ├── persistance/                  <-- [Bug/Typo] Pacote com erro de grafia ('persistance')
    │       │   └── Nfsejob.java              <-- [Naming] Violação de convenção CamelCase
    │       └── repository/
    │           ├── PostgresJobRepository.java <-- Adaptador de saída (implementa JobRepository)
    │           └── PostgresJpaRepository.java <-- Spring Data JPA
    └── kafka/
        └── consumer/
            └── JobEventoConsumer.java        <-- Adaptador de entrada (Kafka Listener)
```

#### 1.2 Análise Pedagógica e Problemas Identificados

##### Problema A: Inversão Conceitual de Portas de Entrada (`port.in`)
- **O que está errado**: O arquivo `JobCreatedEvent.java` é um `record` que representa a estrutura de dados de uma mensagem recebida do Kafka. Ele está posicionado dentro de `app.port.in`.
- **Por que é um problema (Visão Sênior/Mentor)**: Na Arquitetura Hexagonal (Ports & Adapters), uma **Porta de Entrada (Driving/Primary Port)** define a interface de caso de uso (Use Case Interface) pela qual o mundo externo interage com a aplicação (ex: `ProcessJobUseCase` ou `JobProcessingPort`). Um evento ou DTO de transporte não é uma porta, é um contrato de dados. Além disso, o adaptador `JobEventoConsumer` injeta diretamente a classe concreta `JobProcessingService` (linha 24 de `JobEventoConsumer.java`), ignorando completamente o desacoplamento pretendido pelo padrão de portas e adaptadores.
- **Direção de Correção**:
  1. Criar a interface de porta de entrada: `hub.pedro.jobs.consumer.app.port.in.ProcessJobUseCase` com o método `void execute(UUID jobId)` ou `void execute(ProcessJobCommand command)`.
  2. Fazer `JobProcessingService` implementar `ProcessJobUseCase`.
  3. Mover `JobCreatedEvent` para o pacote de contratos de mensageria da infraestrutura Kafka (`infra.kafka.dto` ou `infra.kafka.event`) ou para um módulo compartilhado `jobs-shared`.

##### Problema B: Pacotes Impróprios e Erros de Grafia
- **O que está errado**:
  - `hub.pedro.jobs.consumer.domain.interfaces.JobStatus`: O enum `JobStatus` está dentro de um pacote chamado `interfaces`. Um enum é um Tipo/Value Object, não uma interface Java.
  - `hub.pedro.jobs.consumer.infra.database.postgresql.persistance`: O pacote está escrito com "a" (`persistance`), incorreto no padrão da língua inglesa (`persistence`).
  - Divergência com `jobs-api`: No módulo `jobs-api`, a interface `JobRepository` está em `domain.interfaces`, enquanto no `jobs-consumer` está em `domain.repository`. Em contrapartida, `JobStatus` no `jobs-api` está em `domain.shared`, enquanto no `jobs-consumer` está em `domain.interfaces`.
- **Por que é um problema**: Quebra a previsibilidade do projeto, dificulta a navegação, denota falta de rigor arquitetural e impede a padronização de regras de governança via ArchUnit.
- **Direção de Correção**: Unificar a convenção de pacotes. `JobStatus` deve ficar em `domain.model` ou `domain.enums` (ou no módulo compartilhado `jobs-shared`), `JobRepository` em `domain.repository` ou `domain.port.out`, e corrigir o pacote para `persistence`.

##### Problema C: Falta do Módulo `jobs-shared` e Duplicação de Código (Shared Kernel Omitido)
- **O que está errado**: O `pom.xml` raiz lista `jobs-shared` no `dependencyManagement`, mas o módulo físico não existe no repositório. Como resultado, as classes `Job`, `JobStatus`, `JobCreatedEvent`, `Nfsejob`, `PostgresJobRepository`, `PostgresJpaRepository` e `JacksonConfig` foram clonadas manualmente entre `jobs-api` e `jobs-consumer` com pequenas divergências e bugs assimétricos.
- **Por que é um problema**: Duplicação de regras de negócio, risco constante de quebra de contrato em tempo de execução (drift de schema entre produtor e consumidor) e retrabalho de manutenção.
- **Direção de Correção**: Implementar o módulo `jobs-shared` contendo os contratos canônicos de eventos (`JobCreatedEvent`), enums de domínio (`JobStatus`) e utilitários transversais, importando-o como dependência em ambos os módulos.

---

### EIXO 2: Arquitetura do Kafka Consumer e Risco Crítico de Perda de Dados

#### 2.1 Código em Análise
Em `JobEventoConsumer.java`:
```java
@KafkaListener(topics = "job-created", groupId = "jobs-consumer-group")
public void consumirEvento(String payload) {
    log.info("[Consumer] Evento recebido. payload={}", payload);
    try {
        JobCreatedEvent evento = objectMapper.readValue(payload, JobCreatedEvent.class);
        // @Async: retorna imediatamente, processamento ocorre em thread do pool
        jobProcessingService.processarNf(evento.id());
    } catch (JsonProcessingException e) {
        log.error("[Consumer] Falha ao desserializar evento Kafka: {}", e.getMessage(), e);
        throw new RuntimeException("Falha ao desserializar evento Kafka", e);
    }
}
```

Em `JobProcessingService.java`:
```java
@Async("jobProcessingExecutor")
public void processarNf(UUID id) {
    log.info("[Worker] Buscando Job id={} para processamento assíncrono", id);
    repository.findById(id).ifPresentOrElse(
            this::doProcessamento,
            () -> log.warn("[Worker] Job id={} não encontrado na base de dados", id)
    );
}
```

Em `AsyncConfig.java`:
```java
@Bean(name = "jobProcessingExecutor")
public ThreadPoolTaskExecutor jobProcessingExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("job-worker-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
}
```

#### 2.2 Análise Pedagógica e Problemas Identificados

##### Problema A: O Anti-Pattern "Kafka Consumer + In-Memory `@Async` Fire-and-Forget"
- **O que está errado**: O listener Kafka recebe uma mensagem e imediatamente delega sua execução para um executor assíncrono de thread pool (`ThreadPoolTaskExecutor`), retornando `void` em seguida.
- **Mecanismo da Falha (Passo a Passo)**:
  1. O thread do container do Spring Kafka consome a mensagem da partição do tópico `job-created`.
  2. O método `consumirEvento` despacha a tarefa para a fila de memória do `jobProcessingExecutor` (capacidade de até 100 itens) e finaliza sua execução síncrona.
  3. O Spring Kafka interpreta que o processamento do lote/registro foi concluído com sucesso e **efetua o commit do offset** no broker Kafka.
  4. **Cenário de Desastre 1 (Crash/Restart/OOM)**: Se a aplicação reiniciar durante um deploy, sofrer shutdown forçado pelo Kubernetes ou estourar a memória (OOM), todas as tarefas enfileiradas no `ThreadPoolTaskExecutor` (até 100 tarefas) ou em execução nos threads secundários são **completamente perdidas**. Como o offset já foi comitado no Kafka, o broker considera essas mensagens entregues e elas **nunca mais serão lidas**.
  5. **Cenário de Desastre 2 (Sobrecarga e Rejeição de Fila)**: Quando o volume de mensagens excede a capacidade do pool (20 threads ocupadas + 100 tarefas na fila), novas chamadas disparam `TaskRejectedException`. Dependendo de onde a exceção é lançada, tarefas são descartadas enquanto os offsets anteriores já foram gravados.
  6. **Cenário de Desastre 3 (Neutralização de Mecanismos de Retry e DLT do Kafka)**: Qualquer falha de negócio ou de banco ocorrida dentro de `processarNf` ocorre em um thread isolado e não sobe para o Spring Kafka. Assim, `DefaultErrorHandler`, retentativas e Dead Letter Topics do Spring Kafka nunca são acionados.
  7. **Cenário de Desastre 4 (Destruição da Garantia de Ordenação por Partição)**: O Kafka garante que mensagens com a mesma chave (ex: mesmo `jobId` ou mesmo emissor) cheguem em ordem estrita dentro de uma partição. Ao disparar as mensagens para um pool concorrente de 20 threads, a ordem de execução torna-se não-determinística.
- **Por que isso viola a essência do Kafka**: O Kafka já é, por definição, uma fila de mensagens persistida em disco com controle granular de offset e backpressure nativo. O mecanismo correto de paralelismo no Kafka é a **concorrência baseada em partições** (`spring.kafka.listener.concurrency=X` atribuindo threads a partições distintas), mantendo o processamento **síncrono** dentro do ciclo de vida da mensagem consumida.
- **Direção de Correção**:
  1. **Remover o `@Async` do fluxo de consumo do Kafka**. O método do listener ou do caso de uso deve executar o processamento de ponta a ponta de forma síncrona no thread do listener.
  2. Configurar o modo de confirmação explícito (ex: `AckMode.RECORD` ou `AckMode.MANUAL_IMMEDIATE` com injeção do objeto `Acknowledgment`).
  3. Utilizar `spring.kafka.listener.concurrency=3` (ou N partições) para paralelismo seguro entre partições.

##### Problema B: Ausência de Estratégia de Resiliência Kafka (Retry Topics & Dead Letter Topic - DLT)
- **O que está errado**: Não há configuração de `@RetryableTopic`, `CommonErrorHandler` ou `DeadLetterPublishingRecoverer`.
- **Por que é um problema**: No processamento de documentos fiscais, falhas podem ser **transitórias** (timeout temporário na prefeitura, banco momentaneamente sobrecarregado) ou **definitivas/poison pills** (payload corrompido, dados cadastrais inválidos). Sem retry topics com backoff exponencial e sem envio para DLT (`job-created-dlt`), um erro fatal ou bloqueia a partição em loop infinito ou descarta o documento sem rastreabilidade operacional.
- **Direção de Correção**:
  - Implementar um `DefaultErrorHandler` com `FixedBackOff` ou `ExponentialBackOffWithMaxRetries` e `DeadLetterPublishingRecoverer` para encaminhar mensagens não recuperáveis ao tópico `job-created-dlt`.
  - Configurar anotação `@RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2.0))` no listener Kafka.

##### Problema C: Desserialização Manual em Bloco `try-catch`
- **O que está errado**: O método `consumirEvento(String payload)` recebe uma `String` pura e executa `objectMapper.readValue` manualmente.
- **Por que é um problema**: Se a mensagem no Kafka for um JSON inválido, o método lança uma `RuntimeException`. Sem um `ErrorHandlingDeserializer`, falhas de parsing podem travar o consumidor. Além disso, o Spring Kafka suporta tipagem direta com `ErrorHandlingDeserializer` envolvendo o `JsonDeserializer`.
- **Direção de Correção**: Configurar o `ConsumerFactory` com `ErrorHandlingDeserializer` e permitir que o `@KafkaListener` receba diretamente o tipo `JobCreatedEvent` no parâmetro do método.

---

### EIXO 3: Mapeamento Domínio ↔ Persistência e Ciclo de Vida JPA

#### 3.1 Comparativo de Estruturas

| Atributo | Entidade de Domínio (`Job.java`) | Entidade JPA (`Nfsejob.java`) | Mapeamento no Repositório (`PostgresJobRepository.java`) |
| :--- | :--- | :--- | :--- |
| `id` | `UUID id` | `@Id private UUID id` | Mapeado (`setId`) |
| `payload` | `String payload` | `@Column(columnDefinition="TEXT") private String payload` | Mapeado (`setPayload`) |
| `status` | `JobStatus status` | `@Enumerated(EnumType.STRING) private JobStatus status` | Mapeado (`setStatus`) |
| `createdAt` | `Instant createdAt` | `@Column(nullable=false, updatable=false) private Instant createdAt` | Mapeado (`setCreatedAt`) |
| `updatedAt` | `Instant updatedAt` | `@Column private Instant updatedAt` | Mapeado (`setUpdatedAt`) |
| `scheduledAt` | `Instant scheduledAt` | `@Column private Instant scheduledAt` | Mapeado (`setScheduledAt`) |
| `finishedAt` | `Instant finishedAt` | `@Column private Instant finishedAt` | Mapeado (`setFinishedAt`) |
| `attempts` | `Integer attempts` | `@Column private Integer attempts` | Mapeado (`setAttempts`) |
| **`errors`** | **`List<String> errors`** | **NÃO EXISTE (Campo Ausente)** | **IGNORADO (Perda de Dados)** |
| **`version`** | **NÃO EXISTE** | **NÃO EXISTE (Sem `@Version`)** | **Ausência de Optimistic Locking** |

#### 3.2 Análise Pedagógica e Problemas Identificados

##### Problema A: Perda de Dados de Erros de Processamento (`errors` list descartada)
- **O que está errado**: No domínio, a entidade `Job` possui `private List<String> errors;` e o método `markAsFailed(String errorMessage)` adiciona mensagens de erro a essa lista (linhas 46-52 de `Job.java`). No entanto, a entidade `Nfsejob.java` **não possui coluna para erros** (seja como JSONB, tabela filha `@ElementCollection` ou coluna de texto simples). Ao executar `PostgresJobRepository.save(job)`, o repositório simplesmente não grava os erros, e em `findById` reconstrói o `Job` com `errors` vazio.
- **Impacto**: Quando um job falha no processamento (ex: validação de CNPJ ou recusa de schema pela prefeitura), o job é marcado como `FAILED`, mas o motivo do erro é **completamente apagado**. Quando o cliente da API consulta `GET /api/v1/jobs/{id}`, não há como diagnosticar a causa do erro.
- **Direção de Correção**:
  1. Adicionar na entidade JPA `Nfsejob` o mapeamento para mensagens de erro:
     - Opção 1: `@Column(name = "last_error", columnDefinition = "TEXT") private String lastError;`
     - Opção 2: `@ElementCollection` com tabela de histórico de erros `nfse_job_errors`.
     - Opção 3: Coluna `error_details` do tipo JSONB no PostgreSQL.
  2. Ajustar `PostgresJobRepository` para mapear a lista de erros bidirecionalmente.

##### Problema B: Conflito Crítico de Banco de Dados Local (H2 vs PostgreSQL)
- **O que está errado**: Em `jobs-consumer/src/main/resources/application.properties`:
  ```properties
  spring.datasource.url=jdbc:h2:mem:jobsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
  spring.datasource.username=sa
  spring.datasource.password=
  ```
  Enquanto no `jobs-api`:
  ```properties
  spring.datasource.url=jdbc:postgresql://localhost:5432/jobsdb
  ```
- **Por que é um problema**: Ao rodar a aplicação localmente na IDE (fora do Docker Compose):
  1. `jobs-api` insere o job no banco PostgreSQL local.
  2. `jobs-api` publica o evento no Kafka.
  3. `jobs-consumer` consome o evento do Kafka e tenta executar `repository.findById(id)` no seu banco **H2 em memória**, que está completamente vazio.
  4. O consumer emite o log de aviso: `[Worker] Job id=... não encontrado na base de dados` e encerra a execução sem processar nada.
  5. Além disso, o `pom.xml` do `jobs-consumer` não possui a dependência do driver `com.h2database:h2`, o que causa falha de inicialização caso não haja fallback implícito.
- **Direção de Correção**: Padronizar as configurações de datasource em ambos os módulos apontando para `jdbc:postgresql://localhost:5432/jobsdb` como padrão de desenvolvimento local, utilizando perfis (`application-local.properties`, `application-docker.properties`, `application-test.properties`).

##### Problema C: Instanciação de Entidade Desanexada e Falta de Bloqueio Otimista (`@Version`)
- **O que está errado**:
  1. No método `PostgresJobRepository.save(Job job)`:
     ```java
     Nfsejob nfsejob = new Nfsejob();
     nfsejob.setId(job.getId());
     ...
     jpaRepository.save(nfsejob);
     ```
     O repositório cria uma nova instância de `Nfsejob` e chama `save()`. Como o `id` não é nulo, o Spring Data JPA/Hibernate precisa emitir um `SELECT` prévio para verificar se o registro existe no banco antes de decidir entre `INSERT` ou `UPDATE`.
  2. A entidade `Nfsejob` não possui um campo anotado com `@Version` (`Long version`).
- **Por que é um problema**: Sem controle de concorrência otimista (`@Version`), em cenários de alta concorrência ou retentativas simultâneas de mensagens, ocorrem *lost updates* (uma transação sobrescreve a outra cegamente).
- **Direção de Correção**: Adicionar `@Version private Long version;` na entidade `Nfsejob` e no modelo de domínio, e utilizar MapStruct para converter entre modelo de domínio e entidade de persistência com rastreabilidade de estado.

---

### EIXO 4: Chamadas a Serviços Externos e Simulação de Emissão de NFS-e

#### 4.1 Código em Análise
Em `JobProcessingService.java`:
```java
private void doProcessamento(Job job) {
    try {
        log.info("[Worker] Iniciando processamento do Job id={}", job.getId());
        job.finalizar();
        repository.save(job);
        log.info("[Worker] Job id={} processado com SUCESSO. Status={}", job.getId(), job.getStatus());
    } catch (Exception e) {
        log.error("[Worker] Erro ao processar Job id={}: {}", job.getId(), e.getMessage(), e);
        job.markAsFailed(e.getMessage());
        repository.save(job);
    }
}
```

#### 4.2 Análise Pedagógica e Problemas Identificados

##### Problema A: Simulação Inexistente / Ausência de Regra de Emissão
- **O que está errado**: O `pom.xml` possui o trecho comentado de um suposto SDK (`nfse-nacional-sdk`), mas dentro do código Java o método `doProcessamento` executa apenas um `job.finalizar()` instantâneo em memória. Não há chamada HTTP, não há simulação de delay de rede, não há validação de payload e não há tratamento de respostas fiscais (ex: protocolo de autorização, número de NFS-e, código de verificação).
- **Por que é um problema**: Em um projeto destinado a demonstrar competência em nível sênior em arquitetura de eventos, a ausência de uma camada de integração com serviço externo (ou simulador com latência e taxas de erro estocásticas) descaracteriza o objetivo do pipeline de processamento assíncrono.
- **Direção de Correção**:
  1. Definir uma porta de saída no domínio/aplicação: `hub.pedro.jobs.consumer.app.port.out.NfseEmissionGateway` com método `NfseEmissionResult emit(Job job)`.
  2. Criar uma implementação simuladora em `infra.client.simulated.SimulatedNfseEmissionAdapter` que implemente:
     - Latência realista (ex: 200ms a 1500ms).
     - Taxa controlada de falhas simuladas (ex: 10% de erros 503/timeout) para testar os fluxos de retentativa e DLT.
     - Retorno de dados fiscais fictícios (Protocolo, Número da Nota, XML assinado).

##### Problema B: Ausência de Padrões de Resiliência (Circuit Breaker, Rate Limiting, Timeout)
- **O que está errado**: O módulo não inclui bibliotecas de resiliência como Resilience4j (`resilience4j-spring-boot3`).
- **Por que é um problema**: Serviços de emissão de NFS-e municipais e estaduais sofrem frequentes instabilidades e imposição de rate limits estritos (ex: máximo de 5 a 10 requisições simultâneas por certificado). Sem Circuit Breaker, falhas em cascata saturam o worker. Sem Rate Limiter, a aplicação é bloqueada por WAF/firewall da prefeitura.
- **Direção de Correção**: Integrar Resilience4j no adaptador de emissão de NFS-e, configurando:
  - **CircuitBreaker**: para abrir o circuito e redirecionar imediatamente para retentativa quando a taxa de falha exceder 50%.
  - **RateLimiter**: para limitar a vazão de requisições por segundo.
  - **Timeouts**: configurados via `RestClient` ou `WebClient` (ex: connect timeout de 3s, read timeout de 10s).

---

### EIXO 5: Naming Conventions e Consistência Linguística (Mistura PT/EN)

O módulo apresenta inconsistências linguísticas sistemáticas, misturando português e inglês em classes, métodos, pacotes e mensagens de log.

#### 5.1 Inventário de Inconsistências

| Localização (Arquivo / Linha) | Elemento Atual | Idioma | Classificação do Erro | Recomendação de Nomenclatura (Padrão Inglês) |
| :--- | :--- | :--- | :--- | :--- |
| `Job.java:40` | `public void finalizar()` | Português | Método de Domínio em PT (em classe com métodos em EN) | `public void complete()` ou `public void finish()` |
| `Job.java:46` | `public void markAsFailed(...)` | Inglês | Inconsistência interna com `finalizar()` | Manter em inglês |
| `JobProcessingService.java:25` | `public void processarNf(UUID id)` | Português | Método de Serviço em PT | `public void processJob(UUID id)` ou `public void processNfse(UUID id)` |
| `JobProcessingService.java:33` | `private void doProcessamento(Job job)` | Híbrido | Mistura de verbo EN (`do`) com substantivo PT (`Processamento`) | `private void executeProcessing(Job job)` ou `private void emitNfse(Job job)` |
| `JobEventoConsumer.java:20` | `public class JobEventoConsumer` | Híbrido | Substantivo PT (`Evento`) entre termos EN (`Job`, `Consumer`) | `public class JobEventConsumer` ou `public class JobCreatedKafkaListener` |
| `JobEventoConsumer.java:33` | `public void consumirEvento(String payload)` | Português | Método listener em PT | `public void onMessage(String payload)` ou `public void consumeJobCreatedEvent(...)` |
| `JobEventoConsumer.java:36` | `JobCreatedEvent evento = ...` | Português | Variável local em PT | `JobCreatedEvent event = ...` |
| `Nfsejob.java:18` | `public class Nfsejob` | Inconsistente | Violação de CamelCase (`job` em minúsculo) | `public class NfseJob` ou `public class JobJpaEntity` |
| Pacote `...postgresql.persistance` | `package ...persistance` | Erro/Typo | Grafia incorreta de `persistence` | `hub.pedro.jobs.consumer.infra.database.postgresql.persistence` |
| Pacote `...domain.interfaces` | `package ...domain.interfaces` | Impróprio | Contém enum `JobStatus` | `hub.pedro.jobs.consumer.domain.model` ou `hub.pedro.jobs.shared.enums` |
| `AsyncConfig.java:16` | `executor.setThreadNamePrefix("job-worker-")` | Inglês | Correto | Manter padrão em inglês |

#### 5.2 Diretriz Arquitetural
Em projetos Java corporativos internacionais ou de alto nível técnico, adota-se o padrão estrito de **código 100% em Inglês** (classes, métodos, variáveis, pacotes, documentação Javadoc e exceptions), reservando o Português exclusivamente para termos de negócio estritamente nacionais que não possuem tradução direta (ex: `Nfse`, `Cnpj`, `Sefaz`) como termos do Ubiquitous Language do DDD.

---

### EIXO 6: Qualidade, Cobertura e Arquitetura de Testes

#### 6.1 Diagnóstico Atual
- **Cobertura Real**: **0%**. O diretório `src/test` **não existe** no módulo `jobs-consumer`.
- **Dependências Fantasma no `pom.xml`**:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
  </dependency>
  <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-junit-jupiter</artifactId>
      <scope>test</scope>
  </dependency>
  <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-kafka</artifactId>
      <scope>test</scope>
  </dependency>
  ```
  As dependências para testes de integração com containers reais de Kafka e JUnit 5 estão declaradas no `pom.xml`, mas nenhuma classe de teste foi implementada.

#### 6.2 Pirâmide de Testes Recomendada para o `jobs-consumer`

1. **Testes Unitários de Domínio (`JobTest.java`)**:
   - Testar invariantes de negócio da entidade `Job`: transição de estado de `PENDING` para `DONE` via `complete()`, preenchimento automático de `finishedAt` e `updatedAt`.
   - Testar transição para `FAILED` via `markAsFailed()`, incremento de `attempts` e registro cumulativo na lista `errors`.
2. **Testes Unitários de Aplicação (`JobProcessingServiceTest.java`)**:
   - Utilizar JUnit 5 + Mockito para mockar `JobRepository` e `NfseEmissionGateway`.
   - Validar cenários de sucesso, job não encontrado no banco e exceções lançadas durante a emissão com registro de falha.
3. **Testes de Integração com Testcontainers (`JobKafkaConsumerIntegrationTest.java`)**:
   - Inicializar containers reais de Kafka e PostgreSQL via `@Testcontainers`.
   - Publicar mensagens no tópico `job-created` e verificar:
     - Consumo correto e desserialização da mensagem.
     - Persistência e atualização do registro no PostgreSQL.
     - Confirmação de offset no Kafka.
     - Comportamento de envio para `job-created-dlt` em caso de mensagem corrompida.
4. **Testes de Arquitetura (ArchUnit - `ArchitectureRulesTest.java`)**:
   - Garantir que a camada de domínio (`domain..`) não dependa de classes do Spring ou de `infra..`.
   - Garantir que adaptadores de infraestrutura não acessem diretamente o domínio sem passar pelas portas da aplicação.

---

### EIXO 7: Infraestrutura, Monitoramento Operacional e CI/CD

#### 7.1 Actuator sem Web Starter e Porta Fantasma (8081)
- **O que está errado**:
  - `application.properties` define `server.port=8081` e `management.endpoints.web.exposure.include=health,info,prometheus`.
  - `compose.yaml` mapeia `ports: - "8081:8081"`.
  - No entanto, o `pom.xml` não inclui `spring-boot-starter-web` (ou `spring-boot-starter-webflux`).
- **Por que é um problema**: No Spring Boot, o `spring-boot-starter-actuator` necessita de um web server embutido (Tomcat/Netty) para expor seus endpoints HTTP. Como a dependência web foi intencionalmente omitida ("sem spring-boot-starter-web"), o Spring Boot inicializa como uma aplicação não-web (modo `WebApplicationType.NONE`). A porta 8081 **nunca é aberta**!
- **Consequência em Produção**: Probes de Liveness/Readiness do Kubernetes (`httpGet: path: /actuator/health port: 8081`) falharão com *Connection Refused*, fazendo com que o Kubernetes mate e reinicie continuamente os pods em loop (*CrashLoopBackOff*).
- **Direção de Correção**: Adicionar `spring-boot-starter-web` ao `jobs-consumer/pom.xml` para habilitar a porta de gerenciamento e observabilidade, ou utilizar um endpoint leve reativo.

#### 7.2 Manifests Kubernetes Incompletos (`k8s/`)
- **O que está errado**: O diretório `k8s/` possui manifests para `jobs-api`, `kafka` e `cassandra` (banco de dados que sequer é utilizado pelo projeto!), mas **não possui nenhum manifest de Deployment ou Service para o `jobs-consumer`**.
- **Direção de Correção**:
  - Criar `k8s/consumer-deployment.yaml` com 2 a 3 réplicas, variáveis de ambiente configurando conexão com Kafka e PostgreSQL, probes de liveness/readiness e limites de CPU/Memória.
  - Remover `k8s/cassandra.yaml` que não pertence à arquitetura do projeto.

#### 7.3 Discrepância de Versão do Java no CI/CD (`.github/workflows/maven-publish.yml`)
- **O que está errado**: O workflow do GitHub Actions está configurado com `java-version: '11'`, enquanto o projeto raiz e os módulos exigem Java 21 (`<java.version>21</java.version>`).
- **Por que é um problema**: O pipeline de CI falhará imediatamente ao tentar compilar classes com sintaxe e APIs do Java 21 (como `record`, `pattern matching`, etc.) em um JDK 11. Além disso, o workflow é acionado apenas em releases, não em PRs (`pull_request`).
- **Direção de Correção**: Atualizar o workflow para `java-version: '21'` e adicionar gatilhos para `push` e `pull_request` nas branches principais.

---

## 3. Catálogo Priorizado de Problemas e Recomendações

| ID | Prioridade | Componente | Descrição do Problema | Impacto Técnico / Operacional | Direção de Correção Recomendada |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ISS-01** | **CRÍTICO** | Kafka / `@Async` | Kafka Listener com auto-commit disparando `@Async` Fire-and-Forget | Perda definitiva de dados em caso de falha/restart da JVM; quebra total de garantias de entrega e ordenação. | Remover `@Async`, processar de forma síncrona no thread do listener com `AckMode` explícito e paralelismo via partições do Kafka. |
| **ISS-02** | **CRÍTICO** | JPA / Domain | Campo `errors` da entidade `Job` omitido em `Nfsejob` e no repositório | Descarte total do motivo de falhas dos jobs; impossibilidade de diagnóstico de erros pelo cliente da API. | Adicionar coluna/tabela de erros em `Nfsejob` e mapear bidirecionalmente em `PostgresJobRepository`. |
| **ISS-03** | **CRÍTICO** | Configuração | Datasource configurado para H2 em memória em `jobs-consumer` | Consumer não encontra nenhum job gerado pela API ao rodar localmente fora do Docker Compose. | Padronizar datasource para PostgreSQL local em ambos os módulos com perfis de ambiente. |
| **ISS-04** | **CRÍTICO** | Observabilidade | Actuator configurado sem `spring-boot-starter-web` | Porta 8081 não é aberta; probes de saúde do Kubernetes e métricas Prometheus falham. | Adicionar dependência web starter para viabilizar exposição HTTP dos endpoints de gestão do Actuator. |
| **ISS-05** | **IMPORTANTE** | Hexagonal / DDD | Inversão conceitual de portas (`JobCreatedEvent` em `port.in`, sem interface de caso de uso) | Acoplamento direto entre Kafka e serviço de aplicação; violação da Arquitetura Hexagonal. | Criar interface `ProcessJobUseCase` em `app.port.in` e mover DTOs de eventos para pacote de contratos/infraestrutura. |
| **ISS-06** | **IMPORTANTE** | Resiliência | Ausência de Retry Topics, Dead Letter Topic (DLT) e Circuit Breaker | Mensagens corrompidas ou falhas transitórias bloqueiam o consumidor ou são descartadas sem rastreabilidade. | Configurar `@RetryableTopic`, `DefaultErrorHandler` com DLT (`job-created-dlt`) e Resilience4j na emissão de NFS-e. |
| **ISS-07** | **IMPORTANTE** | Testes | Ausência total de testes no módulo `jobs-consumer` (0% de cobertura) | Risco de regressão silenciosa, falta de validação automatizada de integrações com Kafka e PostgreSQL. | Implementar testes unitários com JUnit 5/Mockito e testes de integração com Testcontainers (Kafka + PostgreSQL). |
| **ISS-08** | **MELHORIA** | Consistência | Mistura sistemática de Inglês e Português em classes, métodos e pacotes | Dificuldade de manutenção, falta de padrão e inconsistência estilística na base de código. | Padronizar código 100% em Inglês (`JobEventConsumer`, `complete()`, `persistence`), mantendo termos fiscais como Ubiquitous Language. |
| **ISS-09** | **MELHORIA** | Multi-módulo | Ausência do módulo físico `jobs-shared` previsto no POM raiz | Código duplicado entre `jobs-api` e `jobs-consumer` com risco de divergência de contratos e modelos. | Criar o módulo `jobs-shared` e extrair contratos de eventos e enums compartilhados. |
| **ISS-10** | **MELHORIA** | Infra / CI-CD | Falta de manifest K8s para o consumer e JDK 11 no GitHub Actions | Impossibilidade de deploy no Kubernetes e falha garantida de compilação no pipeline de CI. | Criar `k8s/consumer-deployment.yaml` e atualizar workflow do CI para JDK 21. |

---

## 4. Blueprint Arquitetural de Refatoração

### 4.1 Estrutura de Pacotes Alvo (Hexagonal + DDD Estrito)
```
hub.pedro.jobs.consumer/
├── ConsumerApplication.java
├── app/
│   ├── port/
│   │   ├── in/
│   │   │   └── ProcessJobUseCase.java              <-- Interface de Caso de Uso (Inbound Port)
│   │   └── out/
│   │       └── NfseEmissionGateway.java            <-- Porta de Saída para Emissão Fiscal (Outbound Port)
│   └── service/
│       └── JobProcessingService.java               <-- Implementa ProcessJobUseCase
├── config/
│   ├── JacksonConfig.java
│   └── KafkaConsumerConfig.java                    <-- Configuração de DLT, ErrorHandler e ContainerFactory
├── domain/
│   ├── model/
│   │   └── Job.java                                <-- Entidade de Domínio Rica (AggregateRoot)
│   └── repository/
│       └── JobRepository.java                      <-- Porta de Saída para Persistência (Outbound Port)
└── infra/
    ├── client/
    │   └── simulated/
    │       └── SimulatedNfseEmissionAdapter.java   <-- Implementa NfseEmissionGateway com Resilience4j
    ├── database/
    │   └── postgresql/
    │       ├── entity/
    │       │   └── JobJpaEntity.java               <-- Entidade JPA com campos completos (incluindo erros e @Version)
    │       ├── mapper/
    │       │   └── JobJpaMapper.java               <-- MapStruct Domain <-> Entity
    │       └── repository/
    │           ├── PostgresJobRepository.java      <-- Implementa JobRepository
    │           └── SpringDataJobJpaRepository.java
    └── kafka/
        ├── dto/
        │   └── JobCreatedEvent.java                <-- Contrato do Evento Kafka
        └── listener/
            └── JobCreatedEventListener.java        <-- Listener Kafka Síncrono com ErrorHandler e DLT
```

### 4.2 Proposta de Código: Kafka Listener Seguro e Resiliente

```java
package hub.pedro.jobs.consumer.infra.kafka.listener;

import hub.pedro.jobs.consumer.app.port.in.ProcessJobUseCase;
import hub.pedro.jobs.consumer.infra.kafka.dto.JobCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class JobCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(JobCreatedEventListener.class);
    private final ProcessJobUseCase processJobUseCase;

    public JobCreatedEventListener(ProcessJobUseCase processJobUseCase) {
        this.processJobUseCase = processJobUseCase;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(
            topics = "job-created",
            groupId = "jobs-consumer-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(
            @Payload JobCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment
    ) {
        log.info("[KafkaListener] Consumindo evento de Job id={} [Topic: {}, Partition: {}, Offset: {}]",
                event.id(), topic, partition, offset);

        // Execução síncrona dentro da transação/fluxo do listener
        processJobUseCase.execute(event.id());

        // Confirmação explícita de offset após sucesso completo
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
        log.info("[KafkaListener] Evento do Job id={} processado e comitado com sucesso", event.id());
    }

    @DltHandler
    public void handleDlt(
            @Payload JobCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment
    ) {
        log.error("[DLT] Mensagem encaminhada para o Dead Letter Topic. Job id={}, Topic={}, Offset={}",
                event.id(), topic, offset);
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
```

### 4.3 Proposta de Código: Entidade de Persistência com Campos Completos

```java
package hub.pedro.jobs.consumer.infra.database.postgresql.entity;

import hub.pedro.jobs.consumer.domain.model.JobStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "nfse_job")
public class JobJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private JobStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "nfse_job_errors", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "error_message", columnDefinition = "TEXT")
    private List<String> errors = new ArrayList<>();

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    public void prePersist() {
        if (this.status == null) this.status = JobStatus.PENDING;
        if (this.createdAt == null) this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters...
}
```

---

## 5. Conclusão da Investigação

O módulo `jobs-consumer` possui excelente potencial didático e arquitetural, mas atualmente não está pronto para ambientes produtivos devido ao risco crítico de perda de dados no consumo assíncrono não coordenado do Kafka, à ausência de persistência de erros, ao conflito de bases H2/PostgreSQL e à ausência de suíte de testes.

As recomendações aqui estruturadas fornecem o caminho técnico exato para elevar o projeto ao padrão sênior de confiabilidade, resiliência e clareza arquitetural.
