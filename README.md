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
- Ctrl+Enter runs the cell under the caret, Ctrl+Shift+Enter runs all cells up to it.
  Each file has its own F# session; restart it from Tools > FSX Cells.

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
