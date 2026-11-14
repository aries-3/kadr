# Kadr

A local photo catalog with semantic search: find photos by a text description or by an example image. Everything runs locally, without the cloud.

- REST service — Spring Boot, Java 21, Maven
- Catalog — H2, embedded and file-based
- Vector index — Apache Lucene (HNSW)
- Embeddings — SigLIP 2 via ONNX Runtime, on CPU

Status: early development. The project skeleton is in place (milestone M0 of the [MVP plan](docs/mvp-plan.md)); the API endpoints answer `501 Not Implemented` for now.

## Build

Requires JDK 21; Maven is fetched by the Maven Wrapper.

```bash
./mvnw verify
```

## Run

```bash
java -jar server/target/kadr.jar
```

The service listens on `http://127.0.0.1:8080` and keeps its data in `~/.kadr`. Both can be changed:

```bash
java -jar server/target/kadr.jar --kadr.data-dir=/path/to/data --server.port=8081
```

Health check:

```bash
curl http://127.0.0.1:8080/actuator/health
```

## API

The contract is [kadr-api.yaml](server/src/main/resources/openapi/kadr-api.yaml) (OpenAPI 3). Server interfaces and DTOs are generated from it during the build.
