# fsx-cells

<!-- Plugin description -->
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
  - Ctrl+Enter (⌘↩ on macOS): run the cell under the caret.
  - Shift+Enter (⇧↩): run it and move to the next cell (on the last cell, add a new one).
  - Ctrl+Shift+Enter (⌘⇧↩): run it and add a new cell below.
  - Ctrl+F2 (⌘F2): interrupt the running cell (also the Stop link under it). The session keeps its values.
  - Ctrl+Alt+Page Down / Page Up (⌃⌥Page Down / Page Up): move the caret to the next / previous cell.

  In .fsx files the Enter shortcuts take the place of Split Line, Start New Line and Complete Statement. To use other
  keys, search for "FSX Cells" in Settings | Keymap. Send to F# Interactive (Ctrl+\\) still works as before.
- Other actions, in Tools > FSX Cells (no default keys): Run Cells Up to Here, Run Cells Below (the cell under the
  caret and all after it), Run All Cells, Restart F# Session, Restart Session and Run All, Open Cell Output in Tab and
  Clear Cell Outputs. Runs stop at the first cell that fails.
- Files are saved before cells run, because `#load`ed files are read from disk.
- Each cell has a toolbar above it (Run cell, Run up to here, Run cells below, Add cell above) and a run icon in the
  gutter; right-click the icon for Run All Cells, Restart Session and Run All, Add Cell Below and more.
- What a cell prints shows up while it runs.
- Each file has its own F# session, stopped when you close the file; restart it from Tools > FSX Cells.
- Sequences of records, objects and unions are shown as tables; `fsi.AddPrinter`, `fsi.PrintLength` and
  `fsi.PrintDepth` tune the output. Cells marked `[image]` or `[html]` show images and HTML.

Requires a .NET 10 or newer SDK: cells are compiled by the installed SDK's F# compiler, the one `dotnet fsi` uses
in the script's folder. `dotnet` is found on PATH or in the usual install folders; to use another one, set its path
in Settings | Tools | FSX Cells.

Cells run on the machine Rider runs on. Remote development (JetBrains Gateway) isn't supported.
<!-- Plugin description end -->

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

`host/smoke-test.sh` publishes the host and runs a few cells through it with the installed SDK's F# compiler, the way
the plugin does (most of the host's unit tests run in-process with their own compiler).

CI builds and tests on Linux, Windows and macOS. A weekly workflow (`Newest .NET SDK`) runs the host tests and the
smoke test against the newest .NET SDK, previews included, to catch an SDK whose F# compiler the host can't use.

## Finding dotnet

Before starting a file's F# session the plugin looks for `dotnet` in this order, and uses the first it finds:

1. The path set in Settings | Tools | FSX Cells, if any.
2. PATH (on macOS, the PATH of your login shell, even when Rider was started from the Dock).
3. `DOTNET_ROOT`.
4. The usual install folders: `%ProgramFiles%\dotnet` on Windows; `/usr/local/share/dotnet`, Homebrew,
   `/usr/share/dotnet`, `/usr/lib/dotnet` and similar on macOS and Linux; and `~/.dotnet` everywhere.

It then runs `dotnet --version` in the script's folder, so `global.json` applies as it does for `dotnet fsi`. If
`dotnet` isn't found, or the SDK it picks is older than .NET 10, the cell shows why and a notification offers to
download .NET or open the settings. The session also gets that `dotnet`'s folder first on PATH, so processes the
script starts use the same one.

## Output blocks

Output appears under the cell as a block you can select and copy from (Ctrl+C, or right-click > Copy / Copy All /
Open in Editor Tab). Blocks are at most 20 lines high (25 for HTML) and scroll beyond that. Editing a cell greys out its output
until you run it again; compile errors and the script line a runtime exception came from get a wavy underline.

While a cell runs, its block says for how long and has a Stop link, and what the cell prints appears as it comes
(the last 1000 lines; all of it once the cell is done). Stop (or Ctrl+F2, or Tools > FSX Cells > Interrupt Cell)
interrupts the cell like Ctrl+C in `dotnet fsi`: it stops where it is, along with the cells waiting to run after
it, and the session keeps the values of earlier cells. A cell that doesn't stop within 5 seconds (stuck in native
code, say) gets its session restarted instead.

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

The `Build` workflow builds, tests and runs the JetBrains Plugin Verifier on every push. To make a release:

1. Move the `Unreleased` notes in [CHANGELOG.md](CHANGELOG.md) under a version heading (or run
   `./gradlew patchChangelog -Pversion=0.2.0` in `plugin/`) and commit.
2. Push a version tag:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

The workflow builds the plugin with that version, attaches the zip to a GitHub release and, when the
`PUBLISH_TOKEN` secret is set, publishes it to the JetBrains Marketplace. A tag with a pre-release suffix
(`v0.3.0-eap.1`) goes to the Marketplace's `eap` channel instead of the default one.

The plugin page's description comes from the part of this README between the `Plugin description` comments, and
its change notes from CHANGELOG.md.

Repository secrets used by the workflow:

| Secret | Use |
|---|---|
| `PUBLISH_TOKEN` | A JetBrains Marketplace token (marketplace profile > My Tokens). Without it, nothing is published. |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Optional: sign the plugin zip ([how](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)). The Marketplace signs plugins either way. |

The first version has to be uploaded by hand at <https://plugins.jetbrains.com/plugin/add> (build it with
`./gradlew buildPlugin -Pversion=0.6.0`); JetBrains reviews new plugins before they're listed.

## License

[MIT](LICENSE)
