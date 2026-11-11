#!/usr/bin/env bash
#
# Unit tests for bin/set-policies.
#
# The script creates the five easy-db-lab managed policies and attaches them to one IAM user,
# group or role. It must report an attachment only when the policy is attached: a failed attach
# shows the AWS error and fails the run, and the closing success line is printed only after every
# policy is confirmed attached to the target.
#
# The script is copied into a scratch bin/ next to a stub easy-db-lab (it finds easy-db-lab
# beside itself), and a stub aws on PATH keeps IAM state in files. No AWS account, no network.
# The script runs under "$BASH", the bash running this test; run-with-bashes.sh runs it again
# under the macOS /bin/bash 3.2.
#
# Run directly:  sh src/test/shell/run-with-bashes.sh bin/set-policies.test.sh
# Or via gradle: ./gradlew testSetPolicies

set -uo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/set-policies"
TARGET_ACCOUNT="123456789012"
OTHER_ACCOUNT="999988887777"
ACCOUNT_PREFIX="arn:aws:iam::${TARGET_ACCOUNT}:policy"
ALL_POLICIES="EasyDBLabEC2 EasyDBLabIAM EasyDBLabEMR EasyDBLabOpenSearch EasyDBLabCompactor"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

# Sets up a scratch bin/ holding the script and a stub easy-db-lab, and a stub aws whose IAM state
# lives in ${WORK}/iam:
#   policies/<name>  exists when the managed policy exists; holds its ARN
#   attached         one attached policy ARN per line
#   attach-error     when present, attach-*-policy prints it to stderr and exits 254
#   attach-noop      when present, attach-*-policy succeeds without attaching
#   list-error       when present, list-attached-*-policies prints it to stderr and exits 254
#   account          the account sts get-caller-identity reports (the target account)
#   sts-error        when present, sts get-caller-identity prints it to stderr and exits 254
# and whose easy-db-lab prints ${WORK}/sip/<policy> for `sip <policy>` when that file exists, and
# otherwise a policy whose ARNs name ${TARGET_ACCOUNT}.
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/bin" "${WORK}/stubs" "${WORK}/iam/policies" "${WORK}/sip"
  : > "${WORK}/iam/attached"
  : > "${WORK}/aws.log"
  echo "${TARGET_ACCOUNT}" > "${WORK}/iam/account"
  cp "${SCRIPT}" "${WORK}/bin/set-policies"

  cat > "${WORK}/bin/easy-db-lab" <<EOF
#!/usr/bin/env bash
[ "\$1" = "sip" ] || { echo "unexpected easy-db-lab args: \$*" >&2; exit 2; }
if [ -f "${WORK}/sip/\$2" ]; then cat "${WORK}/sip/\$2"; exit 0; fi
cat <<'JSON'
$(policy_naming "${TARGET_ACCOUNT}")
JSON
EOF

  cat > "${WORK}/stubs/aws" <<EOF
#!/usr/bin/env bash
IAM="${WORK}/iam"
echo "\$*" >> "${WORK}/aws.log"
EOF
  cat >> "${WORK}/stubs/aws" <<'EOF'
arg() {
  want="$1"; shift
  while [ $# -gt 0 ]; do
    if [ "$1" = "$want" ]; then echo "$2"; return; fi
    shift
  done
}
if [ "$1 $2" = "sts get-caller-identity" ]; then
  if [ -f "$IAM/sts-error" ]; then cat "$IAM/sts-error" >&2; exit 254; fi
  cat "$IAM/account"
  exit 0
fi
[ "$1" = "iam" ] || { echo "unexpected aws service: $1" >&2; exit 2; }
sub="$2"; shift 2
case "$sub" in
  list-policies)
    name="$(arg --query "$@" | sed -n "s/.*PolicyName=='\([^']*\)'.*/\1/p")"
    if [ -f "$IAM/policies/$name" ]; then cat "$IAM/policies/$name"; fi
    ;;
  create-policy)
    name="$(arg --policy-name "$@")"
    echo "arn:aws:iam::123456789012:policy/$name" > "$IAM/policies/$name"
    cat "$IAM/policies/$name"
    ;;
  list-policy-versions) echo 1 ;;
  create-policy-version) echo v2 ;;
  list-attached-user-policies|list-attached-group-policies|list-attached-role-policies)
    if [ -f "$IAM/list-error" ]; then cat "$IAM/list-error" >&2; exit 254; fi
    paste -s - < "$IAM/attached"
    ;;
  attach-user-policy|attach-group-policy|attach-role-policy)
    if [ -f "$IAM/attach-error" ]; then cat "$IAM/attach-error" >&2; exit 254; fi
    if [ ! -f "$IAM/attach-noop" ]; then arg --policy-arn "$@" >> "$IAM/attached"; fi
    ;;
  *) echo "unexpected aws iam subcommand: $sub" >&2; exit 2 ;;
esac
EOF
  chmod +x "${WORK}/bin/set-policies" "${WORK}/bin/easy-db-lab" "${WORK}/stubs/aws"
}

# Prints a policy shaped like the packaged templates, its account-scoped ARNs naming account $1,
# beside ARNs that name no account (service-linked roles, SSM documents, S3).
policy_naming() {
  cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": "iam:PassRole",
     "Resource": ["arn:aws:iam::$1:role/EasyDBLabEC2Role",
                  "arn:aws:iam::*:role/aws-service-role/ecs.amazonaws.com/AWSServiceRoleForECS"]},
    {"Effect": "Allow", "Action": "ssm:StartSession",
     "Resource": ["arn:aws:ssm:*:$1:session/*", "arn:aws:ssm:*::document/AWS-StartSSHSession"]},
    {"Effect": "Allow", "Action": "s3:*", "Resource": "arn:aws:s3:::easy-db-lab-*"}
  ]
}
JSON
}

existing_policy() {
  echo "${ACCOUNT_PREFIX}/$1" > "${WORK}/iam/policies/$1"
}

attached_policy() {
  echo "${ACCOUNT_PREFIX}/$1" >> "${WORK}/iam/attached"
}

# Runs the script with none of its own variables inherited; output goes to ${WORK}/out.txt.
run_script() {
  (cd "${WORK}" && env -u ECL_USER_NAME -u ECL_GROUP_NAME -u ECL_ROLE_NAME -u ECL_PROFILE \
    -u AWS_PROFILE -u ECL_DRY_RUN PATH="${WORK}/stubs:${PATH}" \
    "${BASH}" "${WORK}/bin/set-policies" "$@") > "${WORK}/out.txt" 2>&1
}

out() {
  cat "${WORK}/out.txt"
}

test_one_target_creates_and_attaches_every_policy() {
  tests_run=$((tests_run + 1))
  setup
  run_script --user-name alice || { fail "one target: script exited non-zero: $(out)"; return; }

  for name in ${ALL_POLICIES}; do
    grep -Fqx "${ACCOUNT_PREFIX}/${name}" "${WORK}/iam/attached" \
      || fail "one target: ${name} was not attached"
  done
  grep -Fq "All managed policies created and attached successfully" "${WORK}/out.txt" \
    || fail "one target: expected the success line, got: $(out)"
}

test_attach_failure_shows_the_aws_error_and_exits_non_zero() {
  tests_run=$((tests_run + 1))
  setup
  echo "An error occurred (NoSuchEntity) when calling the AttachUserPolicy operation: The user with name nobody cannot be found." \
    > "${WORK}/iam/attach-error"
  if run_script --user-name nobody; then
    fail "attach failure: script exited zero: $(out)"
    return
  fi

  grep -Fq "The user with name nobody cannot be found." "${WORK}/out.txt" \
    || fail "attach failure: expected the AWS error in the output, got: $(out)"
  if grep -Eq "already attached|Successfully attached|attached successfully" "${WORK}/out.txt"; then
    fail "attach failure: must not report an attachment, got: $(out)"
  fi
}

test_an_unreadable_target_shows_the_aws_error_and_exits_non_zero() {
  tests_run=$((tests_run + 1))
  setup
  echo "An error occurred (NoSuchEntity) when calling the ListAttachedUserPolicies operation: The user with name nobody cannot be found." \
    > "${WORK}/iam/list-error"
  if run_script --user-name nobody; then
    fail "unreadable target: script exited zero: $(out)"
    return
  fi

  grep -Fq "The user with name nobody cannot be found." "${WORK}/out.txt" \
    || fail "unreadable target: expected the AWS error in the output, got: $(out)"
  if grep -q "^iam attach-" "${WORK}/aws.log"; then
    fail "unreadable target: must stop before attaching, got: $(cat "${WORK}/aws.log")"
  fi
  if grep -Eq "already attached|Successfully attached|attached successfully" "${WORK}/out.txt"; then
    fail "unreadable target: must not report an attachment, got: $(out)"
  fi
}

# Fails when anything was created, updated or attached.
assert_no_iam_changes() {
  if grep -Eq '^iam (create-policy|create-policy-version|delete-policy-version|attach-)' "${WORK}/aws.log"; then
    fail "$1: must change nothing in IAM, got: $(cat "${WORK}/aws.log")"
  fi
}

test_a_policy_that_is_not_json_fails_before_any_change() {
  tests_run=$((tests_run + 1))
  setup
  printf 'Welcome to easy-db-lab profile setup.\nAWS access key: ' > "${WORK}/sip/iam"
  if run_script --user-name alice; then
    fail "not JSON: script exited zero: $(out)"
    return
  fi

  grep -F "EasyDBLabIAM" "${WORK}/out.txt" | grep -Fq "not a JSON policy document" \
    || fail "not JSON: expected an error naming EasyDBLabIAM, got: $(out)"
  grep -Fq "profile setup" <(grep -F "ERROR" -A3 "${WORK}/out.txt") \
    || fail "not JSON: expected a pointer to profile setup, got: $(out)"
  assert_no_iam_changes "not JSON"
}

test_several_json_documents_are_not_one_policy() {
  tests_run=$((tests_run + 1))
  setup
  { policy_naming "${TARGET_ACCOUNT}"; policy_naming "${TARGET_ACCOUNT}"; } > "${WORK}/sip/emr"
  if run_script --user-name alice; then
    fail "two documents: script exited zero: $(out)"
    return
  fi
  grep -F "EasyDBLabEMR" "${WORK}/out.txt" | grep -Fq "not a JSON policy document" \
    || fail "two documents: expected an error naming EasyDBLabEMR, got: $(out)"
  assert_no_iam_changes "two documents"
}

test_a_policy_for_another_account_fails_before_any_change() {
  tests_run=$((tests_run + 1))
  setup
  policy_naming "${OTHER_ACCOUNT}" > "${WORK}/sip/compactor"
  if run_script --user-name alice --profile lab; then
    fail "other account: script exited zero: $(out)"
    return
  fi

  local message
  message="$(grep -F "ERROR" -A3 "${WORK}/out.txt")"
  grep -Fq "${OTHER_ACCOUNT}" <<< "${message}" \
    || fail "other account: expected the policy's account in the error, got: $(out)"
  grep -Fq "${TARGET_ACCOUNT}" <<< "${message}" \
    || fail "other account: expected the target account in the error, got: $(out)"
  grep -Fq "different account" <<< "${message}" \
    || fail "other account: expected the error to say the accounts differ, got: $(out)"
  assert_no_iam_changes "other account"
}

test_a_target_account_that_cannot_be_read_fails_before_any_change() {
  tests_run=$((tests_run + 1))
  setup
  echo "Unable to locate credentials. You can configure credentials by running \"aws configure\"." \
    > "${WORK}/iam/sts-error"
  if run_script --user-name alice; then
    fail "no caller identity: script exited zero: $(out)"
    return
  fi
  grep -Fq "Unable to locate credentials." "${WORK}/out.txt" \
    || fail "no caller identity: expected the AWS error in the output, got: $(out)"
  assert_no_iam_changes "no caller identity"
}

test_the_account_check_reads_the_profile_given() {
  tests_run=$((tests_run + 1))
  setup
  run_script --user-name alice --profile lab || { fail "profile: script exited non-zero: $(out)"; return; }
  grep -q '^sts get-caller-identity.*--profile lab' "${WORK}/aws.log" \
    || fail "profile: expected the caller identity of --profile lab, got: $(cat "${WORK}/aws.log")"
}

test_an_attached_policy_is_reported_and_not_attached_again() {
  tests_run=$((tests_run + 1))
  setup
  for name in ${ALL_POLICIES}; do
    existing_policy "${name}"
  done
  attached_policy EasyDBLabEC2
  run_script --user-name alice || { fail "already attached: script exited non-zero: $(out)"; return; }

  grep -Fq "EasyDBLabEC2 is already attached to user alice" "${WORK}/out.txt" \
    || fail "already attached: expected an already-attached line for EasyDBLabEC2, got: $(out)"
  if grep '^iam attach-' "${WORK}/aws.log" | grep -Fq -- "--policy-arn ${ACCOUNT_PREFIX}/EasyDBLabEC2"; then
    fail "already attached: EasyDBLabEC2 was attached again"
  fi
  grep -Fq -- "attach-user-policy --user-name alice --policy-arn ${ACCOUNT_PREFIX}/EasyDBLabIAM" "${WORK}/aws.log" \
    || fail "already attached: expected EasyDBLabIAM to be attached, got: $(cat "${WORK}/aws.log")"
}

test_a_policy_missing_after_the_attach_step_fails_the_run() {
  tests_run=$((tests_run + 1))
  setup
  touch "${WORK}/iam/attach-noop"
  if run_script --user-name alice; then
    fail "missing after attach: script exited zero: $(out)"
    return
  fi

  grep -i "not attached" "${WORK}/out.txt" | grep -Fq "EasyDBLabCompactor" \
    || fail "missing after attach: expected the missing policies to be named, got: $(out)"
  if grep -Fq "attached successfully" "${WORK}/out.txt"; then
    fail "missing after attach: must not print the success line, got: $(out)"
  fi
}

test_group_and_role_targets_use_their_own_iam_calls() {
  tests_run=$((tests_run + 1))
  local kind
  for kind in group role; do
    setup
    run_script "--${kind}-name" target || { fail "${kind}: script exited non-zero: $(out)"; continue; }
    grep -q "^iam list-attached-${kind}-policies --${kind}-name target" "${WORK}/aws.log" \
      || fail "${kind}: expected list-attached-${kind}-policies, got: $(cat "${WORK}/aws.log")"
    grep -q "^iam attach-${kind}-policy --${kind}-name target" "${WORK}/aws.log" \
      || fail "${kind}: expected attach-${kind}-policy, got: $(cat "${WORK}/aws.log")"
  done
}

test_policy_lookup_is_limited_to_customer_managed_policies() {
  tests_run=$((tests_run + 1))
  setup
  run_script --user-name alice || { fail "scope: script exited non-zero: $(out)"; return; }

  grep -q '^iam list-policies' "${WORK}/aws.log" || { fail "scope: list-policies was never called"; return; }
  local unscoped
  unscoped="$(grep '^iam list-policies' "${WORK}/aws.log" | grep -v -- '--scope Local')"
  [ -z "${unscoped}" ] || fail "scope: list-policies without --scope Local: ${unscoped}"
}

test_zero_or_several_targets_are_rejected() {
  tests_run=$((tests_run + 1))
  setup
  if run_script; then fail "no target: script exited zero"; fi
  grep -Fq "Must specify exactly one" "${WORK}/out.txt" || fail "no target: expected the error, got: $(out)"

  if run_script --user-name alice --role-name r; then fail "two targets: script exited zero"; fi
  grep -Fq "Cannot specify multiple targets" "${WORK}/out.txt" \
    || fail "two targets: expected the error, got: $(out)"
  [ ! -s "${WORK}/aws.log" ] || fail "rejected targets must not call aws, got: $(cat "${WORK}/aws.log")"
}

test_dry_run_changes_nothing_and_lists_the_attachment_check() {
  tests_run=$((tests_run + 1))
  setup
  run_script --group-name devs --dry-run || { fail "dry run: script exited non-zero: $(out)"; return; }

  [ ! -s "${WORK}/aws.log" ] || fail "dry run: must not call aws, got: $(cat "${WORK}/aws.log")"
  grep -Fq "aws iam list-attached-group-policies --group-name devs" "${WORK}/out.txt" \
    || fail "dry run: expected the attachment check step, got: $(out)"
  grep -Fq "list-policies --scope Local" "${WORK}/out.txt" \
    || fail "dry run: expected the scoped policy lookup, got: $(out)"
}

echo "Testing set-policies under bash ${BASH_VERSION}"
test_one_target_creates_and_attaches_every_policy
test_attach_failure_shows_the_aws_error_and_exits_non_zero
test_an_unreadable_target_shows_the_aws_error_and_exits_non_zero
test_a_policy_that_is_not_json_fails_before_any_change
test_several_json_documents_are_not_one_policy
test_a_policy_for_another_account_fails_before_any_change
test_a_target_account_that_cannot_be_read_fails_before_any_change
test_the_account_check_reads_the_profile_given
test_an_attached_policy_is_reported_and_not_attached_again
test_a_policy_missing_after_the_attach_step_fails_the_run
test_group_and_role_targets_use_their_own_iam_calls
test_policy_lookup_is_limited_to_customer_managed_policies
test_zero_or_several_targets_are_rejected
test_dry_run_changes_nothing_and_lists_the_attachment_check

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
