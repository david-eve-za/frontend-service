# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **Shared NVIDIA rate limiter** (`NvidiaRateLimiter`, `app.nvidia.rate-limit-rpm: 30`): token bucket with full initial capacity and one permit every `60/rpm` seconds; `acquire()` blocks (virtual threads park) until quota is free. Applied at both API chokepoints — every model attempt in `NvidiaAiService.callModelWithRotation` (including retries and key rotation) and glossary extraction in `GlossaryService` — so translation, retries and extraction share one orchestrated budget across the whole pipeline. `rpm <= 0` disables the limiter.
- **Configurable parallel TTS** (`app.audio.tts-concurrency: 3`): `AudioGeneratorService` synthesizes a block's internal ~1000-char TTS fragments concurrently toward the Edge TTS endpoint and merges them in order afterwards.
- **`ChunkTranslationService`**: dedicated bean for per-chunk translation, invoked through the Spring async proxy.

### Changed

- **Virtual-thread executor**: `bookProcessingExecutor` switches from a 4–8 platform-thread `ThreadPoolTaskExecutor` to `SimpleAsyncTaskExecutor` with `setVirtualThreads(true)` (Java 21). Pipeline tasks — orchestrator runs, chunk translations, audio — park for free during NVIDIA/Edge-TTS/LLM waits instead of pinning platform threads. The `MdcTaskDecorator` is preserved, so every log line still carries `bookId`/`traceId`/`step` for the Visor de Logs.
- **Truly parallel chunk translation**: every pending chunk translates on its own virtual thread with the glossary injected, paced by the shared limiter. Per-chunk transaction semantics are unchanged (FAILED-status rollback per chunk, join-based error detection).

### Fixed

- **Sequential self-invocation defect**: `processBookChunksAsync` called `translateChunkAsync` via `this.`, bypassing the `@Async` proxy — so chunks translated sequentially despite the async annotations. Chunk translation now goes through the `ChunkTranslationService` proxy.

Measured on a two-chunk book: ~44s total with two interleaved virtual threads vs ~88s sequential. 78/78 tests green, including the new `NvidiaRateLimiter` and `ChunkTranslationService` suites.

## Prior work

Summary of the project history before this changelog existed (no releases were tagged):

- **Setup & guides**: conductor setup, Google-inspired Java/Markdown style guides, Swagger, JUnit 5, dependabot, `.gitignore` for build artifacts.
- **Book Translator UI**: top-level menu and pages, sidebar navigation, 3-step upload wizard, blockML semantic block editor.
- **Regex pattern management**: CRUD for text-cleaning patterns (form v1, table + modal v2, infinite-loading fix).
- **Novels catalog & sync**: catalog with metadata sync, novels-sync crawler, anti-Cloudflare request pacer with Retry-After handling, self-healing Cloudflare+Anubis bypass, widened retry backoff (16s–256s), 10-minute batch metadata sync.
- **Dashboard**: live pipeline metrics replacing demo widgets, aggregated stats endpoint.
- **Block-centric pipeline**: block-scoped translation with per-block chunks/clean text/audios, parallel chunk view, unified pipeline architecture, Edge-TTS audio synthesis, pipeline tracing/resume, start button on Traducción, retry budgets that only consume on genuine failures (manual runs bypass them).
- **Real-time log viewer**: in-app SSE log stream (Visor de Logs) with no external tools, plus a class-filter combo-box built from the classes present in the stream.
- **Glossary system**: series glossary with LLM extraction for consistent, non-literal translations, book-scoped terms deleted when a book is removed, curation UI by scope.
- **Maintenance**: NVIDIA SDK network-error retries, npm audit fixes, Angular 21.2.24 upgrade, lockfile normalization.
