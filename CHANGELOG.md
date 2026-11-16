# Changelog

All notable changes to fsx-cells are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Run cells of `.fsx` files, delimited by `// %%` or `(* cell *)` lines, with Ctrl+Enter, Shift+Enter and
  Ctrl+Shift+Enter, and see the result under the cell.
- A toolbar above each cell and a run icon in the gutter, with Run All Cells, Run Cells Up to Here and more.
- One F# session per file, using the F# compiler of the installed .NET 10+ SDK.
- Tables for sequences of records, objects and unions, and property trees for objects, drawn as HTML when the
  embedded browser is available.
- `[image]` and `[html]` cell annotations to show a value as an image or as HTML.
- Compile errors and the line of a runtime exception are underlined in the script.
- `dotnet` is found on PATH, in `DOTNET_ROOT` or in the usual install folders (also on macOS when Rider is started
  from the Dock), or set in Settings | Tools | FSX Cells.
- A notification explains a missing `dotnet` or a .NET SDK older than 10 (including one pinned by `global.json`),
  with links to download .NET and to the settings.
- Cells don't run in a project Rider doesn't trust; a notification offers to trust it.
- Interrupt a running cell with the Stop link under it or Ctrl+F2; the session keeps its values.
- What a cell prints is shown while it runs, with the time it has been running.
