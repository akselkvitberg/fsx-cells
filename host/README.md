# fsx-cells host

`FsxCells.Host` is a small F# console app that keeps one F# Interactive session alive
(`FsiEvaluationSession` from FSharp.Compiler.Service) and runs cells of a single `.fsx` file.
The Rider plugin starts one host process per `.fsx` file and talks to it over stdin/stdout.

```bash
dotnet build host/FsxCells.Host
dotnet test host/FsxCells.Host.Tests
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
| `outputs` | Only when the cell called `display`: `[{type,text,html?}]` in order, `type` being `text` (printed output) or `display` (a displayed value, with `html` when it has an HTML layout). `stdout` still has all the text. |
| `valueText` | The cell's value as display text. Missing for `unit` and for declarations (`let x = ...`). |
| `valueHtml` | The value as HTML, when it has a table or object layout or a `text/html` formatter and HTML is on (see below). |
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
| `char[,]`, `char[][]` | One line per row. |
| `string[]`, `string list` | One line per item. |
| `bool[,]` | `#` for `true`, `.` for `false`. |
| `Set<int*int>`, `(int*int)` list / array / seq / `ResizeArray` / `HashSet` | `#` at each point on a `.` grid sized to the bounding box. Points are `(x, y)`: x is the column, y the row. Empty or huge collections fall back to `%A`. |
| `Map<int*int, char>` | The chars on a grid sized to the bounding box, spaces for missing points. |
| A sequence of records, anonymous records or objects | A table: a `#` column, then one column per field or property. |
| A dictionary (`Map`, `dict`, `Dictionary`, ...) | A table with a `key` column, then the value's fields, or a `value` column. |
| An object (a class or struct without its own `ToString`) | Its type name, then one indented line per public property and field. Nested objects, tables and dictionaries are indented under the property. |
| A type with a registered formatter | The registered formatter's text, also inside tables and objects. |
| `unit` | No value; only the output. |
| Anything else (single records, unions, tuples, lists of plain values, ...) | `sprintf "%A"` at width 120. |

### HTML

Tables, dictionaries, objects and values with a `text/html` formatter also get HTML (`valueHtml`, or `html` in
`outputs`), which the plugin shows in an embedded browser: real tables, and nested objects and tables folded into
`<details>`. Other values (single records, unions, grids, strings, ...) only have text. The markup has no styles of
its own; the plugin styles its `fc-*` classes to match the editor. `Formatter.DefaultMimeType <- "text/plain"`
turns HTML off; `Formatter.SetPreferredMimeTypesFor(typeof<T>, "text/plain")` turns it off for one type.

### Limits

Tables and objects follow .NET Interactive's plain-text formatter: sequences show `Formatter.ListExpansionLimit`
items (20) and then `(N more)`, or `(more)` for a lazy sequence, which is enumerated only that far. Nested objects
are expanded `Formatter.RecursionLimit` levels deep (6), so reference cycles stop. Table cells are cut to 60
characters. A property whose getter throws shows `<ExceptionType: message>`.

### `display` and `Formatter`

Every session references the host assembly and opens `FsxCells.Interactive`, which has the parts of .NET
Interactive's formatting API:

```fsharp
display x                         // show x under the cell now, in order with printfn output
x.Display()                       // the same
x.Display("text/plain")           // text even when it has an HTML layout
x.Display("application/json")     // System.Text.Json, indented
displayHtml "<b>hi</b>"           // raw HTML, like Polyglot's HTML(...)
x.ToDisplayString()               // the text, without showing it
x.ToDisplayString("text/html")    // the HTML (or the text, if it has no HTML layout)

Formatter.Register(fun (p: Person) -> sprintf "%s (%d)" p.Name p.Age)          // text, used in HTML too
Formatter.Register((fun (r: Rect) -> sprintf "<svg>...</svg>"), "text/html")  // HTML
Formatter.Register(typedefof<list<_>>, fun o -> "a list")   // open generic types work too
Formatter.ListExpansionLimit <- 50
Formatter.RecursionLimit <- 2
Formatter.DefaultMimeType <- "text/plain"                   // no HTML
Formatter.SetPreferredMimeTypesFor(typeof<Person>, "text/plain")
Formatter.ResetToDefault()
```

The most specific registered formatter wins: the type itself, then base classes, then interfaces, then `obj`;
the latest registration wins a tie. `Register` without a MIME type registers a `text/plain` formatter (Polyglot's
default was `text/html`). In HTML, a type's `text/html` formatter is used if it has one, else its `text/plain`
one (escaped). These names don't exist in plain `dotnet fsi`.

## Session details

- Options: `--noninteractive --quiet --multiemit`.
- Evaluations run on a dedicated thread with a 256 MB stack, so deep recursion works.
- `Console.Out` and `Console.Error` are swapped for a `StringWriter` during each evaluation.
