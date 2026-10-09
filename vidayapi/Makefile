.PHONY: build run run-console clean test test-offline test-fixtures-demo test-integration test-e2e coverage allure allure-serve sonar-up sonar-down sonar benchmark-db benchmark-db-copy stand-up stand-down stand-logs stand-ps app-up app-down reset-test-db e2e-curl traffic-demo e2e-traffic wireshark ci-runner

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
