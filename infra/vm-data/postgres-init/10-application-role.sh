#!/bin/sh
set -eu

psql \
  --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=app_user="$APP_DB_USER" \
  --set=app_password="$APP_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
\gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', current_database(), :'app_user')
\gexec
SELECT format('ALTER SCHEMA public OWNER TO %I', :'app_user')
\gexec
SQL
