# Kadr

A local photo catalog with semantic search: find photos by a text description or by an example image. Everything runs locally, without the cloud.

- REST service — Spring Boot, Java 21, Maven
- Catalog — H2, embedded and file-based
- Vector index — Apache Lucene (HNSW)
- Embeddings — SigLIP 2 via ONNX Runtime, on CPU

Status: early development, milestone M2 of the [MVP plan](docs/mvp-plan.md). Folders can be added and are scanned into the catalog; indexing and search are not there yet: the search and photo endpoints answer `501 Not Implemented`.

## Build

Requires JDK 21; Maven is fetched by the Maven Wrapper.

```bash
./mvnw verify
```

## Run

```bash
java -jar app/target/kadr.jar
```

The service listens on `http://127.0.0.1:8080` and keeps its data in `~/.kadr`. Both can be changed:

```bash
java -jar app/target/kadr.jar --kadr.data-dir=/path/to/data --server.port=8081
```

Health check:

```bash
curl http://127.0.0.1:8080/actuator/health
```

## Usage

Add a folder; it is scanned in the background, nested folders included:

```bash
curl -X POST http://127.0.0.1:8080/api/libraries \
     -H 'Content-Type: application/json' \
     -d '{"path": "/home/user/Pictures"}'
```

Track the progress, and scan again after the folder changes:

```bash
curl http://127.0.0.1:8080/api/libraries
curl -X POST http://127.0.0.1:8080/api/libraries/1/rescan
```

A scan takes JPEG, PNG and WebP files. It skips hidden folders and files, NAS and Windows service folders (`@eaDir`, `#recycle`, `$RECYCLE.BIN`, `System Volume Information`), and doesn't follow symbolic links. Files are only read, never changed. A rescan reads only new and changed files; moved and copied files keep what was computed for them.

## API

The contract is [kadr-api.yaml](app/src/main/resources/openapi/kadr-api.yaml) (OpenAPI 3). Server interfaces and DTOs are generated from it during the build.
