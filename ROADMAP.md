# Parte 2 — Guia de Implementação Senior

> **Objetivo**: Levar o projeto do estado atual (happy path com bugs) até um sistema que **funciona quando as coisas dão errado**.
> Cada tarefa tem o que fazer, por que importa, dicas de direção e como verificar.
> **Implemente você mesmo. O aprendizado está no processo.**

---

## Nível 1 — "Funciona de Verdade"

Antes de pensar em resiliência, o projeto precisa funcionar ponta a ponta sem configs quebradas.

---

### 1.1 Corrigir as Dependências e Configs do Build

**O que tá errado:**
- `spring-boot.version` é `4.1.1` — não existe. A versão estável mais recente é `3.4.x` ou `3.3.x`.
- `spring-boot-starter-kafka` não existe — o artefato correto é `spring-kafka`.
- `spring-boot-starter-webmvc-test` não existe — o correto é `spring-boot-starter-test`.
- `application.properties` aponta pra H2 (`jdbc:h2:mem:...`) mas nenhum POM tem o driver H2.

**Por que importa:**
Se um colega clonar seu repo e rodar `mvn clean package`, tem que compilar. Se rodar `docker compose up` e `java -jar`, tem que subir. Um projeto que não compila limpo não passa nem na triagem.

**O que fazer:**
1. Trocar `spring-boot.version` pra uma versão real (ex: `3.4.3`).
2. Corrigir os artifactIds no POM do `jobs-api` e `jobs-consumer`.
3. Em `application.properties` de ambos os módulos, trocar a URL padrão pra PostgreSQL:
   ```properties
   spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/jobsdb}
   ```
4. Garantir que `mvn clean package` compila sem `-DskipTests`.

**Como verificar:**
```bash
mvn clean package
# Deve compilar sem erros
docker compose up -d
java -jar jobs-api/target/*.jar
# Deve subir e logar "Started ApiApplication"
```

---

### 1.2 Mapear o Campo `errors` no JPA

**O que tá errado:**
O domínio `Job` tem `List<String> errors`. O `Nfsejob` (JPA) não tem. Quando um job falha, o motivo é jogado fora.

**Por que importa:**
Imagina que um operador de suporte precisa descobrir por que 500 notas falharam às 3h da manhã. Ele consulta o banco e vê: `status = FAILED`. Sem motivo. Sem stack trace. Nada. Isso é inaceitável em qualquer sistema real.

**O que fazer:**
Duas opções — escolha a que preferir:

*Opção A — Simples (coluna TEXT):*
Adicionar uma coluna `last_error TEXT` na entidade JPA. Guardar a última mensagem de erro.

*Opção B — Completa (histórico):*
Usar `@ElementCollection` com uma tabela `nfse_job_errors` que guarda todas as mensagens de erro com timestamp.

**Dica:** A opção B é mais "senior", mas a A resolve 90% dos casos reais. Comece pela A e evolua depois se quiser.

**Como verificar:**
1. Force um job a falhar (lance uma exceção no `processarNf`).
2. Consulte o PostgreSQL: `SELECT id, status, last_error FROM nfse_job WHERE status = 'FAILED'`.
3. A coluna `last_error` deve ter a mensagem da exceção.

---

### 1.3 Remover o `@Async` do Consumer Kafka

**O que tá errado:**
O `JobEventoConsumer` recebe a mensagem do Kafka e despacha pra uma thread secundária via `@Async`. O Kafka comita o offset imediatamente. Se a aplicação morrer, as mensagens na fila em memória são perdidas pra sempre.

**Por que importa:**
Essa é a falha mais grave do projeto. Em produção, isso significa **notas fiscais que o cliente pagou pra emitir simplesmente desaparecem**. Sem log, sem retry, sem rastro. O Kafka acha que entregou. O banco não sabe que existiam.

**O que fazer:**
1. Remover o `@Async("jobProcessingExecutor")` do `JobProcessingService.processarNf()`.
2. Remover a classe `AsyncConfig.java` (não é mais necessária).
3. O processamento agora roda na thread do Kafka listener — isso é o correto.
4. Para paralelismo, usar a concorrência nativa do Kafka:
   ```properties
   spring.kafka.listener.concurrency=3
   ```
   Isso cria 3 threads de consumo, cada uma atribuída a partições diferentes. O Kafka gerencia o balanceamento.

**Dica:** Configure o ack manual:
```properties
spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE
```
E no listener:
```java
public void consumirEvento(JobCreatedEvent evento, Acknowledgment ack) {
    jobProcessingService.processarNf(evento.id());
    ack.acknowledge(); // Só comita depois que processou
}
```

**Como verificar:**
1. Envie 10 jobs via curl.
2. Mate o consumer com `kill -9` no meio do processamento.
3. Reinicie o consumer.
4. Verifique no PostgreSQL: **todos os 10 jobs devem estar DONE ou PROCESSING** (nenhum perdido).
5. Os que estavam PROCESSING serão reprocessados pelo Kafka (re-delivery).

---

### 1.4 Integrar o Apache Cassandra

**O que tá errado:**
O Cassandra tá no `k8s/cassandra.yaml` e no `start-env.sh` mas não existe no `compose.yaml` nem no código Java.

**Por que importa:**
Esse é o seu diferencial arquitetural — Polyglot Persistence. PostgreSQL pra queries transacionais, Cassandra pra absorver o volume de documentos XML/PDF. Sem implementar, é só teoria.

**O que fazer:**
1. Adicionar o Cassandra ao `compose.yaml`:
   ```yaml
   cassandra:
     image: cassandra:4.1
     ports:
       - "9042:9042"
     environment:
       - CASSANDRA_CLUSTER_NAME=jobs-cluster
     healthcheck:
       test: ["CMD", "cqlsh", "-e", "describe keyspaces"]
       interval: 15s
       timeout: 10s
       retries: 10
   ```
2. Adicionar `spring-boot-starter-data-cassandra` no POM do `jobs-consumer`.
3. Criar a entidade `NfseDocumento` com Spring Data Cassandra:
   ```java
   @Table("nfse_documento")
   public class NfseDocumento {
       @PrimaryKey
       private String chaveAcesso;
       private String xmlDps;
       private String xmlAutorizado;
       private ByteBuffer pdfDanfse;
       private Instant createdAt;
   }
   ```
4. Criar `NfseDocumentoRepository` (interface Spring Data Cassandra).
5. No `JobProcessingService`, após a emissão (simulada), salvar o documento no Cassandra e atualizar o status no PostgreSQL.

**Como verificar:**
1. Envie um job via curl.
2. Consulte o PostgreSQL: `SELECT * FROM nfse_job WHERE status = 'DONE'` — deve ter o job com `chave_acesso`.
3. Consulte o Cassandra: `SELECT * FROM nfse_keyspace.nfse_documento WHERE chave_acesso = '...'` — deve ter o XML.
4. Rode o script de carga (`load_test.py` adaptado) com 1000 notas.
5. Confira que o Cassandra absorveu todas sem degradar a latência do PostgreSQL.

---

### 1.5 Criar o Módulo `jobs-shared`

**O que tá errado:**
`Job`, `JobStatus`, `JobCreatedEvent`, `Nfsejob`, `JacksonConfig` estão duplicados nos dois módulos. Se você mudar o enum `JobStatus` no `jobs-api` e esquecer no `jobs-consumer`, a deserialização do Kafka quebra em runtime sem erro de compilação.

**Por que importa:**
Em microsserviços reais, contratos compartilhados (eventos, enums, value objects) precisam de uma fonte única de verdade. Schema drift é uma das causas mais comuns de bugs em produção que levam horas pra diagnosticar.

**O que fazer:**
1. Criar o diretório `jobs-shared/` com seu `pom.xml` (sem Spring Boot plugin, é uma lib simples).
2. Mover pra lá: `JobCreatedEvent`, `JobStatus` e qualquer DTO/contrato de evento.
3. Adicionar `jobs-shared` como `<module>` no POM raiz e como `<dependency>` nos dois módulos.
4. Deletar as cópias duplicadas dos módulos.

**Dica:** NÃO mova a entidade `Job` (aggregate root) pro shared. Cada módulo pode ter sua própria representação de domínio. O que compartilha é o **contrato de comunicação** (eventos e enums).

**Como verificar:**
1. `mvn clean package` deve compilar tudo.
2. Mude o `JobStatus` no shared (adicione um valor `RETRYING`).
3. Compile — os dois módulos devem pegar a mudança automaticamente.

---

### 1.6 Escrever Testes com Testcontainers

**O que tá errado:**
Zero testes no `jobs-consumer`. Um teste quase vazio no `jobs-api`. Testcontainers já tá no POM mas nunca foi usado.

**Por que importa:**
Sem testes, cada correção que você fez nas tarefas acima pode ter quebrado outra coisa sem você saber. Testes com Testcontainers sobem PostgreSQL, Kafka e Cassandra reais em containers — é o mais perto de produção que você chega sem fazer deploy.

**O que fazer (mínimo de 4 testes):**

1. **Teste unitário do `Job` (domínio puro):**
   - Criar job → status deve ser PENDING.
   - Chamar `finalizar()` → status deve ser DONE, `finishedAt` preenchido.
   - Chamar `markAsFailed("motivo")` → status FAILED, errors contém "motivo".

2. **Teste de integração do `PostgresJobRepository`:**
   - `@Testcontainers` com PostgreSQL.
   - Salvar job, buscar por ID, verificar que todos os campos voltam (inclusive errors).

3. **Teste de integração do fluxo Kafka (API → Consumer):**
   - `@Testcontainers` com Kafka + PostgreSQL.
   - Publicar um `JobCreatedEvent` no tópico.
   - Aguardar (com polling) até o job aparecer como DONE no banco.

4. **Teste de integração do Cassandra:**
   - `@Testcontainers` com Cassandra.
   - Salvar um `NfseDocumento`, buscar por `chaveAcesso`, verificar XML.

**Como verificar:**
```bash
mvn test
# Todos os testes devem passar (verde)
```

---

### 1.7 Arrumar a Casa (Clean Code)

Tarefas rápidas que mostram cuidado profissional:

- [ ] Renomear pacote `persistance` → `persistence` (ambos os módulos)
- [ ] Renomear classe `Nfsejob` → `NfseJobEntity` (clareza de que é JPA)
- [ ] Padronizar idioma dos métodos (tudo em inglês: `processarNf` → `processJob`, `consumirEvento` → `onJobCreated`, `finalizar` → `complete`)
- [ ] Adicionar `spring-boot-starter-web` no `jobs-consumer` (pra Actuator funcionar)
- [ ] Corrigir o GitHub Actions: `java-version: '11'` → `'21'`, adicionar trigger em `push` e `pull_request`
- [ ] Mover topic name pra `application.properties`: `app.kafka.topic.job-created=job-created`

---

## ✅ Checkpoint do Nível 1

Quando tudo acima estiver feito, seu projeto deve:

```
✓ Compilar limpo com mvn clean package (sem -DskipTests)
✓ Subir com docker compose up -d + java -jar (sem erros)
✓ Processar jobs ponta a ponta: API → Kafka → Consumer → PostgreSQL + Cassandra
✓ Não perder mensagens quando o consumer reinicia
✓ Guardar motivo de falha quando jobs falham
✓ Ter 4+ testes passando com containers reais
✓ Código consistente em inglês, sem typos, sem duplicação
✓ CI/CD verde no GitHub Actions
```

**Só avance pro Nível 2 quando tudo acima estiver verde.**

---

---

## Nível 2 — "Não Quebra Quando as Coisas Dão Errado"

Aqui é onde o projeto vira senior de verdade. Cada tarefa abaixo resolve um **cenário de falha distribuída** real.

---

### 2.1 Transactional Outbox Pattern

**O problema que resolve:**
Hoje o `JobService` faz: `save(job)` no PostgreSQL e depois `publish(event)` no Kafka. São duas operações separadas. Se o Kafka estiver fora do ar no momento do publish, o job fica PENDING pra sempre — mas o cliente já recebeu 202.

Isso se chama **Dual-Write Problem** e é o bug mais clássico de microsserviços.

**Como funciona o Outbox:**
Em vez de publicar direto no Kafka, você salva o job E o evento na **mesma transação** do PostgreSQL:

```
BEGIN TRANSACTION
  INSERT INTO nfse_job (...)
  INSERT INTO outbox_events (id, aggregate_id, event_type, payload, created_at, published)
COMMIT
```

Um processo separado (poller ou CDC) lê a tabela `outbox_events` e publica no Kafka. Se falhar, tenta de novo — o evento tá seguro no banco.

**O que fazer:**
1. Criar tabela `outbox_events` no PostgreSQL.
2. No `JobService`, dentro de um `@Transactional`, salvar o job e o evento na outbox.
3. Criar um `OutboxPoller` com `@Scheduled(fixedDelay = 1000)` que:
   - Lê eventos não publicados (`published = false`).
   - Publica no Kafka.
   - Marca como `published = true`.
4. Remover a publicação direta do `KafkaJobEventPublisher` do fluxo principal.

**Dica avançada:** Se quiser ir além, troque o poller por CDC com Debezium. Mas o poller é perfeitamente válido e mais simples de entender.

**Como verificar:**
1. Pare o Kafka (`docker compose stop kafka`).
2. Envie um job via curl — deve retornar 202 normalmente.
3. Verifique no PostgreSQL: `SELECT * FROM outbox_events WHERE published = false` — o evento deve estar lá.
4. Suba o Kafka (`docker compose start kafka`).
5. Aguarde o poller publicar. O consumer deve processar o job normalmente.
6. **Nenhuma nota perdida, mesmo com o Kafka fora do ar.**

---

### 2.2 Controle de Concorrência Otimista (`@Version`)

**O problema que resolve:**
Dois workers pegam a mesma mensagem (re-delivery do Kafka, rebalanceamento de partições). Ambos leem o job como PENDING, ambos processam, ambos fazem UPDATE. O segundo sobrescreve o resultado do primeiro silenciosamente — **Lost Update**.

**O que fazer:**
1. Adicionar na entidade JPA:
   ```java
   @Version
   private Long version;
   ```
2. Adicionar o campo `version` no domínio `Job` e no mapeamento do repositório.
3. Quando ocorrer `OptimisticLockException`, o worker sabe que outro já processou. Basta logar e ignorar.

**Como verificar:**
1. Envie um job.
2. No `processJob`, adicione um `Thread.sleep(5000)` temporário antes do save.
3. Reinicie o consumer durante o sleep (causa re-delivery).
4. O segundo worker deve receber `OptimisticLockException` — não deve sobrescrever.

---

### 2.3 Idempotência no Consumer

**O problema que resolve:**
Kafka garante **at-least-once delivery**. Isso significa que a mesma mensagem pode chegar 2x (rebalanceamento, timeout, retry). Se o consumer não for idempotente, a mesma nota fiscal é emitida duas vezes.

**O que fazer:**
Antes de processar, verificar se o job já foi processado:
```java
public void processJob(UUID jobId) {
    Job job = repository.findById(jobId)
        .orElseThrow(() -> new JobNotFoundException(jobId));

    if (job.getStatus() == JobStatus.DONE || job.getStatus() == JobStatus.FAILED) {
        log.info("Job {} already processed (status={}), skipping", jobId, job.getStatus());
        return; // Idempotente — não reprocessa
    }

    // ... processa normalmente
}
```

**Dica:** O `@Version` (2.2) já protege contra writes concorrentes. A verificação de status protege contra processamento duplicado. Juntos, cobrem os dois cenários.

**Como verificar:**
1. Publique a mesma mensagem 3x no Kafka (pode usar `kafka-console-producer`).
2. O consumer deve processar apenas 1x. As outras 2 devem logar "already processed, skipping".
3. No banco, o job deve ter `attempts = 1`, não 3.

---

### 2.4 Retry com Backoff e Dead Letter Topic (DLT)

**O problema que resolve:**
Quando a SEFAZ tá fora do ar, o job falha. Hoje ele vai direto pra FAILED sem segunda chance. Em produção, prefeituras ficam instáveis por minutos — um retry resolve 80% das falhas.

**O que fazer:**
Configurar o Spring Kafka com retry automático e DLT:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(template);

    BackOff backOff = new ExponentialBackOff(1000L, 2.0); // 1s, 2s, 4s
    ((ExponentialBackOff) backOff).setMaxElapsedTime(30000L); // máx 30s

    return new DefaultErrorHandler(recoverer, backOff);
}
```

Mensagens que falham após todas as tentativas vão pro tópico `job-created.DLT`. De lá, podem ser reprocessadas manualmente ou por um job de reconciliação.

**Como verificar:**
1. Force o `processJob` a lançar exceção nas 2 primeiras chamadas (use um contador).
2. Observe nos logs: retry 1 (1s), retry 2 (2s), retry 3 (4s) → sucesso.
3. Force falha permanente (sempre lança exceção).
4. Verifique que a mensagem aparece no tópico `job-created.DLT`:
   ```bash
   kafka-console-consumer --topic job-created.DLT --from-beginning
   ```

---

### 2.5 Testes de Cenários de Falha

**O que diferencia senior:** não testar só o happy path, mas **provar que o sistema se recupera**.

**Testes a implementar:**

1. **Teste: Kafka fora do ar não perde jobs (Outbox)**
   - Publica evento com Kafka parado.
   - Sobe Kafka.
   - Verifica que o evento foi entregue e processado.

2. **Teste: Mensagem duplicada não reprocessa (Idempotência)**
   - Processa um job até DONE.
   - Envia a mesma mensagem de novo.
   - Verifica que o job continua DONE com `attempts = 1`.

3. **Teste: Falha temporária é retentada (Retry)**
   - Mock do gateway fiscal falha 2x, sucesso na 3ª.
   - Verifica que o job termina DONE após retries.

4. **Teste: Falha permanente vai pra DLT**
   - Mock do gateway fiscal sempre falha.
   - Verifica que a mensagem aparece no DLT.
   - Verifica que o job está como FAILED com erro preenchido.

5. **Teste: Concorrência não causa Lost Update**
   - Dois threads processam o mesmo jobId.
   - Um deve receber `OptimisticLockException`.
   - Job final deve ter dados consistentes.

---

## ✅ Checkpoint do Nível 2

```
✓ Kafka pode cair e voltar sem perder nenhuma nota (Outbox)
✓ Mesma mensagem processada 2x não duplica resultado (Idempotência)
✓ Falhas temporárias são retentadas com backoff exponencial (Retry)
✓ Falhas permanentes vão pro Dead Letter Topic com erro registrado (DLT)
✓ Dois workers no mesmo job não causam Lost Update (@Version)
✓ Testes automatizados provam cada cenário de falha
```

**Quando tudo isso estiver verde, você tem um projeto genuinamente senior.**
Aí a gente parte pro caminho triste — injeção de falhas, caos engineering, cenários de split-brain e tudo que faz um sistema distribuído sofrer de verdade. 💀

---

*Última atualização: Agosto 2026*

