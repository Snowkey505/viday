-- =============================================================================
-- ЛР4 — схема результатов нагрузочных прогонов для Grafana.
--
-- Пишется автоматически scripts/import-loadtest-to-db.py из report.json,
-- который строит scripts/analyze-loadtest.py по сырым данным k6 и exporter.
-- Все таблицы идемпотентны (CREATE TABLE IF NOT EXISTS), поэтому скрипт
-- импорта применяет этот файл и к уже существующей БД ЛР3.
-- =============================================================================

CREATE TABLE IF NOT EXISTS lt_runs (
    run_id            TEXT PRIMARY KEY,
    title             TEXT NOT NULL,
    profile           TEXT NOT NULL,
    scenario          TEXT NOT NULL,
    phase             TEXT,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL,
    duration_s        DOUBLE PRECISION NOT NULL,
    status            TEXT NOT NULL,
    error_rate_target DOUBLE PRECISION NOT NULL DEFAULT 0,
    config            JSONB NOT NULL DEFAULT '{}'::jsonb,
    environment       JSONB NOT NULL DEFAULT '{}'::jsonb,
    summary           JSONB NOT NULL DEFAULT '{}'::jsonb,
    report_path       TEXT
);

CREATE TABLE IF NOT EXISTS lt_scenario_summary (
    id                BIGSERIAL PRIMARY KEY,
    run_id            TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    scenario          TEXT NOT NULL,
    phase             TEXT NOT NULL,
    scope             TEXT NOT NULL,
    operation         TEXT NOT NULL DEFAULT 'all',
    requests          BIGINT NOT NULL,
    duration_s        DOUBLE PRECISION NOT NULL,
    rps               DOUBLE PRECISION NOT NULL,
    rps_median        DOUBLE PRECISION,
    rps_min           DOUBLE PRECISION,
    rps_max           DOUBLE PRECISION,
    errors_unexpected BIGINT NOT NULL DEFAULT 0,
    errors_business   BIGINT NOT NULL DEFAULT 0,
    errors_injected   BIGINT NOT NULL DEFAULT 0,
    error_rate_pct    DOUBLE PRECISION NOT NULL DEFAULT 0,
    unexpected_error_rate_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
    mean_ms           DOUBLE PRECISION NOT NULL,
    stdev_ms          DOUBLE PRECISION NOT NULL,
    min_ms            DOUBLE PRECISION NOT NULL,
    max_ms            DOUBLE PRECISION NOT NULL,
    p50_ms            DOUBLE PRECISION NOT NULL,
    p75_ms            DOUBLE PRECISION NOT NULL,
    p90_ms            DOUBLE PRECISION NOT NULL,
    p95_ms            DOUBLE PRECISION NOT NULL,
    p99_ms            DOUBLE PRECISION NOT NULL,
    p997_ms           DOUBLE PRECISION NOT NULL,
    vus_median        DOUBLE PRECISION,
    vus_max           DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_lt_summary_run ON lt_scenario_summary(run_id, scenario, phase, scope);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lt_summary_scope
    ON lt_scenario_summary(run_id, scenario, phase, scope, operation);

CREATE TABLE IF NOT EXISTS lt_percentiles (
    run_id      TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    scenario    TEXT NOT NULL,
    phase       TEXT NOT NULL,
    scope       TEXT NOT NULL,
    operation   TEXT NOT NULL DEFAULT 'all',
    percentile  DOUBLE PRECISION NOT NULL,
    latency_ms  DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_pct_lookup ON lt_percentiles(run_id, scenario, phase, scope, operation);

CREATE TABLE IF NOT EXISTS lt_cdf (
    run_id      TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    scenario    TEXT NOT NULL,
    phase       TEXT NOT NULL,
    scope       TEXT NOT NULL,
    operation   TEXT NOT NULL DEFAULT 'all',
    fraction    DOUBLE PRECISION NOT NULL,
    latency_ms  DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_cdf_lookup ON lt_cdf(run_id, scenario, phase, scope, operation);

CREATE TABLE IF NOT EXISTS lt_histogram (
    run_id       TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    scenario     TEXT NOT NULL,
    phase        TEXT NOT NULL,
    scope        TEXT NOT NULL,
    operation    TEXT NOT NULL DEFAULT 'all',
    bucket_index INT NOT NULL,
    from_ms      DOUBLE PRECISION NOT NULL,
    to_ms        DOUBLE PRECISION NOT NULL,
    count        BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_hist_lookup ON lt_histogram(run_id, scenario, phase, scope, operation);

CREATE TABLE IF NOT EXISTS lt_timeseries (
    run_id            TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    scenario          TEXT NOT NULL,
    phase             TEXT NOT NULL,
    operation         TEXT NOT NULL DEFAULT 'all',
    bucket_ts         TIMESTAMPTZ NOT NULL,
    requests          BIGINT NOT NULL,
    rps               DOUBLE PRECISION NOT NULL,
    vus               DOUBLE PRECISION,
    errors_unexpected BIGINT NOT NULL DEFAULT 0,
    errors_business   BIGINT NOT NULL DEFAULT 0,
    errors_injected   BIGINT NOT NULL DEFAULT 0,
    mean_ms           DOUBLE PRECISION NOT NULL,
    p50_ms            DOUBLE PRECISION NOT NULL,
    p75_ms            DOUBLE PRECISION NOT NULL DEFAULT 0,
    p90_ms            DOUBLE PRECISION NOT NULL,
    p95_ms            DOUBLE PRECISION NOT NULL,
    p99_ms            DOUBLE PRECISION NOT NULL,
    p997_ms           DOUBLE PRECISION NOT NULL DEFAULT 0,
    max_ms            DOUBLE PRECISION NOT NULL
);
ALTER TABLE lt_timeseries ADD COLUMN IF NOT EXISTS operation TEXT NOT NULL DEFAULT 'all';
ALTER TABLE lt_timeseries ADD COLUMN IF NOT EXISTS p75_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE lt_timeseries ADD COLUMN IF NOT EXISTS p997_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_lt_ts_lookup ON lt_timeseries(run_id, scenario, phase, bucket_ts);
CREATE INDEX IF NOT EXISTS idx_lt_ts_operation_lookup ON lt_timeseries(run_id, operation, phase, bucket_ts);

CREATE TABLE IF NOT EXISTS lt_resources (
    id             BIGSERIAL PRIMARY KEY,
    run_id         TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    scenario       TEXT NOT NULL,
    phase          TEXT NOT NULL,
    component      TEXT NOT NULL,
    samples        INT NOT NULL,
    cpu_min        DOUBLE PRECISION,
    cpu_max        DOUBLE PRECISION,
    cpu_median     DOUBLE PRECISION,
    cpu_mean       DOUBLE PRECISION,
    ram_min        DOUBLE PRECISION,
    ram_max        DOUBLE PRECISION,
    ram_median     DOUBLE PRECISION,
    ram_mean       DOUBLE PRECISION,
    ram_limit      DOUBLE PRECISION,
    disk_read_min  DOUBLE PRECISION,
    disk_read_max  DOUBLE PRECISION,
    disk_read_median DOUBLE PRECISION,
    disk_read_mean DOUBLE PRECISION,
    disk_write_min DOUBLE PRECISION,
    disk_write_max DOUBLE PRECISION,
    disk_write_median DOUBLE PRECISION,
    disk_write_mean DOUBLE PRECISION,
    net_rx_min     DOUBLE PRECISION,
    net_rx_max     DOUBLE PRECISION,
    net_rx_median  DOUBLE PRECISION,
    net_rx_mean    DOUBLE PRECISION,
    net_tx_min     DOUBLE PRECISION,
    net_tx_max     DOUBLE PRECISION,
    net_tx_median  DOUBLE PRECISION,
    net_tx_mean    DOUBLE PRECISION,
    pids_max       INT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lt_resources_scope
    ON lt_resources(run_id, scenario, phase, component);

CREATE TABLE IF NOT EXISTS lt_resource_samples (
    run_id         TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    ts             TIMESTAMPTZ NOT NULL,
    component      TEXT NOT NULL,
    cpu_pct        DOUBLE PRECISION,
    ram_bytes      DOUBLE PRECISION,
    disk_read_bps  DOUBLE PRECISION,
    disk_write_bps DOUBLE PRECISION,
    net_rx_bps     DOUBLE PRECISION,
    net_tx_bps     DOUBLE PRECISION,
    pids           INT
);
CREATE INDEX IF NOT EXISTS idx_lt_res_samples_lookup
    ON lt_resource_samples(run_id, component, ts);

CREATE TABLE IF NOT EXISTS lt_app_samples (
    run_id               TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    ts                   TIMESTAMPTZ NOT NULL,
    threads_live         DOUBLE PRECISION,
    threads_daemon       DOUBLE PRECISION,
    threads_runnable     DOUBLE PRECISION,
    threads_waiting      DOUBLE PRECISION,
    requests_in_flight   DOUBLE PRECISION,
    executor_active      DOUBLE PRECISION,
    executor_pool        DOUBLE PRECISION,
    heap_bytes           DOUBLE PRECISION,
    gc_pause_rate        DOUBLE PRECISION,
    hikari_active        DOUBLE PRECISION,
    hikari_idle          DOUBLE PRECISION,
    http_rps             DOUBLE PRECISION,
    http_5xx_rps         DOUBLE PRECISION,
    tomcat_active        DOUBLE PRECISION,
    tomcat_rejected_rate DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_lt_app_samples_lookup
    ON lt_app_samples(run_id, ts);

-- Кривая «заявленная нагрузка -> фактическая пропускная способность» для ramp.
CREATE TABLE IF NOT EXISTS lt_ramp_curve (
    run_id            TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    target_vus        INT NOT NULL,
    requests          BIGINT NOT NULL,
    rps               DOUBLE PRECISION NOT NULL,
    errors_unexpected BIGINT NOT NULL DEFAULT 0,
    errors_business   BIGINT NOT NULL DEFAULT 0,
    unexpected_error_rate_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
    mean_ms           DOUBLE PRECISION NOT NULL,
    p95_ms            DOUBLE PRECISION NOT NULL,
    p99_ms            DOUBLE PRECISION NOT NULL,
    stable            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_lt_ramp_lookup ON lt_ramp_curve(run_id, target_vus);

CREATE TABLE IF NOT EXISTS lt_phases (
    id             BIGSERIAL PRIMARY KEY,
    run_id         TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    scenario       TEXT NOT NULL,
    phase          TEXT NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ NOT NULL,
    requests       BIGINT NOT NULL,
    rps            DOUBLE PRECISION NOT NULL,
    unexpected_error_rate_pct DOUBLE PRECISION NOT NULL,
    p50_ms         DOUBLE PRECISION NOT NULL,
    p95_ms         DOUBLE PRECISION NOT NULL,
    p99_ms         DOUBLE PRECISION NOT NULL,
    recovery_s     DOUBLE PRECISION,
    note           TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lt_phases_scope
    ON lt_phases(run_id, scenario, phase);

CREATE TABLE IF NOT EXISTS lt_findings (
    id       BIGSERIAL PRIMARY KEY,
    run_id   TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    kind     TEXT NOT NULL,
    key      TEXT NOT NULL,
    value    TEXT,
    detail   TEXT
);
CREATE INDEX IF NOT EXISTS idx_lt_findings_run ON lt_findings(run_id, kind);

-- CDF и гистограмма, разложенные по времени (5-секундные окна).
-- Заполняется scripts/backfill-loadtest-distributions.py из raw.json, чтобы
-- панели Grafana могли строить распределения на оси таймстемпов.
CREATE TABLE IF NOT EXISTS lt_cdf_ts (
    run_id      TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    phase       TEXT NOT NULL,
    operation   TEXT NOT NULL DEFAULT 'all',
    bucket_ts   TIMESTAMPTZ NOT NULL,
    latency_ms  DOUBLE PRECISION NOT NULL,
    fraction    DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_cdf_ts_lookup
    ON lt_cdf_ts(run_id, phase, operation, bucket_ts);

CREATE TABLE IF NOT EXISTS lt_histogram_ts (
    run_id       TEXT NOT NULL REFERENCES lt_runs(run_id) ON DELETE CASCADE,
    phase        TEXT NOT NULL,
    operation    TEXT NOT NULL DEFAULT 'all',
    bucket_ts    TIMESTAMPTZ NOT NULL,
    bucket_index INT NOT NULL,
    from_ms      DOUBLE PRECISION NOT NULL,
    to_ms        DOUBLE PRECISION NOT NULL,
    count        BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_hist_ts_lookup
    ON lt_histogram_ts(run_id, phase, operation, bucket_ts);
