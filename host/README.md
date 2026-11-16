# fsx-cells host

`FsxCells.Host` is a small F# console app that keeps one F# Interactive session alive
(`FsiEvaluationSession` from FSharp.Compiler.Service) and runs cells of a single `.fsx` file.
The Rider plugin starts one host process per `.fsx` file and talks to it over stdin/stdout.

## The F# compiler comes from the installed SDK

The host doesn't ship an F# compiler. At startup it runs `dotnet --version` in the script's folder (so
`global.json` applies, as for `dotnet fsi`) and loads FSharp.Compiler.Service, FSharp.Core and
FSharp.Compiler.Interactive.Settings from that SDK's `sdk/<version>/FSharp` folder. New F# and .NET versions
work without a new host; the host runs on the newest installed .NET (`RollForward=LatestMajor`).

- It is compiled against FSharp.Compiler.Service 43.10.100 and FSharp.Core 10.0.100 (the .NET 10.0.100 SDK) and
  uses a small part of the compiler API. An SDK whose compiler changed that part breaks the host until it is
  updated.
- `Sdk.fs` installs the assembly resolver as the first thing `main` does, before any F# library code runs.
- Set `FSXCELLS_FSHARP_DIR` to a folder with FSharp.Compiler.Service.dll and FSharp.Core.dll to use those instead.
- Without a usable SDK the host prints why to stderr and exits with code 2; the plugin shows that message as a
  setup problem. (The plugin checks first that `dotnet` exists and picks SDK 10 or newer in the script's folder,
  and runs the host with that `dotnet`; see "Finding dotnet" in the main README.)

```bash
dotnet build host/FsxCells.Host
dotnet test host/FsxCells.Host.Tests
host/smoke-test.sh   # the published host, with the installed SDK's F# compiler
```

## Protocol

JSON lines: one JSON object per line, UTF-8, `\n`-terminated. Requests go to stdin, responses
come from stdout. Requests are handled one at a time, in order. Anything the evaluated code
writes to the console outside an evaluation goes to stderr, so stdout carries only protocol lines.

There is no reset or stop request: the plugin restarts the process for that.

### `init`

```json
{"type":"init","scriptPath":"C:\\git\\AdventOfCode\\2025\\Day10.fsx"}
```

Sets the script path and changes the current directory to the script's folder. Send it first:
the F# session is created after it (in the background, to warm up), and FSI resolves relative
`#load` paths against the directory it was created in. No response.

### `eval`

```json
{"type":"eval","id":1,"code":"let x = 5\nprintfn \"hi\"\nx + 1","startLine":12}
```

| Field | Meaning |
|---|---|
| `id` | String or number; echoed back unchanged in the response. |
| `code` | The cell body (without the marker line). |
| `startLine` | 1-based line in the script of the first line of `code`. Optional, defaults to 1. |
| `display` | `"image"` or `"html"`: show the value as an image or as raw HTML (see [Display annotations](#display-annotations)). Optional. Any other value is a `protocolError`. |

The response:

```json
{"type":"result","id":1,"ok":true,"stdout":"hi\n","valueText":"6","typeName":"int","diagnostics":[],"elapsedMs":12}
```

| Field | Meaning |
|---|---|
| `type` | Always `"result"`. |
| `id` | The request's id. |
| `ok` | `false` for compile errors (any diagnostic with severity `error`) and runtime exceptions. |
| `stdout` | Everything the cell wrote to stdout and stderr, in order, with `\n` line endings. |
| `valueText` | The cell's value as display text. Missing for `unit` and for declarations (`let x = ...`). |
| `valueHtml` | The value as HTML, when it has a table or object layout (see below). |
| `typeName` | F# type of the value, e.g. `int`, `Set<int * int>`, `char array2d`. Missing when `valueText` is. |
| `error` | Compile errors as `(line,col): message` lines, or the runtime exception as `Type: message` followed by the stack frames from the script. |
| `diagnostics` | `[{startLine,startCol,endLine,endCol,severity,message,fileName}]`. Lines are 1-based script lines; columns are 0-based. `severity` is `error`, `warning`, `info` or `hidden`. `fileName` is the script, or a `#load`ed file. |
| `elapsedMs` | Time spent evaluating. |

Line numbers are the script's own lines because the host pads `code` with `startLine - 1` empty
lines and passes the script path to FSI. That also makes `__SOURCE_DIRECTORY__`, `__LINE__` and
stack trace lines match the file.

A compile error:

```json
{"type":"eval","id":2,"code":"let b: string = x","startLine":20}
{"type":"result","id":2,"ok":false,"stdout":"","error":"(20,17): This expression was expected to have type\n    'string'    \nbut here has type\n    'int'    ","diagnostics":[{"startLine":20,"startCol":16,"endLine":20,"endCol":17,"severity":"error","message":"This expression was expected to have type\n    'string'    \nbut here has type\n    'int'    ","fileName":"C:\\git\\AdventOfCode\\2025\\Day10.fsx"}],"elapsedMs":40}
```

A runtime exception:

```json
{"type":"eval","id":3,"code":"(failwith \"boom\" : int)","startLine":25}
{"type":"result","id":3,"ok":false,"stdout":"","error":"System.Exception: boom\n   at <StartupCode$FSI_0004>.$FSI_0004.main@() in C:\\git\\AdventOfCode\\2025\\Day10.fsx:line 25","diagnostics":[],"elapsedMs":30}
```

A request the host can't read gets a `protocolError` reply (with the `id` if it could be read):

```json
{"type":"protocolError","id":4,"ok":false,"error":"missing 'code'"}
```

### Value formatting

| Value | Shown as |
|---|---|
| `string` | The raw text: real line breaks, no quotes. |
| `DateOnly`, `DateTime`, `DateTimeOffset`, `TimeOnly` | ISO 8601 whatever the culture: `1969-07-20`, `1969-07-20 20:17:40`, `1969-07-20 20:17:40 +02:00`, `20:17:40`. A `DateTime` at midnight is just the date (unless it is UTC, which ends in `Z`); fractions of a second only when there are any. Also inside tables and objects. |
| A sequence of records, anonymous records or objects | A table: a `#` column, then one column per field or property. |
| A sequence of union values (`Result` too) | A table: a `Case` column, then the case's fields: named fields by name, unnamed ones as `Item`, `Item1`, `Item2`. Fields another case doesn't have are left empty. Options and unions whose cases have no fields keep `%A`. |
| A dictionary (`Map`, `dict`, `Dictionary`, ...) | A table with a `key` column, then the value's fields, or a `value` column. |
| An object (a class or struct without its own `ToString`) | Its type name, then one indented line per public property and field. Nested objects, tables and dictionaries are indented under the property. |
| A type with an `fsi.AddPrinter` printer | The printer's text, also inside tables and objects. |
| `unit` | No value; only the output. |
| Anything else (single records and union values, tuples, lists of plain values, ...) | `sprintf "%A"` at width 120. |

### HTML

Tables (of records, objects or unions), dictionaries and objects also get HTML (`valueHtml`), which the plugin
shows in an embedded browser: real tables, and nested objects and tables folded into `<details>`. Other values
(single records, unions, strings, values with a printer, ...) only have text. The markup has no styles of
its own; the plugin styles its `fc-*` classes to match the editor.

### Display annotations

The plugin sends `display` when the cell's marker ends with `[image]` or `[html]` (`// %% Plot [image]`). The
value is then not formatted as above: it is converted to `valueHtml`, with a short `valueText` for when there is
no browser view. `typeName` is the value's type as usual. A cell without a value (`unit`, declarations) has no
output. A value of the wrong kind fails the cell (`ok: false`, with the message in `error`).

| `display` | Accepted values | `valueHtml` | `valueText` |
|---|---|---|---|
| `image` | `byte[]` holding a PNG, JPEG, GIF, WebP or BMP image (the type is read from the first bytes) | `<img src="data:<mime>;base64,...">` | `image/png, 640×480, 23 KB` (no dimensions for WebP and SVG) |
| `image` | A `string` starting with `<svg`, or with `<?xml` and containing `<svg` | an `<img>` with an `image/svg+xml` data URI | `image/svg+xml, 1 KB` |
| `image` | Any other `string`: the path of an image file, relative to the script's folder. `.svg` files are SVG, others are read by their first bytes. | as above | as above |
| `html` | A `string` | the string, unchanged | the string, so the markup shows when there is no browser view |

### Limits and printers

Tables and objects follow .NET Interactive's plain-text formatter: sequences show 20 items and then `(N more)`, or
`(more)` for a lazy sequence, which is enumerated only that far. Nested objects are expanded 6 levels deep, so
reference cycles stop. Table cells are cut to 60 characters. A property whose getter throws shows
`<ExceptionType: message>`.

These are set with F# Interactive's own settings, which also work in `dotnet fsi`:

| Setting | Effect |
|---|---|
| `fsi.AddPrinter(fun (x: T) -> ...)` | Values of `T` (and subtypes) are shown as that text, at the top and inside tables and objects. An exact type match wins over a base type; otherwise the latest printer wins. |
| `fsi.PrintLength <- n` | Items shown before `(N more)`. While it has fsi's default (100), fsx-cells uses 20. |
| `fsi.PrintDepth <- n` | Levels of nested objects. While it has fsi's default (100), fsx-cells uses 6. |

`fsi.AddPrintTransformer` and `fsi.AddHtmlPrinter` are not used.

## Session details

- Scripts get the SDK's `fsi` object, so `fsi.AddPrinter`, `fsi.PrintLength` and `fsi.CommandLineArgs` (the
  script path) work as in `dotnet fsi`. If it can't be set up, cells still run without it and the next cell's
  output says why.
- Options: `--noninteractive --quiet --multiemit --gui- --langversion:preview`. Preview language features are
  always on, so a script that uses them needs `dotnet fsi --langversion:preview` to run outside the plugin.
- Evaluations run on a dedicated thread with a 256 MB stack, so deep recursion works.
- `Console.Out` and `Console.Error` are swapped for a `StringWriter` during each evaluation.
