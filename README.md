# fsx-cells

Run cells of plain F# script (`.fsx`) files in Rider, notebook-style, and see the result right under each cell.

```fsharp
// %% Parse
let grid = input.Split('\n') |> Array.map _.ToCharArray()
grid            // Ctrl+Enter -> the grid is drawn under this line

(* cell *)
grid.Length
```

- Cells start at a `// %%` line (optionally with a title, `// %% Part 1`) or a `(* cell *)` line.
  Text before the first marker is a cell too.
- Files stay ordinary scripts: `dotnet fsi day01.fsx` runs the whole thing.
- Shortcuts (in .fsx files only; elsewhere the keys keep their usual meaning):
  - Ctrl+Enter: run the cell under the caret.
  - Shift+Enter: run it and move to the next cell (on the last cell, add a new one).
  - Ctrl+Shift+Enter: run it and add a new cell below.
- Each cell has a toolbar above it (Run cell, Run up to here, Add cell above) and a run icon in the gutter;
  right-click the icon for Run All Cells, Add Cell Below and more.
- Each file has its own F# session, stopped when you close the file; restart it from Tools > FSX Cells.

## Layout

- `host/`: F# console app that keeps an F# Interactive session alive and talks JSON lines over
  stdin/stdout. See [host/README.md](host/README.md).
- `plugin/`: the Rider plugin (Kotlin, IntelliJ Platform Gradle Plugin). It bundles the host.
- `samples/`: files to try it on.

## Build

Needs .NET 10 SDK and a JDK 21+ (`JAVA_HOME`).

```bash
cd plugin
./gradlew test buildPlugin   # plugin zip in plugin/build/distributions
./gradlew runIde             # a test Rider with the plugin, opening samples/
```

Install the zip in your own Rider with Settings > Plugins > Install Plugin from Disk.

## Output blocks

Output appears under the cell as a block you can select and copy from (Ctrl+C, or right-click > Copy / Copy All /
Open in Editor Tab). Blocks are at most 20 lines high (25 for HTML) and scroll beyond that. Editing a cell greys out its output
until you run it again; compile errors and the script line a runtime exception came from get a wavy underline.

## Tables, objects and `display`

Values are shown like Polyglot Notebooks shows them: a sequence of records or objects is a table, a dictionary is
a key/value table, and a class is its type name followed by its properties. These are drawn as HTML in an embedded
browser (JCEF), with nested objects and tables you can fold open; other values stay text. Without JCEF, or with
`Formatter.DefaultMimeType <- "text/plain"`, the tables are drawn in text. `display x` (or `x.Display()`) shows a
value partway through a cell, and `Formatter.Register` sets how a type is shown, as text or as HTML:

```fsharp
type Person = { Name: string; Age: int }
[ { Name = "Ada"; Age = 36 }; { Name = "Grace"; Age = 45 } ]
// # │ Name  │ Age
// ──┼───────┼────
// 0 │ Ada   │  36
// 1 │ Grace │  45

Formatter.Register(fun (p: Person) -> sprintf "%s (%d)" p.Name p.Age)
Formatter.Register((fun (p: Person) -> sprintf "<b>%s</b>" p.Name), "text/html")
```

See [host/README.md](host/README.md#value-formatting) for the details. `display` and `Formatter` exist only in
fsx-cells sessions, not in plain `dotnet fsi`.

## Converting Polyglot notebooks

```bash
dotnet fsi tools/dib2fsx.fsx <file.dib | folder> [--force]
```

Writes a `.fsx` next to each `.dib`: F# cells become `// %%` cells, markdown becomes comments, other languages are
commented out, and the `open`s Polyglot added automatically (`System`, `System.IO`, `System.Text`) are added at the
top. `display`, `.Display()` and `Formatter` work as they are; lines that use other Polyglot-only APIs such as
`Kernel` or `HTML` are listed so you can fix them by hand.

## Releases

The `Build` workflow builds and tests on every push. To make a release, push a version tag; the workflow builds
the plugin with that version and attaches the zip to a GitHub release (it isn't published to the Marketplace):

```bash
git tag v0.2.0
git push origin v0.2.0
```
