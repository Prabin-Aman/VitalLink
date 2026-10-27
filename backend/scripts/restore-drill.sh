#!/bin/sh
# Restore drill (docs/08 §8.4): back up exactly as .github/workflows/backup.yml does, decrypt, restore
# into a new database on the same server, and compare row counts table by table.
#
#   SOURCE_URL=postgresql://user:pass@host:5432/vitallink sh backend/scripts/restore-drill.sh
#
# Needs Docker and gpg. The client tools are the server's major version (the workflow uses postgres:17
# because Supabase runs 17): a dump made by newer tools doesn't restore into an older server. The restored database is
# <source db>_restore; it is dropped first if it exists. Point the API at it afterwards
# (DATABASE_URL=…_restore) and run SystemCheck to prove the app works on the restored data.
set -eu
: "${SOURCE_URL:?set SOURCE_URL}"
PASSPHRASE=${BACKUP_PASSPHRASE:-drill-$(date +%s)}
RESTORE_URL=$(printf '%s' "$SOURCE_URL" | sed -E 's#/([^/?]+)(\?.*)?$#/\1_restore\2#')
RESTORE_DB=$(printf '%s' "$RESTORE_URL" | sed -E 's#.*/([^/?]+)(\?.*)?$#\1#')
ADMIN_URL=$(printf '%s' "$SOURCE_URL" | sed -E 's#/([^/?]+)(\?.*)?$#/postgres\2#')
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
IMAGE=postgres:17
pg() { docker run --rm -i --network host -v "$work:/work" -e PGURL="$1" "$IMAGE" sh -c "$2"; }
# Runs the SQL on stdin (so the shell never sees its $$ quotes) and prints unaligned rows.
sql() { pg "$1" 'psql "$PGURL" -v ON_ERROR_STOP=1 -At -F " "' | tr -d '\r'; }

IMAGE=postgres:$(echo 'SHOW server_version_num;' | sql "$SOURCE_URL" | cut -c1-2)
echo "1. dump (as backup.yml, with $IMAGE tools)"
pg "$SOURCE_URL" 'pg_dump "$PGURL" --schema=vitallink --format=custom --no-owner' > "$work/vitallink.dump"
test -s "$work/vitallink.dump"
gpg --batch --yes --symmetric --cipher-algo AES256 --passphrase "$PASSPHRASE" \
  --output "$work/vitallink.dump.gpg" "$work/vitallink.dump"
rm "$work/vitallink.dump"
echo "   encrypted backup: $(wc -c < "$work/vitallink.dump.gpg") bytes"

echo "2. decrypt and restore into $RESTORE_DB"
gpg --batch --quiet --decrypt --passphrase "$PASSPHRASE" "$work/vitallink.dump.gpg" > "$work/vitallink.dump"
printf 'DROP DATABASE IF EXISTS %s;\nCREATE DATABASE %s;\n' "$RESTORE_DB" "$RESTORE_DB" | sql "$ADMIN_URL"
pg "$RESTORE_URL" 'pg_restore --no-owner --exit-on-error --dbname "$PGURL" /work/vitallink.dump'

echo "3. compare row counts"
q=$(sql "$SOURCE_URL" <<'SQL'
SELECT string_agg(format($$SELECT %L AS t, count(*) AS n FROM vitallink.%I$$, relname, relname), $$ UNION ALL $$)
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'vitallink' AND c.relkind = 'r';
SQL
)
printf '%s ORDER BY 1;' "$q" | sql "$SOURCE_URL" > "$work/source.txt"
printf '%s ORDER BY 1;' "$q" | sql "$RESTORE_URL" > "$work/restore.txt"
if ! diff "$work/source.txt" "$work/restore.txt"; then
  echo "   row counts differ"
  exit 1
fi
echo "   $(wc -l < "$work/source.txt") tables, $(awk '{s+=$2} END {print s}' "$work/source.txt") rows: identical"

no_rls=$(sql "$RESTORE_URL" <<'SQL'
SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'vitallink' AND c.relkind = 'r' AND NOT c.relrowsecurity AND relname <> 'flyway_schema_history';
SQL
)
test "$no_rls" = 0 || { echo "   $no_rls tables lost row-level security"; exit 1; }
echo "   row-level security is on for every table in the restore"
