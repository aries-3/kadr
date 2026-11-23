# Kadr

A local photo catalog with semantic search: find photos by a text description or by an example image. Everything runs locally, without the cloud.

- REST service — Spring Boot, Java 21, Maven
- Catalog — H2, embedded and file-based
- Vector index — Apache Lucene (HNSW)
- Embeddings — SigLIP 2 via ONNX Runtime, on CPU

Status: early development, milestones M0–M4 of the [MVP plan](docs/mvp-plan.md) are done. Folders can be added; they are scanned into the catalog and their photos indexed in the background — turned into vectors by the embedding model and put into a vector index — and can be browsed in the web interface. Search is not there yet: the search endpoints answer `501 Not Implemented`.

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

The service doesn't start without them, and says which are missing. It loads the image part of the model at startup, about 0.6 GB of memory; the text part, 1.3 GB more, only with the first text query. The build tests the model when the files are there and 2.5 GB of memory is free, and skips those tests otherwise; `-Dkadr.model.dir=/path` points them to another folder.

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
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx768m -jar app/target/kadr.jar
```

Both flags are for the vector index, Lucene: `--add-modules jdk.incubator.vector` lets it use the SIMD instructions of the CPU — without it the index works, only slower, and Lucene warns about it; `--enable-native-access=ALL-UNNAMED` allows it to call native code, which Java 21 only warns about and later versions refuse. Running from the IDE, add them to the VM options; `./mvnw -pl app spring-boot:run` passes them itself.

The service listens on `http://127.0.0.1:8080` and keeps its data in `~/.kadr`. Both can be changed:

```bash
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx768m -jar app/target/kadr.jar --kadr.data-dir=/path/to/data --server.port=8081
```

Open http://127.0.0.1:8080/ for the web interface.

Photos are indexed in the background, a few batches at a time; the log reports the progress about once a minute, with the time left. While indexing, the service takes about 1.8 GB of memory with `-Xmx768m`, which is enough for it: by default Java may take a quarter of the memory for its heap alone. On a laptop CPU it takes about 0.6 s a photo: 100,000 photos take most of a day, once — later scans index only new photos. A stopped service goes on where it stopped. The settings, under `kadr.indexing` (see `application.yml`):

| Setting | Default | |
|---|---|---|
| `enabled` | `true` | `false` stops indexing; scanning goes on |
| `inference-threads` | `0` | threads of the model; `0` is one per physical core, taken as half the processors |
| `decoding-threads` | `2` | threads decoding photos alongside the model |
| `batch-size` | `16` | photos the model takes at once |
| `max-attempts` | `3` | attempts at a photo whose file can't be read; one that isn't an image fails at once |
| `max-megapixels` | `100` | larger images aren't decoded and fail |

The vectors are kept in the catalog; the vector index in `index/<model id>/` of the data directory follows it within a second. The index can be deleted while the service is stopped: on the next start it is made again from the catalog, without the model — 4,000 photos took 4 seconds on a laptop CPU; meanwhile searches find what is in it already. The same happens by itself when it is damaged or was made for another model.

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
