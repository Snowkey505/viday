.PHONY: build run run-console clean test test-offline test-fixtures-demo test-integration test-e2e coverage allure allure-serve sonar-up sonar-down sonar benchmark-db benchmark-db-copy benchmark benchmark-local benchmark-import benchmark-dashboard grafana-up grafana-down grafana-logs stand-up stand-down stand-logs stand-ps app-up app-down reset-test-db e2e-curl traffic-demo e2e-traffic wireshark ci-runner loadtest loadtest-all loadtest-fail loadtest-matrix loadtest-import loadtest-dashboard

build:
	cd docker && make build && cd .. && ./gradlew build

run:
	java -jar $$(find presentation/build/libs -name "*.jar" ! -name "*-plain.jar")

run-console:
	java -jar $$(find presentation/build/libs -name "*.jar" ! -name "*-plain.jar") --spring.main.web-application-type=none --viday.console.enabled=true

clean:
	./gradlew clean

test:
	./gradlew test coverageSummary

test-offline:
	./gradlew testOffline

# Только демо фикстур (@Tag("fixtures-demo")) — вне обычного прогона (22 теста)
test-fixtures-demo:
	./gradlew testFixturesDemo

# ---- ЛР2: тестовый стенд (отдельный инстанс хранилищ, Требования 3-4) ----
#   make stand-up      -> docker compose -p viday-test -f docker/docker-compose.test.yml up -d
# Имя проекта задаётся явно (-p): параллельные разработчики указывают своё
# (docker compose -p viday-test-alice ...), иначе их стенды не пересекаются
# (Требование 15).
stand-up:
	docker compose -p viday-test -f docker/docker-compose.test.yml up -d

stand-down:
	docker compose -p viday-test -f docker/docker-compose.test.yml down -v

stand-logs:
	docker compose -p viday-test -f docker/docker-compose.test.yml logs -f

stand-ps:
	docker compose -p viday-test -f docker/docker-compose.test.yml ps

# Интеграционные тесты на реальном PostgreSQL стенда (Требования 2-4, 6)
test-integration:
	./gradlew :infrastructure:testIntegration

# E2E-тест: приложение + реальный HTTP поверх стенда (Задание 3, Требование 6)
test-e2e:
	./gradlew :presentation:testE2E

# Приложение в контейнере (для демо curl + захвата трафика, Задание 5)
app-up:
	docker compose -f docker/docker-compose.app.yml up -d --build

app-down:
	docker compose -f docker/docker-compose.app.yml down

# Принудительный откат тестового хранилища (Требования 4, 10, 14)
reset-test-db:
	./scripts/reset-test-db.sh

# Имитация E2E-действий средством отправки запросов (Задание 5)
e2e-curl:
	./scripts/e2e-curl.sh

# Имитация E2E-действий + захват трафика tcpdump (Задание 5)
traffic-demo:
	./scripts/traffic-capture.sh

# E2E одним касанием + pcap для Wireshark:
#   make e2e-traffic
# 1) поднимает стенд и контейнер приложения (viday_api слушает localhost:8080);
# 2) прогоняет E2E-сценарий под tcpdump;
# 3) сохраняет traffic/viday-e2e-<ts>.pcap и печатает HTTP-дайджест.
e2e-traffic: stand-up
	# Образ собирается один раз; при повторных запусках просто поднимается
	docker image inspect viday-test-app-api:latest >/dev/null 2>&1 || \
	  docker compose -f docker/docker-compose.app.yml build
	docker compose -f docker/docker-compose.app.yml up -d
	./scripts/traffic-capture.sh

# Открыть последний pcap в Wireshark (тела запросов видны: HTTP, не HTTPS):
#   make wireshark
wireshark:
	wireshark $$(ls -t traffic/*.pcap | head -1)

# Контейнер запуска тестов + стенд в одной сети (Требование 1):
#   make ci-runner    -> docker compose -p viday-ci -f docker/docker-compose.ci.yml run --rm test-runner full
ci-runner:
	docker compose -p viday-ci -f docker/docker-compose.ci.yml run --rm test-runner full

# Демонстрация «разных Dockerfile» и Задания 5 в одной docker-сети:
# поднимается контейнер API (Dockerfile.app), e2e-runner (docker/e2e/Dockerfile)
# гоняет curl-сценарий e2e-curl.sh прямо в HTTP реального приложения;
# postgres (docker/postgres/Dockerfile со схемой) — сервис стенда.
#   make ci-e2e-curl
ci-e2e-curl:
	docker compose -p viday-ci -f docker/docker-compose.ci.yml up -d --build api
	docker compose -p viday-ci -f docker/docker-compose.ci.yml run --rm e2e-runner
	docker compose -p viday-ci -f docker/docker-compose.ci.yml down -v

coverage:
	./gradlew test coverageSummary

allure:
	./gradlew test allureReports

# Run unit tests, build the aggregated report and serve it on http://localhost:<port>
# Usage:            make allure-serve           (нужен поднятый стенд: make stand-up)
# Custom port:      make allure-serve ALLURE_PORT=8080
allure-serve:
	# :allureServe прогоняет ВСЕ этапы (unit+integration+e2e) и открывает
	# агрегированный отчёт build/reports/allure-report-ci в браузере.
	./gradlew :allureServe -PallurePort=$(if $(ALLURE_PORT),$(ALLURE_PORT),19999)

# ---- SonarQube (tree-view coverage report) ----
#   make sonar-up                    -> start server at http://localhost:9002
#   make sonar SONAR_TOKEN=<token>   -> run tests + JaCoCo + analysis
sonar-up:
	docker compose -f docker/docker-compose.sonar.yml up -d
	@echo "SonarQube starting... wait for http://localhost:9002 (admin/admin on first login, password must be changed)."
	@echo "Generate an analysis token: My Account -> Security -> Generate Token, then: make sonar SONAR_TOKEN=<token>"

sonar-down:
	docker compose -f docker/docker-compose.sonar.yml down

sonar:
	./gradlew sonar -PsonarToken=$(SONAR_TOKEN)

# ---- ЛР3: микробенчмарк (собственный харнесс: warmup/teardown, статистика,
# перцентили, гистограммы). Сравнение Jackson 3 vs kotlinx.serialization на
# ТРЁХ моделях (x1/x4/x8). Отчёт и ВСЕ графики — в Grafana.
#   make benchmark        -> каждый прогон = отдельный docker-образ (Требование 6),
#                            закрепление на 1 ядре (Требование 4), результаты +
#                            автоимпорт в Grafana DB (если стенд поднят)
#   make benchmark-local  -> локальный прогон через Gradle (для быстрой проверки)
#   make grafana-up       -> стенд визуализации: Grafana http://localhost:3000
#                            (admin/admin) + PostgreSQL с результатами
#   make benchmark-import -> импорт последнего report.json в Grafana DB
#   make benchmark-dashboard -> перегенерировать дашборд benchmark.json
benchmark:
	./scripts/run-benchmark.sh

benchmark-local:
	./gradlew :benchmark:run --args="--samples 1000 --batch 256 --out benchmark/build/reports/benchmark/local"

# ---- ЛР4: нагрузочное тестирование (ramp/stress/soak + 10%/20% ошибок) ----
#   make loadtest SCENARIO=ramp CPU=1 RAM=512 COROUTINES=200 THREADS=4
#   RAM задаётся в MiB; APP_HEAP_MB по умолчанию равен половине RAM.
#   make loadtest-matrix CPUS="1 2" RAMS="512 1024" COROUTINES="100 200"
#   make loadtest-all             -> полная серия из 6 прогонов
#   make loadtest-import          -> импорт последнего report.json в Grafana DB
#   make loadtest-dashboard       -> перегенерировать обе доски:
#                                    loadtest-lr4-auth и loadtest-lr4-public
loadtest:
	SCENARIO="$(if $(SCENARIO),$(SCENARIO),smoke)" \
	GENERATOR="$(if $(GENERATOR),$(GENERATOR),coroutines)" \
	WORKLOAD="$(if $(WORKLOAD),$(WORKLOAD),auth)" \
	CPU="$(if $(CPU),$(CPU),2)" RAM="$(if $(RAM),$(RAM),1536)" \
	APP_HEAP_MB="$(APP_HEAP_MB)" \
	COROUTINES="$(if $(COROUTINES),$(COROUTINES),450)" \
	THREADS="$(if $(THREADS),$(THREADS),4)" \
	ERROR_RATE="$(if $(ERROR_RATE),$(ERROR_RATE),0)" \
	SOAK_VUS="$(if $(SOAK_VUS),$(SOAK_VUS),60)" \
	SOAK_DURATION="$(if $(SOAK_DURATION),$(SOAK_DURATION),20m)" \
	SMOKE_VUS="$(if $(SMOKE_VUS),$(SMOKE_VUS),2)" \
	SMOKE_DURATION="$(if $(SMOKE_DURATION),$(SMOKE_DURATION),35s)" \
	RAMP_MAX_VUS="$(if $(RAMP_MAX_VUS),$(RAMP_MAX_VUS),450)" \
	RAMP_STEP_DURATION="$(if $(RAMP_STEP_DURATION),$(RAMP_STEP_DURATION),1m)" \
	RAMP_PLATEAU_DURATION="$(if $(RAMP_PLATEAU_DURATION),$(RAMP_PLATEAU_DURATION),2m)" \
	REQUEST_TIMEOUT="$(if $(REQUEST_TIMEOUT),$(REQUEST_TIMEOUT),30s)" \
	EARLY_STOP="$(if $(EARLY_STOP),$(EARLY_STOP),1)" \
	EARLY_STOP_AFTER="$(if $(EARLY_STOP_AFTER),$(EARLY_STOP_AFTER),30s)" \
	FAILURE_WINDOW="$(if $(FAILURE_WINDOW),$(FAILURE_WINDOW),5s)" \
	FAILURE_THRESHOLD="$(if $(FAILURE_THRESHOLD),$(FAILURE_THRESHOLD),0.95)" \
	FAILURE_WINDOWS="$(if $(FAILURE_WINDOWS),$(FAILURE_WINDOWS),3)" \
	FAILURE_MIN_REQUESTS="$(if $(FAILURE_MIN_REQUESTS),$(FAILURE_MIN_REQUESTS),20)" \
	./scripts/run-loadtest.sh

loadtest-all:
	./scripts/run-all-loadtests.sh

# Демонстрация «сервис падает под рампом»:
#   auth (login + запись), 1 CPU, минимальная RAM 256 MiB (heap 64),
#   100 корутин, ramp 10→100 VU ступенями по 5 с и плато 20 с.
#   VU = корутина, поэтому выше 100 не поднимаемся; на 1 CPU auth
#   ломается уже на ~40–50 VU, после чего 3 окна по 5 с дают ≥95%
#   отказов и генератор останавливается сам.
#   RAM=256 — практический минимум: 64/128 MiB убиваются OOM до healthcheck.
loadtest-fail:
	SCENARIO=ramp WORKLOAD=auth CPU=1 RAM=256 APP_HEAP_MB=64 \
	COROUTINES=100 THREADS=1 \
	RAMP_MAX_VUS=100 RAMP_STEP_DURATION=5s RAMP_PLATEAU_DURATION=20s \
	REQUEST_TIMEOUT=2s WARMUP=5s THINK_MIN_MS=50 THINK_MAX_MS=100 \
	EARLY_STOP=1 EARLY_STOP_AFTER=30s FAILURE_WINDOW=5s FAILURE_WINDOWS=3 \
	FAILURE_THRESHOLD=0.95 FAILURE_MIN_REQUESTS=20 \
	./scripts/run-loadtest.sh

loadtest-matrix:
	CPUS="$(if $(CPUS),$(CPUS),1 2)" \
	RAMS="$(if $(RAMS),$(RAMS),512 1024)" \
	COROUTINES="$(if $(COROUTINES),$(COROUTINES),100)" \
	SCENARIO="$(if $(SCENARIO),$(SCENARIO),ramp)" \
	WORKLOAD="$(if $(WORKLOAD),$(WORKLOAD),auth)" \
	./scripts/run-loadtest-matrix.sh

loadtest-import:
	./scripts/import-loadtest-to-db.sh

loadtest-dashboard:
	python3 scripts/generate-loadtest-dashboard.py
	./scripts/setup-grafana-dashboard.sh

# ---- ЛР3: Grafana (отчёт и все графики перенесены в Grafana) ----
grafana-up:
	docker network inspect viday_loadtest_net >/dev/null 2>&1 || docker network create viday_loadtest_net
	docker compose -f docker/docker-compose.grafana.yml up -d
	./scripts/setup-grafana-dashboard.sh
	@echo "Grafana: http://localhost:3000 (admin/admin)."
	@echo "  ЛР3:        http://localhost:3000/d/benchmark-lr3"
	@echo "  ЛР4 auth:   http://localhost:3000/d/loadtest-lr4-auth"
	@echo "  ЛР4 public: http://localhost:3000/d/loadtest-lr4-public"

grafana-down:
	docker compose -f docker/docker-compose.grafana.yml down

grafana-logs:
	docker compose -f docker/docker-compose.grafana.yml logs -f

benchmark-import:
	./scripts/import-benchmark-to-db.sh
	./scripts/setup-grafana-dashboard.sh

benchmark-dashboard:
	python3 scripts/generate-grafana-dashboard.py
	./scripts/setup-grafana-dashboard.sh

benchmark-multidim:
	chmod +x docker/postgres/research/run_multidim.sh
	./docker/postgres/research/run_multidim.sh

benchmark-multidim-one:
	chmod +x docker/postgres/research/run_multidim.sh
	N=$(N) ./docker/postgres/research/run_multidim.sh

benchmark-multidim-copy:
	docker exec -i viday_postgres psql -U viday_admin_user -d viday -v ON_ERROR_STOP=1 \
		< docker/postgres/research/05_export_multidim_csv.sql
	mkdir -p research_output
	docker cp viday_postgres:/tmp/benchmark_q1_public_feed_chart.csv ./research_output/ 2>/dev/null || \
		docker cp viday_postgres:/tmp/benchmark_q1_public_feed_chart.csv ./research_output/benchmark_q1_public_feed_chart.csv
	docker cp viday_postgres:/tmp/benchmark_q2_name_search_chart.csv ./research_output/benchmark_q2_name_search_chart.csv
	docker cp viday_postgres:/tmp/benchmark_q3_playlist_join_chart.csv ./research_output/benchmark_q3_playlist_join_chart.csv
	cp research_output/benchmark_q1_public_feed_chart.csv ./benchmark_q1_public_feed.csv
	cp research_output/benchmark_q2_name_search_chart.csv ./benchmark_q2_name_search.csv
	cp research_output/benchmark_q3_playlist_join_chart.csv ./benchmark_q3_playlist_join.csv
