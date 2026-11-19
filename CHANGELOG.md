# Changelog

All notable changes to fsx-cells are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- An F# session that hasn't run a cell for 30 minutes is stopped to free its memory; the next cell starts a new one
  and says so above its output. Set the time, or turn this off, in Settings | Tools | FSX Cells.
- Errors in the plugin can be reported from Rider's error dialog (Report on GitHub): it shows the report, with the
  plugin, Rider, OS and .NET SDK versions and the F# host's last messages, then opens a pre-filled GitHub issue in
  the browser. Nothing is posted until you submit it there.
- Warnings from the compiler are listed in the output block, not only underlined.
- `FsxCells.slnx` at the repository root with the host projects.
- Run Cells Below, and Restart Session and Run All, in the gutter menu and Tools > FSX Cells; Run cells below in
  the cell toolbar.
- Next Cell and Previous Cell, Ctrl+Alt+Page Down and Ctrl+Alt+Page Up in .fsx files.
- Error positions `(line,col)` and stack frame lines of the script in the output are links to that place in the
  script.

### Changed

- All open files are saved before a cell runs, so `#load`ed files that were edited but not saved run as shown.
- An HTML output block (tables, `[html]`, `[image]`) only holds an embedded browser while it is near the visible
  part of the editor, so a file with many such cells uses less memory.

### Fixed

- Scrolling an output with the mouse wheel no longer scrolls the editor at the same time; the editor scrolls once the
  output is at its top or bottom. The output's scroll bars show the normal pointer instead of the text cursor.
- A lazy sequence shown as a table is enumerated once, not once for the text and again for the HTML; a sequence with
  side effects no longer runs them twice, and both forms show the same rows.
- A cell that reads stdin (`Console.ReadLine()`) no longer hangs the session or swallows the next request; it
  reads an empty input.
- The host process exits when Rider is gone, even if a cell never ends.
- Closing a file or restarting a session no longer freezes the IDE while a new session is starting.
- Compile errors are underlined again after Shift+Enter on the last cell and after Ctrl+Shift+Enter.
- Cell toolbars, run icons and shortcuts no longer appear in diff and preview editors of `.fsx` files.
- `// %%` and `(* cell *)` lines inside a triple-quoted string or a block comment no longer start a cell.
- An output block moves to the end of its cell when a new cell marker is added above it.
- Shift+Enter and Ctrl+Enter in the editor's find field no longer run a cell.
- Strings containing line breaks no longer break text tables.
- The host no longer keeps every result in memory for the life of the session.
- `dotnet`'s first-run banner can no longer be mistaken for the SDK version.

## [0.6.0] - 2026-11-16

The first release on the JetBrains Marketplace. Versions up to 0.5.0 were GitHub releases only; this lists everything
up to now.

### Added

- Run cells of `.fsx` files, delimited by `// %%` or `(* cell *)` lines, with Ctrl+Enter, Shift+Enter and
  Ctrl+Shift+Enter (⌘↩, ⇧↩ and ⌘⇧↩ on macOS), and see the result under the cell.
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

### Changed

- Dates and times are shown as ISO 8601 (`1969-07-20`, `1969-07-20 20:17:40`), whatever the culture, on their own
  and in tables and objects.

[Unreleased]: https://github.com/akselkvitberg/fsx-cells/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/akselkvitberg/fsx-cells/compare/v0.5.0...v0.6.0
