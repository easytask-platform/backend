#!/usr/bin/env bash
# End-to-end demo scenario from EasyTask_Project_Explanation.md §12 (Sara/Ahmad flow).
# Requires: the backend running on $BASE_URL (default http://localhost:8080), curl, jq.
# Safe to re-run: every run uses a unique email suffix.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}/api/v1"
SUFFIX="$(date +%s)"
PASS="password123"

step() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

# request <expected_status> <method> <path> <token|-> [json_body|-] [extra curl args...]
request() {
    local expected="$1" method="$2" path="$3" token="$4" body="${5:--}"
    if (( $# >= 5 )); then shift 5; else shift $#; fi
    local args=(-s -w '\n%{http_code}' -X "$method" "$BASE$path")
    [[ "$token" != "-" ]] && args+=(-H "Authorization: Bearer $token")
    [[ "$body" != "-" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
    args+=("$@")
    local response status
    response="$(curl "${args[@]}")"
    status="${response##*$'\n'}"
    RESPONSE_BODY="${response%$'\n'*}"
    [[ "$status" == "$expected" ]] \
        || fail "$method $path -> $status (expected $expected): $RESPONSE_BODY"
}

command -v jq >/dev/null || fail "jq is required"

step "1. Admin registers 'EasyTask Demo Company'"
request 201 POST /auth/register-organization - "{
  \"organizationName\": \"EasyTask Demo Company $SUFFIX\",
  \"adminFullName\": \"Demo Admin\",
  \"email\": \"admin+$SUFFIX@demo.easytask\",
  \"password\": \"$PASS\"}"
ADMIN_TOKEN=$(jq -r .accessToken <<<"$RESPONSE_BODY")

step "2. Admin creates Sara (manager), Ahmad and Lina (employees)"
request 201 POST /users "$ADMIN_TOKEN" "{\"fullName\": \"Sara Manager\", \"email\": \"sara+$SUFFIX@demo.easytask\", \"initialPassword\": \"$PASS\", \"role\": \"MANAGER\"}"
SARA_ID=$(jq -r .id <<<"$RESPONSE_BODY")
request 201 POST /users "$ADMIN_TOKEN" "{\"fullName\": \"Ahmad Employee\", \"email\": \"ahmad+$SUFFIX@demo.easytask\", \"initialPassword\": \"$PASS\", \"role\": \"EMPLOYEE\"}"
AHMAD_ID=$(jq -r .id <<<"$RESPONSE_BODY")
request 201 POST /users "$ADMIN_TOKEN" "{\"fullName\": \"Lina Employee\", \"email\": \"lina+$SUFFIX@demo.easytask\", \"initialPassword\": \"$PASS\", \"role\": \"EMPLOYEE\"}"
LINA_ID=$(jq -r .id <<<"$RESPONSE_BODY")

step "3-4. Admin creates 'Backend Team' and adds Sara, Ahmad, Lina"
request 201 POST /teams "$ADMIN_TOKEN" '{"name": "Backend Team", "description": "Server-side work"}'
TEAM_ID=$(jq -r .id <<<"$RESPONSE_BODY")
for USER_ID in "$SARA_ID" "$AHMAD_ID" "$LINA_ID"; do
    request 201 POST "/teams/$TEAM_ID/members" "$ADMIN_TOKEN" "{\"userId\": \"$USER_ID\"}"
done

step "5. Sara logs in"
request 200 POST /auth/login - "{\"email\": \"sara+$SUFFIX@demo.easytask\", \"password\": \"$PASS\"}"
SARA_TOKEN=$(jq -r .accessToken <<<"$RESPONSE_BODY")

step "6. Sara creates project 'Task Management Backend'"
request 201 POST /projects "$SARA_TOKEN" '{"name": "Task Management Backend", "description": "College project", "status": "ACTIVE"}'
PROJECT_ID=$(jq -r .id <<<"$RESPONSE_BODY")
request 201 POST "/projects/$PROJECT_ID/members" "$SARA_TOKEN" "{\"userId\": \"$AHMAD_ID\"}"

step "7-8. Sara creates 'Build authentication API' assigned to Ahmad"
request 201 POST /tasks "$SARA_TOKEN" "{
  \"projectId\": \"$PROJECT_ID\", \"title\": \"Build authentication API\",
  \"description\": \"JWT login + refresh\", \"priority\": \"HIGH\",
  \"estimatedHours\": 6, \"assigneeIds\": [\"$AHMAD_ID\"]}"
TASK_ID=$(jq -r .id <<<"$RESPONSE_BODY")

step "9. Ahmad receives an in-app notification"
request 200 POST /auth/login - "{\"email\": \"ahmad+$SUFFIX@demo.easytask\", \"password\": \"$PASS\"}"
AHMAD_TOKEN=$(jq -r .accessToken <<<"$RESPONSE_BODY")
request 200 GET /notifications/unread-count "$AHMAD_TOKEN"
[[ "$(jq -r .count <<<"$RESPONSE_BODY")" -ge 1 ]] || fail "Ahmad has no unread notifications"

step "10. Ahmad sees the task"
request 200 GET "/tasks?assigneeId=$AHMAD_ID" "$AHMAD_TOKEN"
[[ "$(jq -r .totalItems <<<"$RESPONSE_BODY")" == 1 ]] || fail "Ahmad does not see his task"

step "11. Ahmad moves the task to IN_PROGRESS"
request 200 PATCH "/tasks/$TASK_ID/status" "$AHMAD_TOKEN" '{"status": "IN_PROGRESS"}'

step "12. Ahmad logs 2 hours"
request 201 POST "/tasks/$TASK_ID/time-entries" "$AHMAD_TOKEN" "{\"workDate\": \"$(date +%F)\", \"hoursSpent\": 2, \"note\": \"Built login endpoint\"}"

step "13. Ahmad comments"
request 201 POST "/tasks/$TASK_ID/comments" "$AHMAD_TOKEN" '{"text": "Login endpoint is implemented."}'

step "14. Ahmad uploads a test result file"
TMP_FILE="$(mktemp /tmp/easytask-e2e-XXXX.txt)"
echo "All auth tests passing" > "$TMP_FILE"
request 201 POST "/tasks/$TASK_ID/attachments" "$AHMAD_TOKEN" - -F "file=@$TMP_FILE;type=text/plain"
rm -f "$TMP_FILE"

step "15. Ahmad submits the task as IN_REVIEW"
request 200 PATCH "/tasks/$TASK_ID/status" "$AHMAD_TOKEN" '{"status": "IN_REVIEW"}'

step "16-17. Sara reviews and approves"
request 200 PATCH "/tasks/$TASK_ID/status" "$SARA_TOKEN" '{"status": "APPROVED", "note": "Nice work"}'

step "18. Ahmad receives the approval notification"
request 200 GET '/notifications?read=false' "$AHMAD_TOKEN"
grep -q 'approved' <<<"$RESPONSE_BODY" || fail "no approval notification for Ahmad"

step "19. Dashboard shows updated project progress"
request 200 GET "/projects/$PROJECT_ID" "$SARA_TOKEN"
[[ "$(jq -r .progressPercent <<<"$RESPONSE_BODY")" == 100 ]] || fail "project progress is not 100%"
request 200 GET /dashboard/manager "$SARA_TOKEN"
[[ "$(jq -r .tasksByStatus.approved <<<"$RESPONSE_BODY")" -ge 1 ]] || fail "manager dashboard missing approved task"

step "20. Activity log kept the history"
request 200 GET "/tasks/$TASK_ID/activity" "$SARA_TOKEN"
for EVENT in TASK_CREATED ASSIGNEE_ADDED STATUS_CHANGED TIME_LOGGED COMMENT_POSTED ATTACHMENT_UPLOADED TASK_APPROVED; do
    grep -q "$EVENT" <<<"$RESPONSE_BODY" || fail "activity log missing $EVENT"
done

printf '\n\033[1;32mE2E scenario completed successfully.\033[0m\n'
