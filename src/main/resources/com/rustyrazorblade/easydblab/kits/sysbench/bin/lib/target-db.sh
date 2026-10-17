#!/usr/bin/env bash
#
# The sysbench database driver args, built once from the TARGET_* variables the framework injects
# for the target kit. prepare, start and stop source this file from their own directory.
#
# The args are a shell array, so each one reaches `kubectl run ... -- sysbench` as a single
# argument: a password with spaces, `*` or quotes is neither split nor globbed. A target with no
# password gets no password flag, exactly as before.

# Sets DB_ARGS to the driver args for the target's MySQL or PostgreSQL wire endpoint. Returns 1,
# with DB_ARGS empty, when the target exposes neither.
target_db_args() {
  DB_ARGS=()
  if [ -n "${TARGET_MYSQL_HOST:-}" ]; then
    DB_ARGS=(
      --db-driver=mysql
      "--mysql-host=${TARGET_MYSQL_HOST}"
      "--mysql-port=${TARGET_MYSQL_PORT}"
      "--mysql-user=${TARGET_MYSQL_USER}"
      "--mysql-db=${TARGET_MYSQL_DATABASE}"
    )
    if [ -n "${TARGET_MYSQL_PASSWORD:-}" ]; then
      DB_ARGS+=("--mysql-password=${TARGET_MYSQL_PASSWORD}")
    fi
  elif [ -n "${TARGET_PG_HOST:-}" ]; then
    DB_ARGS=(
      --db-driver=pgsql
      "--pgsql-host=${TARGET_PG_HOST}"
      "--pgsql-port=${TARGET_PG_PORT}"
      "--pgsql-user=${TARGET_PG_USER}"
      "--pgsql-db=${TARGET_PG_DATABASE}"
    )
    if [ -n "${TARGET_PG_PASSWORD:-}" ]; then
      DB_ARGS+=("--pgsql-password=${TARGET_PG_PASSWORD}")
    fi
  else
    return 1
  fi
}

# Sets MYSQL_PWD_ENV to the `kubectl run` flag that hands a mysql client pod the target password
# through MYSQL_PWD, or to nothing when the target has none. The password stays off the client's
# command line.
target_mysql_pwd_env() {
  MYSQL_PWD_ENV=()
  if [ -n "${TARGET_MYSQL_PASSWORD:-}" ]; then
    MYSQL_PWD_ENV=("--env=MYSQL_PWD=${TARGET_MYSQL_PASSWORD}")
  fi
}
