-- =============================================================================
-- ЛР3 — схема БД результатов микробенчмарка для Grafana.
-- Применяется при первом старте контейнера benchmark-db
-- (docker-entrypoint-initdb.d). Импорт данных: scripts/import-benchmark-to-db.sh
-- =============================================================================

CREATE TABLE IF NOT EXISTS benchmark_runs (
    run_id                     TEXT PRIMARY KEY,
    title                      TEXT NOT NULL,
    generated_at               TIMESTAMPTZ NOT NULL,
    environment                JSONB NOT NULL,
    config                     JSONB NOT NULL,
    overhead_single_ns         DOUBLE PRECISION NOT NULL,
    overhead_concurrent_ns     DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS benchmark_cases (
    id                        BIGSERIAL PRIMARY KEY,
    run_id                    TEXT NOT NULL REFERENCES benchmark_runs(run_id) ON DELETE CASCADE,
    model                     TEXT NOT NULL,
    name                      TEXT NOT NULL,
    description               TEXT,
    scenario                  TEXT NOT NULL,
    direction                 TEXT NOT NULL,
    library                   TEXT NOT NULL,
    ops_per_sample            INT NOT NULL,
    total_ops                 BIGINT NOT NULL,
    throughput_ops_per_sec    DOUBLE PRECISION NOT NULL,
    net_median_ns             DOUBLE PRECISION NOT NULL,
    mean_ns                   DOUBLE PRECISION NOT NULL,
    stdev_ns                  DOUBLE PRECISION NOT NULL,
    min_ns                    DOUBLE PRECISION NOT NULL,
    max_ns                    DOUBLE PRECISION NOT NULL,
    p50_ns                    DOUBLE PRECISION NOT NULL,
    p75_ns                    DOUBLE PRECISION NOT NULL,
    p90_ns                    DOUBLE PRECISION NOT NULL,
    p95_ns                    DOUBLE PRECISION NOT NULL,
    p99_ns                    DOUBLE PRECISION NOT NULL,
    measure_duration_ms       DOUBLE PRECISION NOT NULL,
    warmup_ops                BIGINT NOT NULL,
    settle_ops                BIGINT NOT NULL,
    measure_ops               BIGINT NOT NULL,
    teardown_ops              BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cases_run_model ON benchmark_cases(run_id, model);

CREATE TABLE IF NOT EXISTS benchmark_samples (
    run_id        TEXT NOT NULL REFERENCES benchmark_runs(run_id) ON DELETE CASCADE,
    case_name     TEXT NOT NULL,
    model         TEXT NOT NULL,
    library       TEXT NOT NULL,
    direction     TEXT NOT NULL,
    sample_index  INT NOT NULL,
    ns_per_op     DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_samples_lookup ON benchmark_samples(run_id, model, direction, library, case_name);

CREATE TABLE IF NOT EXISTS benchmark_cdf (
    run_id       TEXT NOT NULL REFERENCES benchmark_runs(run_id) ON DELETE CASCADE,
    case_name    TEXT NOT NULL,
    model        TEXT NOT NULL,
    library      TEXT NOT NULL,
    direction    TEXT NOT NULL,
    latency_ns   DOUBLE PRECISION NOT NULL,
    fraction     DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cdf_lookup ON benchmark_cdf(run_id, model, direction, library, case_name);

CREATE TABLE IF NOT EXISTS benchmark_histogram (
    run_id        TEXT NOT NULL REFERENCES benchmark_runs(run_id) ON DELETE CASCADE,
    case_name     TEXT NOT NULL,
    model         TEXT NOT NULL,
    library       TEXT NOT NULL,
    direction     TEXT NOT NULL,
    bucket_index  INT NOT NULL,
    from_ns       DOUBLE PRECISION NOT NULL,
    to_ns         DOUBLE PRECISION NOT NULL,
    count         INT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_hist_lookup ON benchmark_histogram(run_id, model, direction, library, case_name);

CREATE TABLE IF NOT EXISTS benchmark_percentiles (
    run_id      TEXT NOT NULL REFERENCES benchmark_runs(run_id) ON DELETE CASCADE,
    case_name   TEXT NOT NULL,
    model       TEXT NOT NULL,
    library     TEXT NOT NULL,
    direction   TEXT NOT NULL,
    percentile  DOUBLE PRECISION NOT NULL,
    ns          DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pct_lookup ON benchmark_percentiles(run_id, model, direction, library, case_name);

CREATE TABLE IF NOT EXISTS benchmark_comparisons (
    run_id              TEXT NOT NULL REFERENCES benchmark_runs(run_id) ON DELETE CASCADE,
    model               TEXT NOT NULL,
    direction           TEXT NOT NULL,
    scenario            TEXT,
    median_jackson_ns   DOUBLE PRECISION NOT NULL,
    median_kotlinx_ns   DOUBLE PRECISION NOT NULL,
    delta_median_pct    DOUBLE PRECISION NOT NULL,
    delta_mean_pct      DOUBLE PRECISION NOT NULL,
    delta_p99_pct       DOUBLE PRECISION NOT NULL,
    faster_library      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cmp_lookup ON benchmark_comparisons(run_id, model, direction);