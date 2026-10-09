FROM postgres:15-alpine

RUN apk add --no-cache postgresql-contrib

COPY init/01_create_tables.sql /docker-entrypoint-initdb.d/01_create_tables.sql
COPY init/02_alter_constraints.sql /docker-entrypoint-initdb.d/02_alter_constraints.sql
COPY init/03_roles.sql /docker-entrypoint-initdb.d/03_roles.sql
COPY init/04_triggers.sql /docker-entrypoint-initdb.d/04_triggers.sql
COPY init/05_indexes.sql /docker-entrypoint-initdb.d/05_indexes.sql

EXPOSE 5432
