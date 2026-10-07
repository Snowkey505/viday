build:
	docker compose up -d && sleep 5 && docker compose logs postgres

rebuild: clear build

clear: 
	docker compose down -v && docker volume rm docker_postgres_data 2>/dev/null || true
