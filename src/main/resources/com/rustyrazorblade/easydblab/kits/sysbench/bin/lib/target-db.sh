#!/usr/bin/env bash
#
# The sysbench database driver args, built once from the TARGET_* variables the framework injects
# for the target kit. prepare, start and stop source this file from their own directory, after the
# shared kit shell library.
#
# The args are a shell array, so each one reaches `kubectl run ... -- sysbench` as a single
# argument. A target password never goes into a pod spec or onto a kubectl command line, where
# `kubectl get pod -o yaml` or `kubectl describe pod` would show it. target_apply_credentials puts it
# in the Secret <kit>-credentials, and each pod reads it from there through env
# valueFrom.secretKeyRef (target_pod_overrides). A sysbench pod gets it as TARGET_PASSWORD and runs
# sysbench through `sh -c`, which expands it into --mysql-password or --pgsql-password at run time
# as one argument, so a password with spaces, `*` or quotes is neither split nor globbed. A target
# with no password gets no Secret and no password flag, exactly as before.

# The role and key of the Secret that holds the target password for this instance.
SYSBENCH_CREDENTIALS_ROLE=sysbench-credentials
SYSBENCH_CREDENTIALS_KEY=password
# The env var a sysbench pod reads the password into.
SYSBENCH_PASSWORD_ENV=TARGET_PASSWORD

# Prints the name of this instance's credentials Secret.
sysbench_credentials_secret() {
  printf '%s-credentials\n' "${KIT_NAME}"
}

# Sets DB_ARGS to the driver args for the target's MySQL or PostgreSQL wire endpoint, without the
# password, DB_PASSWORD to the target password (empty when it has none) and DB_PASSWORD_FLAG to the
# sysbench flag that carries it. Returns 1, with DB_ARGS empty, when the target exposes neither.
target_db_args() {
  DB_ARGS=()
  DB_PASSWORD=""
  DB_PASSWORD_FLAG=""
  if [ -n "${TARGET_MYSQL_HOST:-}" ]; then
    DB_ARGS=(
      --db-driver=mysql
      "--mysql-host=${TARGET_MYSQL_HOST}"
      "--mysql-port=${TARGET_MYSQL_PORT}"
      "--mysql-user=${TARGET_MYSQL_USER}"
      "--mysql-db=${TARGET_MYSQL_DATABASE}"
    )
    DB_PASSWORD="${TARGET_MYSQL_PASSWORD:-}"
    DB_PASSWORD_FLAG=--mysql-password
    # The MySQL error codes the target kit's mysql endpoint declares as ignore-errors.
    if [ -n "${TARGET_MYSQL_IGNORE_ERRORS:-}" ]; then
      DB_ARGS+=("--mysql-ignore-errors=${TARGET_MYSQL_IGNORE_ERRORS}")
    fi
  elif [ -n "${TARGET_PG_HOST:-}" ]; then
    DB_ARGS=(
      --db-driver=pgsql
      "--pgsql-host=${TARGET_PG_HOST}"
      "--pgsql-port=${TARGET_PG_PORT}"
      "--pgsql-user=${TARGET_PG_USER}"
      "--pgsql-db=${TARGET_PG_DATABASE}"
    )
    DB_PASSWORD="${TARGET_PG_PASSWORD:-}"
    DB_PASSWORD_FLAG=--pgsql-password
  else
    return 1
  fi
}

# Creates or updates this instance's credentials Secret from DB_PASSWORD, when the target has a
# password. The value reaches the library on stdin; printf is a builtin, so no process holds it in
# its arguments.
target_apply_credentials() {
  local secret
  [ -n "${DB_PASSWORD}" ] || return 0
  secret="$(sysbench_credentials_secret)"
  printf '%s' "${DB_PASSWORD}" | edl_apply_secret "${secret}" "${SYSBENCH_CREDENTIALS_ROLE}" "${SYSBENCH_CREDENTIALS_KEY}"
}

# Prints the --overrides JSON for pod $1: an app node and, when the target has a password, env var
# $2 read from the credentials Secret.
target_pod_overrides() {
  local secret
  if [ -z "${DB_PASSWORD}" ]; then
    edl_pod_overrides
    return
  fi
  secret="$(sysbench_credentials_secret)"
  edl_pod_overrides_secret_env "$1" "$2" "${secret}" "${SYSBENCH_CREDENTIALS_KEY}"
}

# Sets SYSBENCH_CMD to the container command that runs sysbench with the driver args and then the
# arguments given. With a password, `sh -c` adds the password flag from TARGET_PASSWORD when the
# container starts; the command itself holds only the variable's name.
sysbench_command() {
  if [ -z "${DB_PASSWORD}" ]; then
    SYSBENCH_CMD=(sysbench "${DB_ARGS[@]}" "$@")
    return
  fi
  SYSBENCH_CMD=(
    sh -c "exec sysbench ${DB_PASSWORD_FLAG}=\"\$${SYSBENCH_PASSWORD_ENV}\" \"\$@\""
    sysbench "${DB_ARGS[@]}" "$@"
  )
}

# Prints the FATAL lines in sysbench output file $1, if any. sysbench prints FATAL for an SQL
# error it does not ignore; a phase that printed one failed, whatever its exit code.
sysbench_fatal_lines() {
  grep '^FATAL:' "$1" || true
}
