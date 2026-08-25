#!/usr/bin/env bash

TOTAL=5000
CONCURRENCY=50
ENDPOINT="http://localhost:8080/api/jobs/nfs"

echo "Disparando $TOTAL requisições com $CONCURRENCY threads paralelas..."

for ((i=1; i<=TOTAL; i++)); do
  (
    NF=$((1000000 + i))
    curl -s -o /dev/null -w "%{http_code}\n" -X POST "$ENDPOINT" \
      -H "Content-Type: application/json" \
      -d "{\"numeroNota\":\"$NF\", \"valor\": 1500.00, \"descricao\":\"Stress Test #$i\"}" &
  )

  # Controla o limite de processos paralelos para não travar o SO
  if (( i % CONCURRENCY == 0 )); then
    wait
  fi
done

wait
echo "Stress test finalizado!"
