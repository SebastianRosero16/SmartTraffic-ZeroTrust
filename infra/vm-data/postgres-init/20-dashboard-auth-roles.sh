#!/bin/sh
set -eu

if [ "$AUTH_DB_USER" = "$AUTH_DB_MIGRATOR_USER" ]; then
  echo "AUTH_DB_USER and AUTH_DB_MIGRATOR_USER must be distinct roles" >&2
  exit 1
fi

psql \
  --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=runtime_user="$AUTH_DB_USER" \
  --set=runtime_password="$AUTH_DB_PASSWORD" \
  --set=migrator_user="$AUTH_DB_MIGRATOR_USER" \
  --set=migrator_password="$AUTH_DB_MIGRATOR_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'migrator_user', :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator_user')
\gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'migrator_user', :'migrator_password')
\gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user')
\gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password')
\gexec

SELECT format('CREATE SCHEMA IF NOT EXISTS dashboard_auth AUTHORIZATION %I', :'migrator_user')
\gexec
SELECT format('ALTER SCHEMA dashboard_auth OWNER TO %I', :'migrator_user')
\gexec
SELECT format('GRANT USAGE ON SCHEMA dashboard_auth TO %I', :'runtime_user')
\gexec
SELECT format(
    'GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA dashboard_auth TO %I',
    :'runtime_user')
\gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA dashboard_auth '
    'GRANT SELECT, INSERT ON TABLES TO %I',
    :'migrator_user',
    :'runtime_user')
\gexec
SQL
