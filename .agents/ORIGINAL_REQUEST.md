# Original User Request

## 2026-08-28T19:20:52Z

Análise estrutural profunda do projeto "Jobs — NFS-e Emission Pipeline", um projeto Java 21 / Spring Boot 4.1.1 multi-módulo com arquitetura event-driven (Kafka) e Hexagonal Architecture. O projeto é de estudo pessoal para aprimoramento técnico (nível pleno → senior). O resultado deve funcionar como uma **mentoria técnica escrita**: identificar problemas, inconsistências e oportunidades de melhoria com explicações detalhadas do *porquê* de cada ponto, e reescrever o README.md com documentação arquitetural completa.

**Não alterar nenhum arquivo de código-fonte.** O único arquivo que deve ser modificado é o `README.md` na raiz do projeto. A análise e as recomendações de correção devem ser entregues como conteúdo documentado dentro do próprio README.md.

Working directory: y:\git\jobs

Integrity mode: development

## Requirements

### R1. Análise Estrutural Completa (sem alterar código)

Realizar uma análise profunda de todo o código-fonte dos módulos `jobs-api` e `jobs-consumer`, cobrindo:

- Organização de pacotes e aderência à Hexagonal Architecture e DDD
- Consistência de naming conventions (pacotes, classes, métodos — incluindo mistura PT/EN)
- Mapeamento domínio ↔ persistência (campos perdidos, inconsistências)
- Configurações quebradas ou conflitantes (H2 vs PostgreSQL, Actuator sem web starter)
- Riscos de perda de dados (Kafka auto-commit + @Async, mapeamento JPA incompleto)
- Duplicação de código entre módulos (ausência do módulo `jobs-shared`)
- Qualidade e cobertura de testes
- Infraestrutura (Dockerfile, compose.yaml, k8s manifests, CI/CD pipeline)

Cada problema identificado deve ser acompanhado de uma **explicação educacional** do porquê é um problema e qual seria a abordagem correta — como um mentor/professor faria.

### R2. Reescrita Completa do README.md

Sobrescrever o arquivo `README.md` existente na raiz do projeto com documentação arquitetural completa em **Português (BR)**, contendo no mínimo:

- Descrição e propósito do projeto
- Diagrama de arquitetura (ASCII ou descrição textual clara do fluxo)
- Estrutura de módulos com explicação de cada camada (domain, app, infra, web, config)
- Modelo de dados (PostgreSQL e Kafka events)
- Decisões técnicas e trade-offs (por que Kafka, por que Hexagonal, por que dois bancos no plano original)
- Como executar (Docker Compose + compilação + exemplos de requests)
- Seção de "Problemas Conhecidos e Oportunidades de Melhoria" — lista priorizada dos issues encontrados na análise, cada um com:
  - O que está errado
  - Por que é um problema (impacto técnico)
  - O que fazer para corrigir (direção, não implementação exata)
  - Nível de prioridade (crítico / importante / melhoria)
- Roadmap de evolução técnica sugerido

### R3. Preservação do Código

Nenhum arquivo `.java`, `.xml`, `.yaml`, `.properties`, `.py`, `.sh`, `.ps1` ou qualquer outro arquivo de código/configuração deve ser modificado. Apenas o `README.md` na raiz do projeto deve ser alterado.

## Acceptance Criteria

### Análise de Qualidade
- [ ] A análise cobre ambos os módulos (`jobs-api` e `jobs-consumer`) e a infraestrutura (Docker, k8s, CI/CD)
- [ ] Pelo menos 10 problemas ou oportunidades de melhoria distintos são identificados e documentados
- [ ] Cada problema inclui explicação do porquê é um problema e a direção de correção
- [ ] Os problemas são priorizados (crítico / importante / melhoria)

### README.md
- [ ] O arquivo `y:\git\jobs\README.md` foi sobrescrito com o novo conteúdo
- [ ] Contém diagrama de arquitetura do fluxo event-driven
- [ ] Contém descrição da estrutura de pacotes e camadas hexagonais
- [ ] Contém modelo de dados (tabelas PostgreSQL + eventos Kafka)
- [ ] Contém instruções de execução local funcional
- [ ] Contém seção de problemas conhecidos com pelo menos 10 itens priorizados
- [ ] Contém roadmap de evolução
- [ ] Está escrito inteiramente em Português (BR)
- [ ] Tem qualidade de documentação profissional (legível, bem formatado, com seções claras)

### Preservação de Código
- [ ] Nenhum arquivo além de `README.md` foi modificado, criado ou deletado no diretório do projeto

