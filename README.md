# fsx-cells

Run cells of plain F# script (`.fsx`) files in Rider, notebook-style, and see the result right under each cell.

```fsharp
// %% Parse
let grid = input.Split('\n') |> Array.map _.ToCharArray()
grid            // Ctrl+Enter -> the value is shown under this line

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

Needs .NET 10 SDK and a JDK 21+ (`JAVA_HOME`). To use the plugin you need a .NET 10 or newer SDK: cells are
compiled by that SDK's F# compiler (the one `dotnet fsi` uses in the script's folder), so new F# versions work
without a new plugin.

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

## Tables and objects

Values are shown like Polyglot Notebooks shows them: a sequence of records, objects or union values is a table, a
dictionary is a key/value table, and a class is its type name followed by its properties. These are drawn as HTML in
an embedded browser (JCEF), with nested objects and tables you can fold open; without JCEF they are drawn as text.
Other values stay text.

```fsharp
type Person = { Name: string; Age: int }
[ { Name = "Ada"; Age = 36 }; { Name = "Grace"; Age = 45 } ]
// # │ Name  │ Age
// ──┼───────┼────
// 0 │ Ada   │  36
// 1 │ Grace │  45
```

There's no fsx-cells-only API: you tune the output with F# Interactive's own settings, so the script still runs
with `dotnet fsi`:

```fsharp
fsi.AddPrinter(fun (p: Person) -> sprintf "%s (%d)" p.Name p.Age)   // how a type is shown, also inside tables
fsi.PrintLength <- 50                                                // rows before "(N more)" (default 20)
fsi.PrintDepth <- 2                                                  // levels of nested objects (default 6)
```

See [host/README.md](host/README.md#value-formatting) for the details.

## Images and HTML

A cell marker can end with a display annotation in brackets, as in Jupytext's percent format
(`# %% title [markdown]`). Under plain `dotnet fsi` the marker is a comment, so the script is unchanged.

```fsharp
// %% Plot [image]
File.ReadAllBytes "plot.png"

(* cell Report [html] *)
"<h3>Done</h3><b>12</b> rows"

// %% [image]
"<svg xmlns='http://www.w3.org/2000/svg' width='40' height='40'><circle cx='20' cy='20' r='18'/></svg>"
```

- `[image]` shows the value as an image: a `byte[]` with a PNG, JPEG, GIF, WebP or BMP image, an SVG string, or the
  path of an image file (relative to the script's folder).
- `[html]` shows a string as HTML.

The words are not case-sensitive, and only a trailing `[image]` or `[html]` counts: in `// %% Day 3 [part 2]`
the brackets are part of the title. A value of the wrong kind gives an error under the cell. See
[host/README.md](host/README.md#display-annotations) for the details.

## Converting Polyglot notebooks

```bash
dotnet fsi tools/dib2fsx.fsx <file.dib | folder> [--force]
```

Writes a `.fsx` next to each `.dib`: F# cells become `// %%` cells, markdown becomes comments, other languages are
commented out, and the `open`s Polyglot added automatically (`System`, `System.IO`, `System.Text`) are added at the
top. Lines that use Polyglot-only APIs such as `display` or `Formatter` are listed so you can fix them by hand
(`fsi.AddPrinter` replaces `Formatter.Register`).

## Releases

The `Build` workflow builds and tests on every push. To make a release, push a version tag; the workflow builds
the plugin with that version and attaches the zip to a GitHub release (it isn't published to the Marketplace):

```bash
git tag v0.2.0
git push origin v0.2.0
```
