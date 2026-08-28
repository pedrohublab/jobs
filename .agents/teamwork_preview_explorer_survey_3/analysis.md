# Relatório de Análise Técnica — Cross-Cutting, Infraestrutura e Integração E2E
**Projeto**: Jobs — NFS-e Emission Pipeline  
**Autor**: Survey 3 Explorer (Teamwork Agent)  
**Data**: 2026-08-28  
**Nível Técnico de Referência**: Pleno → Sênior (Mentoria Técnica Arquitetural)

---

## 1. Visão Executiva

Uma auditoria aprofundada de todo o repositório `jobs` revelou que o projeto possui uma concepção inicial ambiciosa (arquitetura orientada a eventos para processamento assíncrono de alto volume com Spring Boot e Kafka), porém sofre de severas divergências entre a documentação de design e a implementação real, além de vulnerabilidades críticas de perda de dados, configurações quebradas de infraestrutura e ausência quase total de testes automatizados.

### Resumo Quantitativo dos Achados

| Severidade | Quantidade | Principais Áreas Afetadas |
|---|---|---|
| **CRÍTICO** | 6 | Perda de mensagens Kafka (`@Async` + commit antecipado), Dual-Write sem Outbox, Perda de mensagens de erro na persistência JPA, CI/CD falhando com JDK 11 em Java 21, Versão inexistente do Spring Boot (4.1.1), `jobs-consumer` com 0 testes e sem endpoints de saúde/actuator |
| **IMPORTANTE** | 6 | Ausência de módulo `jobs-shared` e duplicação massiva de código, Conflito H2 vs PostgreSQL no classpath, Ausência de migrations (Flyway/Liquibase) com `ddl-auto=update`, Inconsistência de Payload (Claim Check vs Event-Carried), Falta de manifests K8s para o Worker e Banco, `JAVA_OPTS` ignorado no Docker/Compose |
| **MELHORIA** | 5 | Otimização de cache de camadas no Dockerfile, Manifests órfãos do Cassandra em k8s/scripts, Scripts com comandos quebrados e sintaxe legada, Inconsistência de nomenclatura e pacotes vazios, Falta de métricas customizadas de negócio |

---

## 2. Análise Detalhada por Dimensão

---

### Pilar 1: Root POM e Configuração Multi-Módulo

#### 1.1. Versão Inexistente do Spring Boot no Parent POM
- **Localização**: `pom.xml` (linhas 24, 36, 84)
- **O que foi observado**:
  ```xml
  <properties>
      <spring-boot.version>4.1.1</spring-boot.version>
  </properties>
  ```
- **Explicação Educacional (Por que é um problema?)**:  
  O ecossistema Spring Boot encontra-se na linhagem 3.x (ex: 3.2.x, 3.3.x, 3.4.x). A versão `4.1.1` não existe no Maven Central. Declarar uma versão fantasma em um BOM importado impede que o Maven resolva as dependências transitivas padronizadas do Spring, causando falhas de compilação ou exigindo overrides manuais com risco de incompatibilidade binária entre bibliotecas.
- **Direção de Correção**:
  Ajustar `<spring-boot.version>` para uma versão estável oficial (ex: `3.3.3` ou `3.4.0`) compatível com Java 21 e Spring Cloud `2024.0.0`.

#### 1.2. Módulo `jobs-shared` Inexistente e Duplicação Massiva de Código
- **Localização**: `pom.xml` (linhas 11-17, 51-55) e código-fonte em `jobs-api` vs `jobs-consumer`
- **O que foi observado**:
  O `pom.xml` declara no `dependencyManagement` o módulo `hub.pedro:jobs-shared:0.0.1-SNAPSHOT`, e o `<description>` cita "Parent POM agregador dos módulos jobs-shared, jobs-producer e jobs-consumer". No entanto, o diretório e módulo `jobs-shared` NÃO existem no repositório.
  Como consequência, os desenvolvedores duplicaram integralmente entre `jobs-api` e `jobs-consumer`:
  1. Entidade de Domínio `Job.java`
  2. Enum `JobStatus.java`
  3. Interface de Domínio `JobRepository.java`
  4. Entidade JPA `Nfsejob.java`
  5. Repositório JPA `PostgresJpaRepository.java` e `PostgresJobRepository.java`
  6. DTO de Evento `JobCreatedEvent.java`
  7. Configuração `JacksonConfig.java`
- **Explicação Educacional (Por que é um problema?)**:  
  A duplicação de entidades e contratos entre módulos gera **Schema Drift** e **divergência de regras de negócio**. Por exemplo, qualquer alteração no ciclo de vida de `JobStatus` ou na estrutura de `JobCreatedEvent` precisará ser replicada manualmente em múltiplos lugares. Se um lado for atualizado e o outro esquecido, a serialização do Kafka ou a escrita no banco falhará em tempo de execução.
- **Direção de Correção**:
  Criar fisicamente o módulo `jobs-shared` (ou subdividi-lo em `jobs-domain-shared` e `jobs-contract-events`) contendo os contratos de eventos compartilhados e tipos comuns, adicionando `<module>jobs-shared</module>` no `pom.xml` raiz.

#### 1.3. Dependência Inválida de Teste em `jobs-api`
- **Localização**: `jobs-api/pom.xml` (linhas 66-69)
- **O que foi observado**:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc-test</artifactId>
      <scope>test</scope>
  </dependency>
  ```
- **Explicação Educacional (Por que é um problema?)**:  
  O Spring Boot não possui um starter chamado `spring-boot-starter-webmvc-test`. O artefato correto para toda a suíte de testes do ecossistema Spring Boot é `spring-boot-starter-test`, que já inclui JUnit Jupiter, Mockito, AssertJ, Spring Test e o slice `@WebMvcTest`. Declarar um artefato inexistente quebra o build do Maven.
- **Direção de Correção**:
  Substituir por `spring-boot-starter-test`.

#### 1.4. Actuator e Micrometer no `jobs-consumer` sem Web Starter
- **Localização**: `jobs-consumer/pom.xml` (linhas 29-38, 55-59) e `application.properties` (linhas 21-22)
- **O que foi observado**:
  O `jobs-consumer` importa `spring-boot-starter-actuator` e expõe `management.endpoints.web.exposure.include=health,info,prometheus`. Porém, o POM do consumer inclui apenas `spring-boot-starter` (base headless) e **NÃO** inclui `spring-boot-starter-web`.
- **Explicação Educacional (Por que é um problema?)**:  
  O Spring Boot Actuator só sobe um servidor HTTP embutido (Tomcat ou Netty) para responder em `/actuator/health` e `/actuator/prometheus` se houver um starter web no classpath. Sem ele, a aplicação roda puramente como um worker de linha de comando/daemon. As propriedades de porta (`server.port=8081`) e endpoints web tornam-se inoperantes. No Kubernetes e no Docker Compose, qualquer probe HTTP apontando para `http://jobs-consumer:8081/actuator/health` falhará com conexão recusada.
- **Direção de Correção**:
  Adicionar `spring-boot-starter-web` ao `jobs-consumer/pom.xml` para habilitar a porta de gerenciamento e observabilidade, ou configurar explicitamente um servidor de métricas/health leve.

---

### Pilar 2: Fluxo de Eventos E2E e Integração Kafka

```
┌─────────────────┐       (1) HTTP POST       ┌──────────────────────────────────────┐
│  Client / Load  ├──────────────────────────►│        jobs-api (Port 8080)          │
│     Script      │                           │                                      │
└─────────────────┘                           │  1. JobService.scheduleNFsProcessing │
                                              │  2. Save to DB (Status: PENDING)     │
                                              │  3. Async Send to Kafka              │
                                              └──────────────────┬───────────────────┘
                                                                 │
                                                   (2) Topic: job-created
                                                   (JSON: JobCreatedEvent)
                                                                 ▼
                                              ┌──────────────────────────────────────┐
                                              │      jobs-consumer (Worker)          │
                                              │                                      │
                                              │  1. @KafkaListener(job-created)      │
                                              │  2. Spring Kafka COMMITS OFFSET ──┐  │
                                              │  3. @Async thread pool execution  │  │
                                              │  4. DB findById(id) & save(DONE)  │  │
                                              └───────────────────────────────────┼──┘
                                                                                  │
                                                   [Perigo de Perda de Dados] ◄───┘
                                                   Se worker falhar na fila,
                                                   o offset já foi comitado!
```

#### 2.1. Perda de Dados Crítica: `@Async` no `@KafkaListener` com Auto-Commit
- **Localização**: `JobEventoConsumer.java` (linhas 32-43), `JobProcessingService.java` (linhas 24-31), `AsyncConfig.java` (linhas 10-21)
- **O que foi observado**:
  ```java
  @KafkaListener(topics = "job-created", groupId = "jobs-consumer-group")
  public void consumirEvento(String payload) {
      JobCreatedEvent evento = objectMapper.readValue(payload, JobCreatedEvent.class);
      // @Async: retorna imediatamente, processamento ocorre em thread do pool
      jobProcessingService.processarNf(evento.id());
  }
  ```
  `AsyncConfig` define um `ThreadPoolTaskExecutor` com `queueCapacity(100)` e política de rejeição padrão (`AbortPolicy`).
- **Explicação Educacional (Por que é um problema?)**:  
  1. **Commit Prematuro**: O Spring Kafka gerencia o commit do offset (por padrão em modo BATCH/RECORD). Quando o método `consumirEvento` retorna, o Spring Kafka assume que a mensagem foi entregue e processada com sucesso e comita o offset no broker Kafka.
  2. **Morte Súbita de Mensagens**: Como o método do serviço é `@Async`, o método do listener retorna em milissegundos enquanto o trabalho pesado fica na fila em memória do thread pool. Se o container for reiniciado, se a máquina virtual cair ou se ocorrer um erro não tratado no thread worker, **a mensagem já teve seu offset comitado e nunca mais será reprocessada** (entrega "at-most-once" imperfeita e perda silenciosa de notas fiscais).
  3. **Bypass de DLT e Retry do Kafka**: Mecanismos de Dead Letter Topic (DLT), `DefaultErrorHandler` e backoff exponencial do Spring Kafka deixam de funcionar, pois para o container do Kafka o listener nunca lançou exceção.
  4. **Quebra de Backpressure**: O Kafka já possui seu próprio mecanismo de concorrência (`spring.kafka.listener.concurrency=3`) e controle de taxa de leitura (`max.poll.records`). Colocar um `@Async` com fila limitada a 100 itens em cima do listener faz com que, sob carga, o pool estoure (`TaskRejectedException`), enquanto o Kafka continua enviando dados sem respeitar o ritmo real de consumo.
- **Direção de Correção**:
  Eliminar o `@Async` do fluxo do Kafka. O processamento deve ser síncrono no thread do listener (ou com Acknowledgment manual após a conclusão da tarefa). A concorrência deve ser controlada nativamente via partições do tópico Kafka e `spring.kafka.listener.concurrency`.

#### 2.2. Dual-Write Hazard no Producer (`jobs-api`)
- **Localização**: `JobService.java` (linhas 27-39)
- **O que foi observado**:
  ```java
  public UUID scheduleNFsProcessing(byte[] payload) {
      Job job = Job.createNewJob(payload);
      repository.save(job);
      JobCreatedEvent event = new JobCreatedEvent(...);
      publisher.publish(event);
      return job.getId();
  }
  ```
  Não há `@Transactional` e o envio no `KafkaJobEventPublisher` é assíncrono via `kafkaTemplate.send()`.
- **Explicação Educacional (Por que é um problema?)**:  
  Trata-se do clássico problema de **Dual-Write (Banco de Dados + Message Broker)** sem garantia de atomicidade. Se o banco persistir o job mas o Kafka estiver instável, a rede falhar ou a JVM morrer antes do envio do evento, o registro ficará órfão no banco com status `PENDING` para sempre. Por outro lado, se a transação do banco falhar após o envio, o Kafka receberá uma mensagem sobre um job que não existe.
- **Direção de Correção**:
  Adotar o padrão **Transactional Outbox Pattern** (gravar o evento na mesma transação ACID do banco de dados e usar um relay/CDC como Debezium ou Spring Scheduled Poller para publicar no Kafka) ou ao menos garantir `@Transactional` com publicação síncrona e rollback em caso de falha de publicação imediata.

#### 2.3. Antagonismo Arquitetural: Claim Check vs Event-Carried State Transfer
- **Localização**: `JobCreatedEvent.java` vs `JobProcessingService.java` (linhas 27-30)
- **O que foi observado**:
  O evento `JobCreatedEvent` trafega no Kafka serializando o `byte[] payload` completo. No entanto, o `JobProcessingService` descarta o payload recebido e executa uma query no banco de dados (`repository.findById(id)`).
- **Explicação Educacional (Por que é um problema?)**:  
  Existe uma contradição de padrões:
  - Se a intenção é **Event-Carried State Transfer**, o consumidor deveria usar os dados contidos no próprio evento para executar o processamento, sem acoplar-se ao banco de dados do produtor.
  - Se a intenção é **Claim Check**, o evento deveria trafegar apenas o ID (`UUID id`) e metadados mínimos, economizando banda de rede e armazenamento no Kafka.
  Além disso, no cenário padrão de testes com H2 em memória, como cada JVM possui seu próprio banco H2 isolado, a query `repository.findById(id)` no consumer SEMPRE falha (100% dos eventos caem no log `Job id=... não encontrado na base de dados`).
- **Direção de Correção**:
  Definir com clareza a estratégia: se o consumer precisa apenas do payload para emitir a NF-e na Sefaz, o evento deve conter o objeto de domínio estruturado (`DpsEvent`) e o consumer deve processá-lo diretamente, persistindo apenas o resultado final.

---

### Pilar 3: Banco de Dados, Modelos JPA e Persistência

#### 3.1. Perda Silenciosa de Mensagens de Erro (`errors` não persistido)
- **Localização**: `Job.java` (linhas 21-22, 46-52), `Nfsejob.java` (linhas 20-44), `PostgresJobRepository.java` (linhas 22-34)
- **O que foi observado**:
  No domínio do consumer (`Job.java`):
  ```java
  public void markAsFailed(String errorMessage) {
      this.status = JobStatus.FAILED;
      this.attempts = ...;
      this.errors.add(errorMessage);
  }
  ```
  Porém, na entidade JPA `Nfsejob.java`, **não existe coluna ou campo para `errors`**. No método `PostgresJobRepository.save(job)`, o atributo `errors` é simplesmente ignorado.
- **Explicação Educacional (Por que é um problema?)**:  
  Quando o processamento de uma NFS-e é rejeitado ou lança uma exceção, o sistema altera o status para `FAILED`, mas **a justificativa do erro é descartada da persistência**. Em ambiente produtivo, o suporte ou o cliente final não terá como saber por que a nota falhou (ex: CNPJ inválido, timeout da Sefaz, certificado expirado).
- **Direção de Correção**:
  Adicionar a coluna `mensagem_erro TEXT` ou `erros JSONB` na tabela do banco e na entidade `NfseJob`, mapeando-a no repository adapter.

#### 3.2. Ausência Total de Ferramenta de Migração e Uso Inseguro de `ddl-auto=update`
- **Localização**: `application.properties` em ambos os módulos (`spring.jpa.hibernate.ddl-auto=update`)
- **O que foi observado**:
  Não há nenhum arquivo `.sql` de migração (zero scripts Flyway ou Liquibase).
- **Explicação Educacional (Por que é um problema?)**:  
  Em arquiteturas de microsserviços onde múltiplos serviços conectam-se ao mesmo banco de dados (ou quando instâncias escalam horizontalmente), o `ddl-auto=update` do Hibernate é uma bomba-relógio:
  1. Concorrência de inicialização pode travar o catálogo do PostgreSQL (locks concorrentes de DDL).
  2. O Hibernate não exclui colunas antigas, não renomeia campos com segurança e não gerencia índices complexos.
  3. Não há rastreabilidade de versões do esquema nem possibilidade de rollback determinístico.
- **Direção de Correção**:
  Desabilitar `ddl-auto` (`spring.jpa.hibernate.ddl-auto=validate` ou `none`) e introduzir o **Flyway** com migrações versionadas em `src/main/resources/db/migration/V1__create_nfse_job_table.sql`.

#### 3.3. Configuração Conflitante H2 vs PostgreSQL
- **Localização**: `application.properties` (linhas 5-7) vs `pom.xml` (linhas 29-32)
- **O que foi observado**:
  O `application.properties` padrão aponta para `jdbc:h2:mem:jobsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`. Contudo, os arquivos `pom.xml` contêm apenas a dependência runtime do driver `org.postgresql:postgresql` e **NÃO possuem a dependência do H2 Database**.
- **Explicação Educacional (Por que é um problema?)**:  
  Se um desenvolvedor clonar o repositório e executar a aplicação localmente via IDE ou `mvn spring-boot:run` sem passar variáveis de ambiente para um Postgres externo, a inicialização falhará imediatamente com `Cannot load driver class: org.h2.Driver` ou `No suitable driver found`.
- **Direção de Correção**:
  Alinhar os profiles do Spring Boot:
  - `application.properties`: profile padrão com PostgreSQL ou profile `local` explicitamente configurado com driver H2 em escopo de teste/dev.
  - `application-docker.properties`: profile para execução conteinerizada.

#### 3.4. Nomenclatura e Organização de Pacotes Defeituosa
- **Localização**: `Nfsejob.java`, pacote `...infra.database.postgresql.persistance`
- **O que foi observado**:
  1. O pacote chama-se `persistance` (com `a` em vez de `persistence`).
  2. A classe da entidade chama-se `Nfsejob` (tudo em minúsculas após `Nfse`), violando o padrão PascalCase (`NfseJobEntity` ou `NfseJob`).
  3. O `JobStatus` está em `domain.shared` na API e em `domain.interfaces` no consumer.
  4. Existem diretórios vazios versionados: `jobs-api/.../domain/repository` e `jobs-api/.../infra/database/postgresql/specification`.
- **Explicação Educacional (Por que é um problema?)**:  
  A falta de padronização estética e estrutural degrada a manutenibilidade, dificulta o onboarding de novos engenheiros e viola os preceitos de Clean Code e convenções de nomenclatura Java/DDD.
- **Direção de Correção**:
  Corrigir a grafia dos pacotes, adotar nomes canônicos e limpar diretórios órfãos.

---

### Pilar 4: Infraestrutura, Docker, Kubernetes e CI/CD

#### 4.1. CI/CD: Pipeline do GitHub Actions Quebrado com Java 11
- **Localização**: `.github/workflows/maven-publish.yml` (linhas 20-29)
- **O que foi observado**:
  ```yaml
  - name: Set up JDK 11
    uses: actions/setup-java@v4
    with:
      java-version: '11'
      distribution: 'temurin'
  - name: Build with Maven
    run: mvn -B package --file pom.xml
  ```
- **Explicação Educacional (Por que é um problema?)**:  
  O projeto utiliza recursos do **Java 21** (records avançados, Virtual Threads/Loom readiness, compiler source/target 21 no POM). O workflow do GitHub Actions tenta compilar o projeto com **JDK 11**. O build quebra fatalmente no primeiro comando com: `Fatal error compiling: invalid target release: 21`.
  Além disso, o workflow só roda em eventos `release: [created]`, deixando de inspecionar Pull Requests e commits em branches principais (`push`).
- **Direção de Correção**:
  Atualizar `java-version: '21'` no workflow e adicionar gatilhos para `push` e `pull_request` nas branches `main` e `master`.

#### 4.2. Kubernetes: Manifests Incompletos, Sem Worker e com Cassandra Fantasma
- **Localização**: Diretório `k8s/` (`deployment.yaml`, `kafka.yaml`, `cassandra.yaml`)
- **O que foi observado**:
  1. `k8s/deployment.yaml` contém apenas o deployment do `jobs-api`. **Não existe manifest para o `jobs-consumer`**.
  2. Não há manifest para o banco de dados PostgreSQL. Em contrapartida, existe um `k8s/cassandra.yaml` com Deployment e Service para Cassandra, tecnologia que não é utilizada pelo código.
  3. No Deployment do `jobs-api`, não há declaração de variáveis de ambiente (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`), recursos (`resources.requests` / `limits`) nem probes (`livenessProbe`, `readinessProbe`).
  4. O manifest `k8s/kafka.yaml` é stateless (sem `PersistentVolumeClaim`), perdendo todas as mensagens se o Pod reiniciar.
- **Explicação Educacional (Por que é um problema?)**:  
  Tentar aplicar esses manifests em um cluster Kubernetes resultará em uma infraestrutura não funcional: o `jobs-api` subirá sem conseguir conectar ao Kafka ou banco, o worker `jobs-consumer` sequer existirá, e o Cassandra consumirá recursos do cluster inutilmente.
- **Direção de Correção**:
  Remover `cassandra.yaml`, criar os manifests para `jobs-consumer` e `postgresql` (ou StatefulSet/Helm chart), e adicionar `ConfigMaps`, `Secrets`, health probes e resource constraints adequados.

#### 4.3. Docker e Docker Compose: `JAVA_OPTS` Ignorado e Portas Fantasmas
- **Localização**: `Dockerfile` (linhas 24-46) e `compose.yaml` (linhas 23, 34, 46)
- **O que foi observado**:
  1. No `compose.yaml`, está configurado `JAVA_OPTS=-Xms256m -Xmx256m`. No `Dockerfile`, o comando de inicialização é `ENTRYPOINT ["java", "-jar", "app.jar"]`.
  2. O `Dockerfile` e o `compose.yaml` expõem a porta `8081:8081` para o `jobs-consumer`.
- **Explicação Educacional (Por que é um problema?)**:  
  1. Quando a forma `exec` do `ENTRYPOINT` é usada no Docker sem interpolação de shell, variáveis de ambiente customizadas como `JAVA_OPTS` **não são lidas pela JVM**. A flag oficial padrão do OpenJDK para injeção automática de opções de runtime é `JAVA_TOOL_OPTIONS`. Portanto, os limites de memória configurados no compose não têm efeito.
  2. O `jobs-consumer` não possui servidor HTTP embutido, logo não escuta em porta alguma. Mapear a porta 8081 no host gera confusão operacional.
- **Direção de Correção**:
  Configurar a variável de ambiente como `JAVA_TOOL_OPTIONS` e ajustar a exposição de portas conforme a necessidade real de health check.

#### 4.4. Scripts Quebrados e Inconsistentes
- **Localização**: `scripts/start-env.sh`, `scripts/compile.ps1`
- **O que foi observado**:
  `scripts/start-env.sh` tenta executar comandos CQL em um container inexistente:
  ```bash
  docker exec jobs-cassandra-1 cqlsh -e "CREATE KEYSPACE..."
  ```
  `scripts/compile.ps1` compila os módulos separadamente com `--file "$root\jobs-api\pom.xml"` em vez de compilar a partir da raiz do reactor Maven.
- **Explicação Educacional (Por que é um problema?)**:  
  Executar `start-env.sh` trava em loop infinito tentando conectar ao Cassandra que não existe no `compose.yaml`.
- **Direção de Correção**:
  Limpar referências ao Cassandra e padronizar os scripts para invocar `mvn clean package` na raiz.

---

### Pilar 5: Estratégia e Qualidade de Testes

#### 5.1. Vácuo de Cobertura de Testes (Quase 0% no Repositório)
- **Localização**: `jobs-consumer/src/test` (inexistente), `JobServiceTest.java` (0 bytes)
- **O que foi observado**:
  - `jobs-consumer`: O diretório `src/test` sequer existe. Há **ZERO testes** para o consumidor Kafka, para o serviço de processamento ou para as regras de negócio de emissão.
  - `jobs-api`: O arquivo `JobServiceTest.java` possui tamanho 0 (arquivo vazio).
  - O único teste existente em todo o projeto é `JobControllerTest.java`, contendo um único método que testa o retorno `202 Accepted` com mocks.
- **Explicação Educacional (Por que é um problema?)**:  
  Sem testes de unidade, testes de integração de mensageria e testes de repositório, o sistema é extremamente frágil a regressões. Alterações simples no payload ou nas anotações do Spring quebrarão a aplicação sem aviso prévio. A presença de dependências do Testcontainers nos POMs sem nenhum teste correspondente demonstra débito técnico severo.
- **Direção de Correção**:
  Implementar uma pirâmide de testes consistente:
  1. **Testes Unitários**: Testar as entidades de domínio `Job` (transições de status, incremento de tentativas, formatação de payloads).
  2. **Testes de Integração Web**: Testar `JobController` incluindo validação do filtro `PayloadSizeFilter` (testando requisições que excedem 100KB com retorno HTTP 413).
  3. **Testes de Integração de Mensageria com Testcontainers**: Testar a publicação e o consumo ponta a ponta com instâncias reais de Kafka e PostgreSQL via `@Testcontainers`.

---

## 3. Matriz Consolidada de Problemas e Recomendações

| ID | Classificação | Problema Identificado | Impacto Técnico | Ação Recomendada |
|---|---|---|---|---|
| **ISSUE-01** | **CRÍTICO** | `@Async` com auto-commit no `@KafkaListener` | Perda permanente de mensagens se o worker falhar ou reiniciar | Remover `@Async`, processar no thread do listener e gerenciar commits de forma determinística |
| **ISSUE-02** | **CRÍTICO** | Dual-Write sem Outbox Pattern no `JobService` | Inconsistência entre estado do PostgreSQL e tópicos do Kafka | Implementar Transactional Outbox Pattern ou publicação transacional síncrona |
| **ISSUE-03** | **CRÍTICO** | Campo `errors` do domínio ignorado na persistência | Perda do diagnóstico e motivo da falha das NFS-e rejeitadas | Adicionar coluna de erro no banco e mapear na entidade JPA |
| **ISSUE-04** | **CRÍTICO** | Workflow do GitHub Actions configurado com JDK 11 | Falha imediata de compilação do projeto Java 21 no CI | Atualizar para JDK 21 e adicionar triggers para PRs e pushes |
| **ISSUE-05** | **CRÍTICO** | Versão `4.1.1` inexistente do Spring Boot no Parent POM | Impossibilidade de resolução de dependências no ecossistema Maven | Corrigir para versão oficial estável (ex: `3.3.x` / `3.4.x`) |
| **ISSUE-06** | **CRÍTICO** | Ausência total de testes no `jobs-consumer` e `JobServiceTest` vazio | Risco crítico de regressão em produção e ausência de validação de regras | Criar suíte completa de testes unitários e de integração com Testcontainers |
| **ISSUE-07** | **IMPORTANTE** | Módulo `jobs-shared` ausente e duplicação massiva de código | Divergência de contratos de dados e manutenção dobrada | Criar módulo `jobs-shared` centralizando domínio compartilhado e DTOs de eventos |
| **ISSUE-08** | **IMPORTANTE** | Falta de migrations versionadas e uso de `ddl-auto=update` | Riscos de corrupção de esquema, locks em produção e falta de rastreabilidade | Adicionar Flyway com scripts SQL versionados e desativar `ddl-auto` |
| **ISSUE-09** | **IMPORTANTE** | Driver H2 ausente no classpath com URL H2 configurada | Falha de inicialização ao executar aplicação localmente fora do Docker | Padronizar profiles de configuração para dev local e Docker |
| **ISSUE-10** | **IMPORTANTE** | Contradição entre Claim Check e Event-Carried State Transfer | Tráfego desnecessário de bytes na rede e queries desnecessárias no banco | Padronizar o contrato do evento para conter o payload necessário ou usar Claim Check estrito |
| **ISSUE-11** | **IMPORTANTE** | Actuator no worker sem starter web | Endpoints de saúde e métricas inacessíveis via HTTP para probes do K8s | Incluir `spring-boot-starter-web` no consumer ou configurar servidor de métricas |
| **ISSUE-12** | **IMPORTANTE** | Manifests de Kubernetes sem deployment do worker e sem banco | Impossibilidade de implantar a arquitetura completa em ambiente K8s | Criar manifest do `jobs-consumer`, Postgres e remover manifest órfão do Cassandra |
| **ISSUE-13** | **MELHORIA** | `JAVA_OPTS` ignorado no Dockerfile | Limites de memória e flags de GC do compose não aplicados à JVM | Utilizar a variável nativa `JAVA_TOOL_OPTIONS` |
| **ISSUE-14** | **MELHORIA** | Build do Docker sem cache otimizado de dependências | Downloads redundantes do Maven a cada alteração de código fonte | Adicionar etapa de `mvn dependency:go-offline` antes de copiar o código |
| **ISSUE-15** | **MELHORIA** | Script `start-env.sh` referenciando Cassandra inexistente | Falha na execução de scripts de automação de desenvolvimento | Limpar referências legadas e atualizar comandos para `docker compose` |
| **ISSUE-16** | **MELHORIA** | Erros de grafia em pacotes (`persistance`) e convenção de nomes (`Nfsejob`) | Degradação da qualidade de código e violação de padrões Java | Corrigir nomes de pacotes e entidades seguindo PascalCase e convenções DDD |
| **ISSUE-17** | **MELHORIA** | Inconsistência entre documentação (`README.md`) e código | Confusão sobre rotas da API, tópicos do Kafka e modelo de dados | Reestruturar a documentação arquitetural no README.md |

---

## 4. Conclusão

O projeto possui uma base arquitetural sólida em termos de intenção de design (separação de responsabilidades, desacoplamento por mensageria e conceitos de Arquitetura Hexagonal), mas sofre de dores típicas de uma transição incompleta de spike para produção. As ações prioritárias devem focar na garantia de entrega de mensagens no Kafka (removendo `@Async` do consumer e aplicando o Outbox no producer), na correção do build/CI e no estabelecimento de um módulo compartilhado com migrations e testes automatizados.
