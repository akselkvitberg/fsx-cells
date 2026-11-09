// Converts Polyglot Notebook .dib files to fsx-cells scripts.
//
//   dotnet fsi tools/dib2fsx.fsx <file.dib | folder> ... [--force]
//
// For each .dib, writes a .fsx next to it (skipped if it exists, unless --force):
// - #!fsharp / #!f# cells   -> a `// %%` cell with the code
// - #!markdown cells        -> `//` comment lines (kept above the next cell)
// - other language cells    -> commented out, with a note
// - the #!meta block        -> dropped
// - #!magic lines inside F# cells (e.g. #!time) -> commented out
// - `open System`, `open System.IO`, `open System.Text` are added at the top: Polyglot's F# kernel opened
//   these automatically, plain `dotnet fsi` does not.
// `display`, `.Display()` and `Formatter` work in fsx-cells (not in plain `dotnet fsi`). Other Polyglot-only
// APIs such as `Kernel` and `HTML` are left as they are; the lines that use them are listed so you can fix
// them by hand.

open System
open System.IO

let kernels =
    set [ "meta"; "fsharp"; "f#"; "csharp"; "c#"; "cs"; "markdown"; "md"; "pwsh"; "powershell"
          "javascript"; "js"; "html"; "mermaid"; "sql"; "kql"; "value"; "http" ]

/// Kernel name if the line starts a new cell, e.g. "#!fsharp" -> Some "fsharp".
let cellKernel (line: string) =
    if line.StartsWith "#!" then
        let name = line.Substring(2).Trim().Split(' ').[0].ToLowerInvariant()
        if kernels.Contains name then Some name else None
    else None

let trimBlankEnds (lines: string list) =
    lines
    |> List.skipWhile String.IsNullOrWhiteSpace
    |> List.rev
    |> List.skipWhile String.IsNullOrWhiteSpace
    |> List.rev

let convert (text: string) =
    let lines = text.Replace("\r\n", "\n").Split('\n') |> List.ofArray

    // Split into (kernel, body) cells. Text before the first header is treated as F#.
    let cells =
        let rec go current body acc rest =
            match rest with
            | [] -> List.rev ((current, List.rev body) :: acc)
            | l :: tail ->
                match cellKernel l with
                | Some k -> go k [] ((current, List.rev body) :: acc) tail
                | None -> go current (l :: body) acc tail
        go "fsharp" [] [] lines
        |> List.map (fun (k, body) -> k, trimBlankEnds body)
        |> List.filter (fun (_, body) -> not body.IsEmpty)

    let out = Collections.Generic.List<string>()
    let comment (l: string) = if String.IsNullOrWhiteSpace l then "//" else "// " + l

    // Namespaces Polyglot's F# kernel opened by default (UseDefaultFormatting in .NET Interactive).
    let fsharpLines = cells |> List.filter (fun (k, _) -> k = "fsharp" || k = "f#") |> List.collect snd
    let defaultOpens =
        [ "open System"; "open System.IO"; "open System.Text" ]
        |> List.filter (fun o -> not (fsharpLines |> List.exists (fun l -> l.Trim() = o)))
    if not fsharpLines.IsEmpty && not defaultOpens.IsEmpty then
        out.Add "// Opened automatically by Polyglot Notebooks; needed when running as a plain script."
        out.AddRange defaultOpens
        out.Add ""

    for kernel, body in cells do
        match kernel with
        | "meta" -> ()
        | "fsharp" | "f#" ->
            if out.Count > 0 && out[out.Count - 1] <> "" && not (out[out.Count - 1].StartsWith "//") then out.Add ""
            out.Add "// %%"
            for l in body do
                out.Add(if l.StartsWith "#!" then "// " + l else l)
            out.Add ""
        | "markdown" | "md" ->
            for l in body do out.Add(comment l)
        | other ->
            out.Add(sprintf "// [%s cell, not converted]" other)
            for l in body do out.Add(comment l)
            out.Add ""

    String.Join("\n", out).TrimEnd() + "\n"

/// Polyglot-only APIs that fsx-cells doesn't provide.
let polyglotOnly =
    Text.RegularExpressions.Regex(@"\b(Kernel\.|DisplayedValue|HTML\(|PocketView|\.DisplayTable\()")

let warnings (fsx: string) =
    fsx.Split('\n')
    |> Array.mapi (fun i l -> i + 1, l)
    |> Array.filter (fun (_, l) -> not (l.TrimStart().StartsWith "//") && polyglotOnly.IsMatch l)

let args = fsi.CommandLineArgs |> Array.skip 1
let force = args |> Array.contains "--force"

let files =
    args
    |> Array.filter (fun a -> a <> "--force")
    |> Array.collect (fun a ->
        if Directory.Exists a then Directory.GetFiles(a, "*.dib", SearchOption.AllDirectories)
        elif File.Exists a then [| a |]
        else
            eprintfn "Not found: %s" a
            [||])

if files.Length = 0 then
    eprintfn "Usage: dotnet fsi tools/dib2fsx.fsx <file.dib | folder> ... [--force]"
    exit 1

for dib in files do
    let target = Path.ChangeExtension(dib, ".fsx")
    if File.Exists target && not force then
        printfn "skip   %s (exists, use --force)" target
    else
        let fsx = convert (File.ReadAllText dib)
        File.WriteAllText(target, fsx)
        printfn "wrote  %s" target
        for line, text in warnings fsx do
            printfn "       line %d uses a Polyglot-only API: %s" line (text.Trim())
