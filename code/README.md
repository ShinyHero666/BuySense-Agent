# BuySense Java

The maintained implementation is java-control-plane.

## Run

    cd java-control-plane
    mvn spring-boot:run

Open http://127.0.0.1:19090.

## Test

    cd java-control-plane
    mvn clean test

## Docker

    docker compose up --build

Copy .env.example to .env only when enabling an external model or retail provider. Never commit credentials.
