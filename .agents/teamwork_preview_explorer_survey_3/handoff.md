# Handoff Report — Survey 3: Cross-Cutting, Infraestrutura, Dados e Integração E2E

**Agent ID**: `teamwork_preview_explorer_survey_3`  
**Parent Agent**: `parent` (`cc1eb7d7-aa1d-4ef0-81d9-2c17717b2188`)  
**Working Directory**: `y:\git\jobs\.agents\teamwork_preview_explorer_survey_3`  
**Data**: 2026-08-28  

---

## 1. Observation

Durante a investigação estrutural detalhada, foram observadas as seguintes evidências diretas no código e arquivos de configuração:

1. **Root POM (`pom.xml`)**:
   - Linha 24: `<spring-boot.version>4.1.1</spring-boot.version>` (versão inexistente no Maven Central).
   - Linhas 14-17: Módulos listados são apenas `<module>jobs-api</module>` e `<module>jobs-consumer</module>`.
   - Linhas 51-55: Dependência interna declarada para `hub.pedro:jobs-shared:0.0.1-SNAPSHOT`, mas o diretório `jobs-shared` não existe no repositório.
2. **Duplicação de Código entre Módulos**:
   - `jobs-api/.../domain/entity/Job.java` e `jobs-consumer/.../domain/entity/Job.java` são classes com estrutura idêntica duplicadas.
   - `JobStatus.java` duplicado em `jobs-api/.../domain/shared/` e `jobs-consumer/.../domain/interfaces/`.
   - `Nfsejob.java` JPA entity e `PostgresJobRepository.java` duplicados em ambos os módulos no pacote `...infra.database.postgresql.persistance`.
   - `JobCreatedEvent.java` duplicado em `jobs-api/.../app/port/out` e `jobs-consumer/.../app/port/in`.
   - `JacksonConfig.java` duplicado em ambos os módulos.
3. **Kafka Consumer & `@Async` (`JobEventoConsumer.java` e `JobProcessingService.java`)**:
   - `JobEventoConsumer.java` linhas 32-39:
     ```java
     @KafkaListener(topics = "job-created", groupId = "jobs-consumer-group")
     public void consumirEvento(String payload) {
         log.info("[Consumer] Evento recebido. payload={}", payload);
         try {
             JobCreatedEvent evento = objectMapper.readValue(payload, JobCreatedEvent.class);
             // @Async: retorna imediatamente, processamento ocorre em thread do pool
             jobProcessingService.processarNf(evento.id());
         } ...
     }
     ```
   - `JobProcessingService.java` linhas 24-25: `@Async("jobProcessingExecutor") public void processarNf(UUID id)`.
   - `AsyncConfig.java` linhas 12-21: `queueCapacity` fixado em 100 com política de descarte padrão `AbortPolicy`.
4. **Descarte de Mensagens de Erro na Persistência**:
   - `Job.java` (consumer) linhas 46-52 possui `public void markAsFailed(String errorMessage)` que adiciona à lista `private List<String> errors`.
   - `Nfsejob.java` (JPA Entity) linhas 20-44 **não possui** campo para `errors`.
   - `PostgresJobRepository.java` linhas 22-34 não mapeia `errors`, descartando a mensagem ao salvar.
5. **Configuração de Banco e Ausência de Migrations**:
   - Ambos `application.properties` possuem `spring.datasource.url=jdbc:h2:mem:jobsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL` e `spring.jpa.hibernate.ddl-auto=update`.
   - `jobs-api/pom.xml` e `jobs-consumer/pom.xml` não contêm dependência do driver H2.
   - Nenhum script `.sql` de Flyway ou Liquibase existe no repositório.
6. **Actuator no Consumer sem Web Starter**:
   - `jobs-consumer/pom.xml` linhas 29-38 possui `spring-boot-starter-actuator` e `spring-boot-starter` (sem `spring-boot-starter-web`).
7. **CI/CD e Kubernetes**:
   - `.github/workflows/maven-publish.yml` linhas 20-23: `java-version: '11'` enquanto `pom.xml` exige Java 21 (`<java.version>21</java.version>`).
   - `k8s/` possui `deployment.yaml` (apenas para `jobs-api` sem env vars ou probes), `kafka.yaml` (sem PVC), e `cassandra.yaml` (órfão), sem manifest para `jobs-consumer` ou `postgresql`.
8. **Scripts**:
   - `scripts/start-env.sh` linha 5: `until docker exec jobs-cassandra-1 cqlsh...` falha pois não há Cassandra no `compose.yaml`.
9. **Suíte de Testes**:
   - `jobs-consumer/src/test` não existe (0 testes).
   - `jobs-api/src/test/java/.../JobServiceTest.java` é um arquivo vazio (0 bytes).
   - `JobControllerTest.java` possui apenas 1 teste unitário web mockado com anotação `@org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`.

---

## 2. Logic Chain

1. **Da observação 1 e 2**: A intenção original expressa no `pom.xml` era usar um módulo compartilhado `jobs-shared`. Como ele não foi criado, houve duplicação manual de 7 componentes críticos entre os módulos, violando DRY e introduzindo alto risco de divergência de tipos.
2. **Da observação 3**: O Spring Kafka confirma (comita) o offset da partição quando o método `@KafkaListener` retorna. Como `consumirEvento` delega para um método assíncrono `@Async` e retorna imediatamente, o commit do Kafka ocorre antes do processamento. Se a thread em background falhar ou a JVM for terminada antes da conclusão da tarefa, a mensagem nunca será reprocessada pelo Kafka, causando perda irreversível de dados.
3. **Da observação 4**: Como a entidade JPA `Nfsejob` não possui coluna para `errors`, ao ocorrer uma falha e chamar `markAsFailed()`, o erro é gravado no objeto de domínio em memória, mas descartado pelo `PostgresJobRepository.save()`. Isso impede a rastreabilidade de falhas de emissão.
4. **Da observação 5**: O uso de `ddl-auto=update` sem Flyway/Liquibase somado ao apontamento padrão para `jdbc:h2:mem` sem o driver H2 no POM faz com que a execução local fora do Docker falhe na inicialização e que deploys em produção sofram com instabilidade no esquema do banco de dados.
5. **Da observação 6**: Sem `spring-boot-starter-web` no consumer, o Spring Boot não inicializa o Tomcat/Netty embutido, tornando impossível expor as portas HTTP de Actuator (`/actuator/health`, `/actuator/prometheus`) para probes de orquestração.
6. **Da observação 7**: Como o pipeline de CI usa JDK 11 e o código foi compilado para Java 21, qualquer execução do GitHub Actions falhará imediatamente. A falta de manifests K8s para o worker e banco impede a implantação em cluster.
7. **Da observação 8 e 9**: A suíte de testes ausente aliada a scripts com dependências inexistentes (Cassandra) evidencia um estágio intermediário de prototipação que necessita de padronização profissional para transicionar de nível pleno para sênior.

---

## 3. Caveats

- Não foram executados testes com brokers Kafka em runtime real durante esta etapa, dado o modo estritamente read-only.
- A análise considerou o estado atual do repositório no branch de trabalho.
- Não há outros caveats ou áreas não inspecionadas em relação à infraestrutura, integração E2E e multi-módulo.

---

## 4. Conclusion

O projeto apresenta 17 pontos de atenção estruturados e priorizados (6 Críticos, 6 Importantes e 5 Melhorias). As recomendações chave para o relatório arquitetural e reescrita do `README.md` consistem em:
1. Eliminar o `@Async` no consumidor Kafka para garantir entrega confiável at-least-once com retry e DLT nativos do Spring Kafka.
2. Adotar o Transactional Outbox Pattern no `jobs-api` para mitigar o problema de Dual-Write.
3. Criar o módulo compartilhado `jobs-shared` e eliminar a duplicação de entidades e eventos.
4. Adicionar persistência para o campo `errors` na entidade `NfseJob`.
5. Introduzir o Flyway com migrações versionadas e remover o `ddl-auto=update`.
6. Corrigir as versões no `pom.xml` (Spring Boot 3.3.x / Java 21) e atualizar o CI workflow para JDK 21.
7. Alinhar os manifests Kubernetes e Docker Compose removendo resquícios do Cassandra e adicionando o worker e healthchecks.
8. Desenvolver uma suíte de testes unitários e de integração com Testcontainers.

O relatório técnico completo e detalhado com explicações educacionais encontra-se disponível em:
`y:\git\jobs\.agents\teamwork_preview_explorer_survey_3\analysis.md`

---

## 5. Verification Method

Para verificar independentemente todas as constatações:

1. **Verificar Pom e Versões**:
   - Inspecionar `y:\git\jobs\pom.xml` linhas 24, 51-55.
   - Inspecionar `y:\git\jobs\jobs-api\pom.xml` e `y:\git\jobs\jobs-consumer\pom.xml`.
2. **Verificar `@Async` e Kafka Commit**:
   - Inspecionar `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\infra\kafka\consumer\JobEventoConsumer.java` linhas 32-43.
   - Inspecionar `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\app\service\JobProcessingService.java` linhas 24-31.
3. **Verificar perda do campo `errors`**:
   - Inspecionar `y:\git\jobs\jobs-consumer\src\main\java\hub\pedro\jobs\consumer\infra\database\postgresql\persistance\Nfsejob.java` e `PostgresJobRepository.java`.
4. **Verificar CI/CD Java 11**:
   - Inspecionar `y:\git\jobs\.github\workflows\maven-publish.yml` linhas 20-24.
5. **Verificar Manifests K8s e Scripts**:
   - Inspecionar `y:\git\jobs\k8s\cassandra.yaml`, `y:\git\jobs\k8s\deployment.yaml`, `y:\git\jobs\scripts\start-env.sh`.
6. **Verificar Ausência de Testes**:
   - Verificar ausência da pasta `y:\git\jobs\jobs-consumer\src\test` e o arquivo vazio `y:\git\jobs\jobs-api\src\test\java\hub\pedro\jobs\api\app\service\JobServiceTest.java`.
