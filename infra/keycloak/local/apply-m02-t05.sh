#!/usr/bin/env bash
# Applies the realm changes of M02-T05 (ADR 0016) to a LOCAL Keycloak whose realm `dentasoft` was imported before:
# Keycloak never imports a realm that already exists, and re-creating it would give the test users new ids (their
# memberships in the database would stop matching) and lose their one-time codes. Nothing is deleted.
#
# What it does, inside the running Keycloak container, with its bootstrap administrator:
#   1. turns on the fine-grained admin permissions (FGAP v2) of the realm;
#   2. creates the group clinic-users (with the id of realm-dentasoft.json) and the confidential client
#      dentasoft-backend, whose secret is the container's DENTASOFT_KC_BACKEND_CLIENT_SECRET;
#   3. gives its service account query-users, query-groups and manage-organizations, also as scope mappings;
#   4. creates the user policy and the Groups permission of realm-dentasoft.json;
#   5. puts the clinic test users of dentasoft-users-0.json in clinic-users (never the platform administrator);
#   6. checks with the service account's own token that it cannot touch the platform administrator.
#
# Steps already done are skipped, so it can run again. Requires python (host) and the Keycloak container running with
# DENTASOFT_KC_BACKEND_CLIENT_SECRET (add it to .env, then `docker compose -f compose.yml -f compose.local.yml up -d
# keycloak` from infra/). Usage, from the repository root: bash infra/keycloak/local/apply-m02-t05.sh
# Local only: other environments get the realm from keycloak-config-cli (M03-T02).
set -euo pipefail
# Git Bash on Windows would turn the container paths (/tmp/…) of docker commands into Windows paths.
dk() { MSYS_NO_PATHCONV=1 docker "$@"; }

CONTAINER="${KEYCLOAK_CONTAINER:-dentasoft-keycloak-1}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REALM_FILE="$HERE/../realm-dentasoft.json"
USERS_FILE="$HERE/dentasoft-users-0.json"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The pieces of the realm file this script needs, as small JSON files for kcadm, with LF line ends for the container.
python -I - "$REALM_FILE" "$USERS_FILE" "$WORK" <<'PY'
import json, sys, pathlib
realm = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
users = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
out = pathlib.Path(sys.argv[3])
group = next(g for g in realm["groups"] if g["name"] == "clinic-users")
client = dict(next(c for c in realm["clients"] if c["clientId"] == "dentasoft-backend"))
client.pop("secret", None)  # set from the container's environment, never written to a file
roles = realm["clientScopeMappings"]["realm-management"]
roles = next(m["roles"] for m in roles if m["client"] == "dentasoft-backend")
permissions = next(c for c in realm["clients"] if c["clientId"] == "admin-permissions")["authorizationSettings"]
permission = next(p for p in permissions["policies"] if p["type"] == "scope")
def write(name, text):
    (out / name).write_bytes(text.encode("utf-8"))  # bytes: no CRLF on Windows


def lines(values):
    return "".join(value + "\n" for value in values)


write("partial.json", json.dumps({"ifResourceExists": "SKIP", "groups": [group], "clients": [client]}))
write("roles.json", json.dumps([{"name": r} for r in roles]))
write("role-names.txt", lines(roles))
write("group-id.txt", group["id"])
write("scopes.json", permission["config"]["scopes"])
write("clinic-users.txt", lines(u["username"] for u in users["users"] if "/clinic-users" in u.get("groups", [])))
write("platform-users.txt", lines(u["username"] for u in users["users"] if "PLATFORM_ADMIN" in u.get("realmRoles", [])))
PY

dk exec "$CONTAINER" sh -c 'test -n "$DENTASOFT_KC_BACKEND_CLIENT_SECRET"' \
    || { echo "The container has no DENTASOFT_KC_BACKEND_CLIENT_SECRET: add it to .env and recreate keycloak." >&2; exit 1; }
dk exec "$CONTAINER" mkdir -p /tmp/m02t05
for file in partial.json roles.json role-names.txt group-id.txt scopes.json clinic-users.txt platform-users.txt; do
    dk exec -i "$CONTAINER" sh -c "cat > /tmp/m02t05/$file" < "$WORK/$file"
done

dk exec -i "$CONTAINER" bash -s <<'SH'
set -euo pipefail
KC=/opt/keycloak/bin/kcadm.sh
CFG=/tmp/m02t05/admin.config
D=/tmp/m02t05
R=dentasoft
trap 'rm -rf "$D"' EXIT
$KC config credentials --config "$CFG" --server http://localhost:8080 --realm master \
    --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
k() { $KC "$@" --config "$CFG"; }
id_of() { k get "$1" -r "$R" -q "$2" --fields id --format csv --noquotes | head -n 1; }

echo "1. Fine-grained admin permissions"
k update "realms/$R" -s adminPermissionsEnabled=true

echo "2. Group clinic-users and client dentasoft-backend"
k create "partialImport" -r "$R" -f "$D/partial.json" >/dev/null
CID=$(id_of clients clientId=dentasoft-backend)
k update "clients/$CID" -r "$R" -s "secret=$DENTASOFT_KC_BACKEND_CLIENT_SECRET"
GID=$(cat "$D/group-id.txt")
k get "groups/$GID" -r "$R" --fields name --format csv --noquotes | grep -qx clinic-users \
    || { echo "The group clinic-users does not have the id of realm-dentasoft.json" >&2; exit 1; }

echo "3. Roles of the service account and scope mappings"
RMID=$(id_of clients clientId=realm-management)
while read -r role; do
    [ -n "$role" ] && k add-roles -r "$R" --uusername service-account-dentasoft-backend \
        --cclientid realm-management --rolename "$role"
done < "$D/role-names.txt"
k create "clients/$CID/scope-mappings/clients/$RMID" -r "$R" -f "$D/roles.json"

echo "4. User policy and Groups permission"
APC=$(id_of clients clientId=admin-permissions)
RS="clients/$APC/authz/resource-server"
SA=$(id_of users username=service-account-dentasoft-backend)
if ! k get "$RS/policy" -r "$R" -q name=backend-service-account --fields name --format csv --noquotes \
        | grep -qx backend-service-account; then
    k create "$RS/policy/user" -r "$R" -s name=backend-service-account -s logic=POSITIVE -s "users=[\"$SA\"]" >/dev/null
fi
PID=$(k get "$RS/policy" -r "$R" -q name=backend-service-account --fields id --format csv --noquotes | head -n 1)
if ! k get "$RS/policy" -r "$R" -q name=backend-manages-clinic-users --fields name --format csv --noquotes \
        | grep -qx backend-manages-clinic-users; then
    k create "$RS/permission/scope" -r "$R" -s name=backend-manages-clinic-users -s resourceType=Groups \
        -s "resources=[\"$GID\"]" -s "scopes=$(cat "$D/scopes.json")" -s "policies=[\"$PID\"]" >/dev/null
fi

echo "5. Clinic test users in clinic-users"
while read -r username; do
    [ -z "$username" ] && continue
    UID_=$(id_of users "username=$username")
    [ -n "$UID_" ] && k update "users/$UID_/groups/$GID" -r "$R" -s realm=$R -s "userId=$UID_" -s "groupId=$GID" -n
done < "$D/clinic-users.txt"

echo "6. Check: the service account cannot touch the platform administrator"
SACFG=/tmp/m02t05/sa.config
$KC config credentials --config "$SACFG" --server http://localhost:8080 --realm "$R" \
    --client dentasoft-backend --secret "$DENTASOFT_KC_BACKEND_CLIENT_SECRET" >/dev/null
PLAT_ROLE=$(k get "roles/PLATFORM_ADMIN" -r "$R" --fields id --format csv --noquotes)
failed=0
while read -r username; do
    [ -z "$username" ] && continue
    PLAT=$(id_of users "username=$username")
    if $KC get "users/$PLAT" -r "$R" --config "$SACFG" >/dev/null 2>&1; then
        echo "   FAIL: the service account can read $username"; failed=1
    fi
    if $KC create "users/$SA/role-mappings/realm" -r "$R" --config "$SACFG" \
            -b "[{\"id\":\"$PLAT_ROLE\",\"name\":\"PLATFORM_ADMIN\"}]" >/dev/null 2>&1; then
        echo "   FAIL: the service account gave itself PLATFORM_ADMIN"; failed=1
        k delete "users/$SA/role-mappings/realm" -r "$R" -b "[{\"id\":\"$PLAT_ROLE\",\"name\":\"PLATFORM_ADMIN\"}]"
    fi
done < "$D/platform-users.txt"
SEEN=$($KC get users -r "$R" --config "$SACFG" --fields username --format csv --noquotes | sort | tr '\n' ' ')
echo "   The service account sees: $SEEN"
[ "$failed" = 0 ] && echo "   OK: 403 for the platform administrator" || exit 1
SH
echo "Done."
