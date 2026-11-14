# Kadr MVP — implementation plan

> Status: draft for review.

## 1. Goal

Build the MVP of Kadr — a local REST service that:

- **F1** indexes a folder of photos, incrementally and surviving restarts;
- **F2** finds photos by a text query, Russian included;
- **F3** finds photos similar to an uploaded image.

Local only, CPU only, no UI. The MVP is done when the acceptance criteria in §6 pass on the owner's own photos.

Out of scope: video, HEIC and RAW, thumbnails, EXIF metadata and search filters, folder watching, removing folders from the index, tags and albums, Docker, authentication.

## 2. Design summary

| Area | Decision |
|------|----------|
| Platform | Java 21, Maven multi-module, Spring Boot 4.x (Spring MVC) |
| Coordinates | groupId `com.teacode.kazakov.kadr`, artifacts `kadr-*`, base package `com.teacode.kazakov.kadr` |
| Catalog | H2 embedded, file-based; Flyway; Spring `JdbcClient` |
| Vector index | Apache Lucene 10.x, HNSW, `DOT_PRODUCT` over L2-normalized vectors. Derived from H2 and rebuildable from it |
| Model | SigLIP 2 base (`google/siglip2-base-patch16-224`), 768 dimensions, ONNX Runtime. Files are placed manually into `~/.kadr/models/siglip2-base-patch16-224/` |
| API | OpenAPI contract-first; errors as RFC 9457 Problem Details; listens on `127.0.0.1` |
| Formats | JPEG, PNG, WebP (ImageIO + TwelveMonkeys) |

```
kadr/
├── domain/                 kadr-domain           entities and ports; no frameworks
├── application/            kadr-application      use cases
├── adapters/
│   ├── persistence-h2/     kadr-persistence-h2   JdbcClient repositories, Flyway migrations
│   ├── index-lucene/       kadr-index-lucene     VectorIndex on Lucene
│   ├── ml-onnx/            kadr-ml-onnx          EmbeddingModel on ONNX Runtime
│   ├── imaging/            kadr-imaging          decoding, EXIF orientation
│   └── fs/                 kadr-fs               folder walking, identity hash
└── server/                 kadr-server           Spring Boot application, REST, wiring
```

`domain` and `application` don't depend on Spring; adapters don't depend on each other.

## 3. Approach

- **Risky part first.** The model is the one component that can fail silently: wrong preprocessing produces plausible-looking but useless vectors. Quality on Russian and CPU throughput are also unknown. M1 therefore validates the model in Java against the official Python implementation before anything depends on it.
- **Every milestone ends in a working state**: `./mvnw verify` is green, the result can be checked by hand, the changes are staged. The owner reviews and commits.
- **Tests come with the code.** Unit tests use a fake model and need no model files. Integration tests (`*IT`) that need the real model are skipped when it is absent.
- **No private data in the repository**: personal photos, model files and the data directory stay outside it.

## 4. Prerequisites from the owner

| What | Needed by |
|------|-----------|
| JDK 21 (Maven comes with the Maven Wrapper) | M0 |
| Model files from `onnx-community/siglip2-base-patch16-224-ONNX`: `onnx/vision_model.onnx`, `onnx/text_model.onnx`, `tokenizer.json`, `preprocessor_config.json` → `~/.kadr/models/siglip2-base-patch16-224/` | M1 |
| Python 3 with `torch` (CPU), `transformers`, `pillow` — once, to produce reference vectors | M1 |
| 20–30 own photos and 5–10 queries for an early quality check | M1 |
| Acceptance set: ~200 own photos in ~10 category folders plus queries in Russian and English (format in M6) | M6; can be prepared any time |

## 5. Milestones

| # | Milestone | What can be checked at the end |
|---|-----------|--------------------------------|
| M0 | Project skeleton | Builds and starts, `/actuator/health` is UP, API contract is ready for review |
| M1 | Embedding model | Java vectors match the Python reference; indexing speed on this machine is known |
| M2 | Catalog and scanning | F1 without ML: a folder is added and scanned, progress is visible |
| M3 | Indexing | Embeddings are computed in the background, survive restarts and broken files |
| M4 | Vector index | Lucene index with catch-up after a crash and rebuild from H2 |
| M5 | Search API | F2 and F3 work end to end |
| M6 | Acceptance | All criteria pass on the owner's photos; README covers installation and usage |

Order: M0 → M1 → M2 → M3 → M4 → M5 → M6. M1 and M2 are independent: if the model prerequisites aren't ready yet, M2 goes first.

### M0. Project skeleton

Tasks:

1. Root `pom.xml` (`kadr-parent`):
   - Java 21 via `maven.compiler.release`;
   - parent `spring-boot-starter-parent`: dependency and plugin versions managed by Spring Boot; versions of all other dependencies (Lucene, ONNX Runtime, DJL tokenizers, TwelveMonkeys, metadata-extractor) in one place;
   - plugins: Failsafe for `*IT` tests, openapi-generator, Spring Boot plugin;
   - Maven Enforcer: Java 21, Maven 3.9+.
2. Maven Wrapper; `.gitignore` (`target/`, `.idea/`, `*.iml`, `*.onnx`, …).
3. All eight modules with the dependency rules from §2.
4. `kadr-server`:
   - application class, `application.yml`, `@ConfigurationProperties` record for `kadr.*`;
   - data directory created on startup;
   - H2 datasource (`DB_CLOSE_ON_EXIT=FALSE`), Flyway, Actuator health, Problem Details.
5. OpenAPI contract `server/src/main/resources/openapi/kadr-api.yaml` with every MVP endpoint and schema. Server interfaces are generated from it; endpoints answer `501 Not Implemented` until their milestone.
6. README: how to build and run.

Done when:

- `./mvnw verify` passes; `java -jar` starts, creates `~/.kadr/catalog.mv.db`, and `/actuator/health` answers UP;
- the owner has reviewed the API contract — the API is fixed before it is implemented.

Risk: openapi-generator's Spring templates may lag behind Spring Boot 4 (Spring 7, Jackson 3). This is checked first. Fallback: hand-written controller interfaces, kept in sync with the contract by tests that validate requests and responses against it.

### M1. Embedding model

Tasks:

1. `kadr-domain`: the `EmbeddingModel` port.
2. `kadr-imaging`:
   - detect the format by content, not by extension;
   - read dimensions from the header and reject images over the megapixel limit before decoding;
   - decode with subsampling down to about twice the model input size;
   - apply EXIF orientation;
   - convert CMYK, grayscale, 16-bit, palette and alpha images to 8-bit RGB the same way the reference does.
3. `kadr-ml-onnx`:
   - image preprocessing: resize to 224×224 without cropping, bilinear with antialiasing like PIL (a plain `Graphics2D` bilinear resize doesn't antialias), scale to [−1, 1], NCHW layout;
   - text: lowercase, Gemma tokenizer from `tokenizer.json` via DJL, pad or truncate to exactly 64 tokens;
   - two ONNX Runtime sessions (image and text); input and output names inspected and the 768-dimension output validated at startup; batching; L2 normalization;
   - files loaded from `kadr.model.dir`; if any is missing, startup fails with a message listing what to put where.
4. Reference parity:
   - `tools/reference/` — a Python script with a pinned `requirements.txt` that computes embeddings with `transformers` for the test images and texts, applying EXIF orientation like the Java side, and writes them to JSON;
   - about six small CC0 or generated test images: landscape, portrait with an EXIF orientation tag, PNG with alpha, grayscale, CMYK JPEG, WebP;
   - test texts in Russian and English, one of them longer than 64 tokens;
   - the images and the JSON are committed as test resources. `OnnxSiglipModelIT` compares Java and Python vectors by cosine similarity: target ≥ 0.999 for texts and ≥ 0.99 for images; final thresholds are set after the first run.
5. Benchmark `EmbeddingThroughputIT` on real photos (path from a system property): decoding and inference speed for batch sizes 1, 8, 16, 32 and several thread counts; peak memory. The numbers set the defaults for `decode-threads`, `inference-threads` and `batch-size`, and estimate the time to index the whole archive.
6. Early quality check: 20–30 own photos, 5–10 queries in Russian and English, top-5 printed per query.
7. Run DJL offline: set the system property `ai.djl.offline=true` before the first tokenizer is created. Otherwise `HuggingFaceTokenizer.newInstance` calls DJL telemetry: it probes the EC2 metadata address `169.254.169.254` (1 s timeout) and, on EC2, reports the instance id to `djl-telemetry-*.amazonaws.com`. Offline mode also forbids native-library downloads; the library is taken from the tokenizers jar (bundled for linux-x86_64, linux-aarch64, osx-aarch64, win-x86_64). Verify that ONNX Runtime and the tokenizer start with the network off.

Done when: the parity test passes; the throughput numbers are recorded; the quality check looks sane — or the owner decides to switch to a larger SigLIP 2 variant before going further.

### M2. Catalog and scanning (F1 without ML)

Tasks:

1. Flyway `V1`: the whole schema — `library_root`, `photo`, `photo_file`, `enrichment`, `embedding`, `embedding_seq`.
2. Domain entities and ports; `kadr-persistence-h2` repositories on `JdbcClient` with batched writes.
3. `kadr-fs`:
   - recursive walk that doesn't follow symlinks, skips hidden and service folders (`.*`, `@eaDir`, `#recycle`) and takes only `jpg`, `jpeg`, `png`, `webp`, case-insensitive;
   - identity hash: SHA-256 over the size, the first 64 KiB and the last 64 KiB; the whole file if it is 128 KiB or smaller.
4. Use cases and REST:
   - `POST /api/libraries`: validation, `toRealPath()`, `409` if the path is already added; nested folders are allowed;
   - `GET /api/libraries`, `GET /api/libraries/{id}`, `POST /api/libraries/{id}/rescan`.
5. Scanning:
   - unchanged size and mtime → the file isn't read; new or changed → hash → find or create `photo` → upsert `photo_file` by absolute path;
   - files under the folder not seen in this scan are marked `missing` (a range on the path prefix, not `LIKE`);
   - unavailable folder → status `UNAVAILABLE`, nothing is marked missing;
   - a photo without an embedding of the current model gets a task in `enrichment` (processed from M3 on);
   - one scan at a time; a scan interrupted by a restart starts again.
6. Status and progress: counts of files, indexed, pending and failed; status `SCANNING → INDEXING → READY`, or `UNAVAILABLE`. Until M3 the status stays `INDEXING`.

Tests: scan scenarios on temporary folder trees — new, unchanged, modified, moved, deleted, copied files; nested folders added in both orders; unavailable folder; restart mid-scan. API validation and error codes.

Done when: adding a real folder fills the catalog and the queue; a rescan finds exactly the changes; adding a subfolder of an indexed folder creates no new work.

### M3. Indexing

Tasks:

1. Queue in H2:
   - claim a batch `PENDING → RUNNING`; mark `DONE`; retry up to `max-attempts`, then `FAILED` with the error;
   - on startup: `RUNNING → PENDING`, create missing tasks for the active model, re-queue tasks of an older version.
2. The `embedding` pipeline: decoding threads → bounded queue → batches → inference → **a single writer thread** that stores the vector with a new `seq` and marks the task `DONE` in one transaction.
3. A photo is read from any of its present files; if none is present, the task fails.
4. Graceful shutdown: stop claiming; unfinished tasks return to `PENDING`.
5. Progress in the log: processed count, rate, ETA.

Tests: with a fake model — queue transitions, retries, failure isolation (broken JPEG, text file named `.jpg`), restart recovery, re-queue on a version change. One IT with the real model on the test images.

Done when: acceptance criteria 1–3 pass; the status reaches `READY`.

### M4. Vector index

Tasks:

1. `LuceneVectorIndex` in `kadr-index-lucene`:
   - index in `~/.kadr/index/<model-id>/`; a document holds `photo_id` and a `KnnFloatVectorField` with `DOT_PRODUCT`;
   - `SearcherManager` refreshed about once a second;
   - commits every N seconds or M documents and on shutdown, with `modelId`, `dimension`, `schemaVersion` and `lastSeq` in the commit user data.
2. Scores: Lucene returns `(1 + cos) / 2`; the API returns the cosine.
3. The writer thread upserts into Lucene after the H2 transaction.
4. Startup: open and validate the index. Missing, corrupt or mismatching → rebuild from H2 in the background, with search working over what is already added. Otherwise → catch up the rows with `seq > lastSeq`.
5. `--add-modules jdk.incubator.vector` (SIMD in Lucene) in the `spring-boot:run` configuration and in the README run command.

Tests on `ByteBuffersDirectory`: upsert and update; score conversion; commit user data; crash simulation (rows in H2, Lucene not committed → catch-up); deleted index → rebuild; dimension mismatch → rebuild. Recall sanity: HNSW top-10 against brute force on 10,000 random vectors, ≥ 0.95.

Done when: acceptance criterion 5 passes; killing the process during indexing loses nothing.

### M5. Search API

Tasks:

1. Search use case: query vector → Lucene kNN with `k = max(2 × limit, 100)` → files from H2 in one query → photos with all files missing dropped → retry with a larger `k` if fewer than `limit` remain → top `limit`.
2. `POST /api/search/text`: query not blank and at most 1000 characters; `limit` 1–200, default 20.
3. `POST /api/search/image`:
   - over the size limit → `413`; unsupported format → `415`; too many pixels or undecodable → `400`;
   - the same decoding and preprocessing as indexing; the upload isn't stored.
4. `GET /api/photos/{id}/file`: streams a present file of the photo with its content type; serves only files from the catalog.

Tests: API and error codes with a fake model and index; an end-to-end IT with the real model — an image from the catalog finds itself first with a score above 0.99, a text query finds the expected test image.

Done when: acceptance criteria 7 and 8 pass; the README has `curl` examples.

### M6. Acceptance

Tasks:

1. Acceptance set format — kept outside the repository:

   ```
   <set>/
   ├── dogs/
   ├── beach/
   ├── …
   └── queries.yaml
   ```

   ```yaml
   - category: dogs
     ru: собака
     en: a dog
   ```

2. `AcceptanceIT` under the Maven profile `acceptance`: starts the service with the real model and a temporary data directory, adds the set, waits for `READY`, runs every query in both languages, computes precision@10 and p95 latency, writes a report to `target/`.
3. Run all criteria from §6 — by hand where automation isn't worth it (restart, crash).
4. Index the owner's real archive: total time, memory, review of failed files; tune the defaults.
5. README: model installation, run command, API examples.

Done when: all criteria in §6 pass, or the owner accepts the deviations.

## 6. Acceptance criteria

1. **Indexing**: after adding a folder of N photos, the status reaches `READY` and `indexed + failed = N`.
2. **Broken files**: a damaged JPEG and a text file named `.jpg` end up `FAILED` with a reason; the rest are indexed.
3. **Restart**: stopping in the middle of indexing and starting again continues the work; computed embeddings aren't recomputed.
4. **Incrementality**: a rescan without changes creates no tasks; after adding files only they are indexed; a file moved within the folder isn't recomputed.
5. **Index rebuild**: deleting `index/` while the service is stopped → after start the index is rebuilt from H2 without inference, and search results are the same.
6. **Text search**: on ~200 own photos in ~10 categories, a query in Russian returns at least 7 photos of the right category in the top 10 (threshold to be confirmed by the first measurements). The same queries in English for comparison.
7. **Image search**: a photo from the catalog finds itself first with a score above 0.99; another shot of the same scene finds the original in the top 5.
8. **API errors**: an invalid path, a duplicate folder, an empty query, an oversized file and a non-image get the documented status codes as Problem Details.
9. **Speed (guideline)**: text search over 100,000 photos — p95 under 300 ms on CPU after warm-up.
10. **Nested folders**: adding a subfolder of an indexed folder → `READY` at once with no new tasks; adding a parent folder → only files outside the existing folder are indexed; a file appears once in search results.

## 7. Risks

| Risk | Shows up in | Mitigation |
|------|-------------|------------|
| Preprocessing differs from training, silently degrading search | M1 | Parity test against the official Python implementation |
| Quality on Russian too low | M1 early check, M6 | Larger SigLIP 2 variant (L, So400m) |
| Indexing too slow on CPU | M1 benchmark | Tune threads and batch size; int8 models and libvips after the MVP |
| openapi-generator not ready for Spring Boot 4 | M0 | Hand-written interfaces validated against the contract in tests |
| DJL tokenizers reaches the network: telemetry on tokenizer creation, native-library download on platforms not bundled in the jar | M1 | `ai.djl.offline=true`; verify an offline start |
| Memory blow-up on large images | M1, M3 | Pixel limit checked from the header, subsampled decoding, bounded queues |

## 8. Working agreement

- The rules in [AGENTS.md](../AGENTS.md) apply: English in the repository; the agent stages its changes and never commits — the owner does.
- One milestone is one reviewable change set. A large milestone may be split into several, each with a green `./mvnw verify`.
- After each milestone the agent reports what was done, how to check it, and any deviations from this plan; the plan is updated when reality diverges from it.
