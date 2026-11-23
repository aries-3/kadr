# Kadr

A local photo catalog with semantic search: find photos by a text description or by an example image. Everything runs locally, without the cloud.

- REST service — Spring Boot, Java 21, Maven
- Catalog — H2, embedded and file-based
- Vector index — Apache Lucene (HNSW)
- Embeddings — SigLIP 2 via ONNX Runtime, on CPU

Status: early development, milestones M0–M2 of the [MVP plan](docs/mvp-plan.md) are done. Folders can be added and are scanned into the catalog; their photos can be browsed in the web interface. The embedding model runs and matches the reference implementation, but the service doesn't use it yet: indexing comes with M3, and the search endpoints answer `501 Not Implemented`.

## Build

Requires JDK 21; Maven is fetched by the Maven Wrapper, Node for the web interface by the build itself (into `web/target/`).

```bash
./mvnw verify
```

## Model

Photos and queries are turned into vectors by [SigLIP 2 base](https://huggingface.co/google/siglip2-base-patch16-224), exported to ONNX by [onnx-community](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX). The model files, about 1.5 GB, aren't in the repository; they go into `models/siglip2-base-patch16-224/` of the data directory:

```bash
M=~/.kadr/models/siglip2-base-patch16-224
R=https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main
mkdir -p $M
curl -L -o $M/vision_model.onnx $R/onnx/vision_model.onnx
curl -L -o $M/text_model.onnx $R/onnx/text_model.onnx
curl -L -o $M/tokenizer.json $R/tokenizer.json
curl -L -o $M/preprocessor_config.json $R/preprocessor_config.json
```

Loaded, the model takes about 2 GB of memory. The build tests it when the files are there and that much memory is free, and skips those tests otherwise; `-Dkadr.model.dir=/path` points them to another folder.

Two measurements run on photos of your own, which stay outside the repository:

```bash
# How fast photos are decoded and embedded with various settings; how long indexing would take
./mvnw -pl ml-onnx -am verify -Dit.failIfNoSpecifiedTests=false \
    -Dit.test=EmbeddingThroughputIT -Dkadr.benchmark.photos=/path/to/photos

# The best five photos for each query in queries.txt of that folder, one query a line
./mvnw -pl ml-onnx -am verify -Dit.failIfNoSpecifiedTests=false \
    -Dit.test=EmbeddingQualityIT -Dkadr.quality.photos=/path/to/photos
```

## Run

```bash
java -jar app/target/kadr.jar
```

The service listens on `http://127.0.0.1:8080` and keeps its data in `~/.kadr`. Both can be changed:

```bash
java -jar app/target/kadr.jar --kadr.data-dir=/path/to/data --server.port=8081
```

Open http://127.0.0.1:8080/ for the web interface.

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

Browse the photos of a folder, page by page, and get a thumbnail or the original file:

```bash
curl 'http://127.0.0.1:8080/api/libraries/1/photos?offset=0&limit=100'
curl -o thumbnail.jpg http://127.0.0.1:8080/api/photos/42/thumbnail
curl -O -J http://127.0.0.1:8080/api/photos/42/file
```

Thumbnails are made on the first request and kept in `~/.kadr/thumbnails`; they can be deleted at any time.

A scan takes JPEG, PNG and WebP files. It skips hidden folders and files, NAS and Windows service folders (`@eaDir`, `#recycle`, `$RECYCLE.BIN`, `System Volume Information`), and doesn't follow symbolic links. Files are only read, never changed. A rescan reads only new and changed files; moved and copied files keep what was computed for them.

## API

The contract is [kadr-api.yaml](app/src/main/resources/openapi/kadr-api.yaml) (OpenAPI 3). Server interfaces and DTOs are generated from it during the build.

## Web interface

The `web` module holds static pages, built into the module's jar; the app serves them from it.

- `web/src/main/pages`: page templates for [Eleventy](https://www.11ty.dev). A page holds only its own markup, with HTML and [Alpine.js](https://alpinejs.dev) directives; the frame of every page, the header and the photo viewer are shared, in `_includes`. The header menu is in `_data/navigation.json`.
- `web/src/main/ts`: the page logic in TypeScript. The build generates TypeScript types from the API contract, type-checks the code against them and bundles it with esbuild, one script per page.
- `web/src/main/resources/static`: what the site serves. CSS and images are kept here; the build adds the pages (`*.html`) and the scripts (`js/`), which git ignores and `./mvnw clean` deletes. The jar takes them from here, whether Maven or you ran npm.

To work on the pages, keep the pages and scripts rebuilt on every change:

```bash
cd web
target/node/npm run watch
```

The app run from the IDE serves the files the IDE has copied to `web/target/classes`, as with any resources: after a change, build the project (Build Project), then reload the page. In IntelliJ IDEA, *On frame deactivation: Update resources* in the Spring Boot run configuration copies them on switching to the browser. `target/node` appears after the first Maven build; a locally installed npm works too.
