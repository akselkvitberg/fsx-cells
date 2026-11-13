#!/usr/bin/env bash
# Runs the published host the way the plugin does (`dotnet FsxCells.Host.dll`, JSON lines over stdin/stdout) and
# checks a few evaluations. Unlike host/FsxCells.Host.Tests, which bring their own F# compiler, this uses the F#
# compiler of the installed .NET SDK, so it catches an SDK whose compiler the host can't use.
#
# Usage: host/smoke-test.sh [dotnet]   (from any folder; works in Git Bash on Windows)
set -euo pipefail

dotnet=${1:-dotnet}
repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

echo "SDK: $("$dotnet" --version)"
"$dotnet" publish "$repo/host/FsxCells.Host" -c Release -o "$work/host" --nologo -v quiet

# The host needs a real path (on Windows, D:/... rather than Git Bash's /d/...).
cd "$work"
dir=$(pwd -W 2>/dev/null || pwd)
script="$dir/smoke.fsx"
: > smoke.fsx

# One request per line; \n inside "code" is a JSON escape.
cat > requests.jsonl <<EOF
{"type":"init","scriptPath":"$script"}
{"type":"eval","id":1,"code":"[1; 2; 3] |> List.sum","startLine":1}
{"type":"eval","id":2,"code":"printfn \"hello from fsi\"","startLine":1}
{"type":"eval","id":3,"code":"type P = { Name: string; Age: int }\n[ { Name = \"Ada\"; Age = 36 }; { Name = \"Grace\"; Age = 45 } ]","startLine":1}
{"type":"eval","id":4,"code":"fsi.PrintLength <- 3\nfsi.PrintLength","startLine":1}
{"type":"eval","id":5,"code":"let b: string = 1","startLine":7}
EOF

# The host answers every request, then exits when stdin closes.
"$dotnet" "$work/host/FsxCells.Host.dll" < requests.jsonl > responses.jsonl 2> stderr.txt || {
    echo "The host exited with code $?:"
    cat stderr.txt
    exit 1
}

failed=0
check() { # check <id> <description> <fixed string the response must contain>...
    local id=$1 what=$2
    shift 2
    local line
    line=$(grep -F "\"id\":$id," responses.jsonl || true)
    for expected in "$@"; do
        if [[ "$line" != *"$expected"* ]]; then
            echo "FAIL $what: no '$expected' in response $id: ${line:-<none>}"
            failed=1
            return
        fi
    done
    echo "ok   $what"
}

check 1 "value" '"ok":true' '"valueText":"6"'
check 2 "stdout" '"ok":true' 'hello from fsi'
check 3 "table of records" '"ok":true' '"valueHtml":' 'Grace'
check 4 "fsi settings object" '"ok":true' '"valueText":"3"'
check 5 "compile error on the script's line" '"ok":false' '"severity":"error"' '"startLine":7'

if [[ $failed -ne 0 ]]; then
    echo "--- responses"; cat responses.jsonl
    echo "--- host stderr"; cat stderr.txt
    exit 1
fi
