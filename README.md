# 🚀 Jobs API - Event-Driven Architecture

Uma API assíncrona focada em alta performance e escalabilidade, construída com **Java 21** e **Spring Boot**. Este projeto é uma Prova de Conceito (POC) que demonstra a aplicação prática de **Domain-Driven Design (DDD)** e arquitetura orientada a eventos.

## 🏗️ Arquitetura e Decisões Técnicas

- **Domain-Driven Design (DDD):** Isolamento total do domínio (Arquitetura Hexagonal / Ports and Adapters). As entidades de negócio (`Job`) são agnósticas de framework, gerenciando seu próprio estado e regras.
- **Mensageria com Apache Kafka:** Desacoplamento entre a recepção da requisição HTTP e o processamento pesado. O Controller retorna `202 Accepted` em milissegundos, enquanto um `Consumer` assíncrono processa a fila de tarefas no background, garantindo resiliência em picos de tráfego.
- **NoSQL com Apache Cassandra:** Persistência de dados altamente escalável. Focada em altíssima velocidade de escrita e disponibilidade distribuída.
- **Docker:** Infraestrutura containerizada (banco e mensageria) configurada via Docker Compose.

## 🛠️ Tecnologias Utilizadas

- **Linguagem:** Java 21
- **Framework:** Spring Boot 3
- **Mensageria:** Apache Kafka
- **Banco de Dados:** Apache Cassandra (NoSQL)
- **Construção:** Maven

## ⚙️ Fluxo de Funcionamento

1. O cliente envia uma requisição `POST /api/jobs/nfs`.
2. A aplicação cria um Job com status `PENDING` e salva no **Cassandra**.
3. Um evento é publicado no tópico `job-created` no **Kafka**.
4. O *Worker* (`JobEventConsumer`) lê o tópico em uma *Thread* isolada, processa a tarefa pesada, altera o status para `FINISHED` via regra de domínio e atualiza o banco de dados.

## 🚀 Como Executar

Certifique-se de ter o Docker e o Java 21 instalados.

```bash
# Iniciar a infraestrutura (Kafka e Cassandra)
docker compose up -d

# Rodar a aplicação
./mvnw spring-boot:run
```

Disparando uma nota:
```bash
curl -X POST http://localhost:8080/api/jobs/nfs -H "Content-Type: application/json" -d '{"numeroNota": "999"}'
```
