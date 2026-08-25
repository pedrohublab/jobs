#!/usr/bin/zsh

docker-compose up -d

until docker exec jobs-cassandra-1 cqlsh -e "describe cluster" > /dev/null 2>&1; do
    echo -n "."
    sleep 3
done

docker exec jobs-cassandra-1 cqlsh -e "CREATE KEYSPACE IF NOT EXISTS my_keyspace WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"
