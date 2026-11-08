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

## Output blocks

Output appears under the cell as a block you can select and copy from (Ctrl+C, or right-click > Copy / Copy All /
Open in Editor Tab). Blocks are at most 20 lines high and scroll beyond that. Editing a cell greys out its output
until you run it again; compile errors and the script line a runtime exception came from get a wavy underline.

## Converting Polyglot notebooks

```bash
dotnet fsi tools/dib2fsx.fsx <file.dib | folder> [--force]
```

Writes a `.fsx` next to each `.dib`: F# cells become `// %%` cells, markdown becomes comments, other languages are
commented out, and the `open`s Polyglot added automatically (`System`, `System.IO`, `System.Text`) are added at the
top. Lines that use Polyglot-only APIs such as `display` are listed so you can fix them by hand.

## Releases

The `Build` workflow builds and tests on every push. To make a release, push a version tag; the workflow builds
the plugin with that version and attaches the zip to a GitHub release (it isn't published to the Marketplace):

```bash
git tag v0.2.0
git push origin v0.2.0
```
