/// A persistent F# Interactive session that evaluates cells and captures their output.
module FsxCells.Host.Session

open System
open System.Collections.Concurrent
open System.Diagnostics
open System.IO
open System.Reflection
open System.Text
open System.Threading
open System.Threading.Tasks
open FSharp.Compiler.Diagnostics
open FSharp.Compiler.Interactive.Shell
open FSharp.Compiler.Symbols
open FsxCells.Host.Protocol

/// Stack size of the evaluation thread, so deeply recursive scripts don't overflow the stack.
let evalStackSize = 256 * 1024 * 1024

/// Runs work items one at a time on a dedicated thread with a large stack.
type EvalThread() =
    let queue = new BlockingCollection<unit -> unit>()
    let thread =
        Thread((fun () ->
                    for work in queue.GetConsumingEnumerable() do
                        work ()),
               evalStackSize,
               IsBackground = true,
               Name = "fsx-cells eval")
    do thread.Start()

    /// Queues work without waiting for it.
    member _.Post(f: unit -> unit) = queue.Add(fun () -> try f () with _ -> ())

    member _.Run(f: unit -> 'T) : 'T =
        let tcs = TaskCompletionSource<'T>(TaskCreationOptions.RunContinuationsAsynchronously)
        queue.Add(fun () ->
            try tcs.SetResult(f ())
            with e -> tcs.SetException e)
        tcs.Task.GetAwaiter().GetResult()

    interface IDisposable with
        member _.Dispose() = queue.CompleteAdding()

/// A TextWriter that forwards to whatever Console.Out / Console.Error currently is,
/// so FSI's own output is captured the same way as the evaluated code's output.
type private ForwardingWriter(target: unit -> TextWriter) =
    inherit TextWriter()
    override _.Encoding = Encoding.UTF8
    override _.Write(c: char) = target().Write c
    override _.Write(s: string) = target().Write s
    override _.Write(buffer: char[], index: int, count: int) = target().Write(buffer, index, count)
    override _.WriteLine(s: string) = target().WriteLine s
    override _.Flush() = target().Flush()

let private severityText (s: FSharpDiagnosticSeverity) =
    match s with
    | FSharpDiagnosticSeverity.Error -> "error"
    | FSharpDiagnosticSeverity.Warning -> "warning"
    | FSharpDiagnosticSeverity.Info -> "info"
    | FSharpDiagnosticSeverity.Hidden -> "hidden"

let private toDiagnostic (d: FSharpDiagnostic) : Diagnostic =
    { StartLine = d.StartLine
      StartCol = d.StartColumn
      EndLine = d.EndLine
      EndCol = d.EndColumn
      Severity = severityText d.Severity
      Message = d.Message
      FileName = d.FileName }

let rec private unwrap (e: exn) =
    match e with
    | :? TargetInvocationException as t when not (isNull t.InnerException) -> unwrap t.InnerException
    | :? AggregateException as a when a.InnerExceptions.Count = 1 -> unwrap a.InnerException
    | _ -> e

/// "Type: message" plus the stack frames that come from user code (FSI-generated assemblies or the script).
let formatException (scriptPath: string) (e: exn) =
    let e = unwrap e
    let frames =
        if isNull e.StackTrace then
            []
        else
            e.StackTrace.Split([| '\r'; '\n' |], StringSplitOptions.RemoveEmptyEntries)
            |> Array.filter (fun l -> l.Contains "FSI_" || l.Contains(scriptPath, StringComparison.OrdinalIgnoreCase))
            |> Array.toList
    String.Join("\n", sprintf "%s: %s" (e.GetType().FullName) e.Message :: frames)

let private formatTypeName (v: FsiValue) =
    try
        v.FSharpType.Format(FSharpDisplayContext.Empty.WithShortTypeNames(true))
    with _ ->
        v.ReflectionType.Name

type Session() =
    let evalThread = new EvalThread()

    let mutable scriptPath =
        Path.Combine(Directory.GetCurrentDirectory(), "fsx-cells.fsx")

    /// Why the script's real `fsi` object couldn't be set up; reported once in the next cell's output.
    let mutable fsiProblem: string option = None

    // Created lazily on the eval thread, after `init` has set the current directory:
    // FSI resolves relative `#load` paths against the directory it was created in.
    let fsi =
        lazy (
            // --gui-: no WinForms event loop (the default on Windows); evaluations run on our own thread.
            // --langversion:preview: preview language features are always available.
            let argv =
                [| "fsi.exe"; "--noninteractive"; "--quiet"; "--multiemit"; "--gui-"; "--langversion:preview" |]
            let create config =
                FsiEvaluationSession.Create(
                    config,
                    argv,
                    TextReader.Null, // never let FSI read the protocol's stdin
                    new ForwardingWriter(fun () -> Console.Out),
                    new ForwardingWriter(fun () -> Console.Error),
                    collectible = false)
            // The script's `fsi` object, from the FSharp.Compiler.Interactive.Settings next to the compiler (the
            // SDK's), which FSI also references for scripts. fsi.AddPrinter, fsi.PrintLength and friends then work
            // as in dotnet fsi, and formatting can read them. In its own function so failures are caught below.
            let withFsiObject () =
                let settings = Assembly.LoadFrom(Path.Combine(Sdk.loadedFSharpDir (), "FSharp.Compiler.Interactive.Settings.dll"))
                let fsiObject = settings.GetType("FSharp.Compiler.Interactive.Settings", true).GetProperty("fsi").GetValue(null)
                let config = FsiEvaluationSession.GetDefaultConfiguration(fsiObject, true)
                let session = create config
                // As in `dotnet fsi script.fsx`: the script path is the only argument. (Create sets it to argv.)
                fsiObject.GetType().GetProperty("CommandLineArgs").SetValue(fsiObject, [| scriptPath |])
                Formatting.fsiConfig <- Some config
                session
            try
                withFsiObject ()
            with e ->
                // Still run cells, without `fsi` in scripts.
                let reason = sprintf "%s: %s" (e.GetType().Name) (e.Message.Trim())
                eprintfn "fsx-cells: could not set up the fsi object, continuing without it: %O" e
                fsiProblem <- Some reason
                Formatting.fsiConfig <- None
                create (FsiEvaluationSession.GetDefaultConfiguration()))

    let normalizeNewlines (s: string) = s.Replace("\r\n", "\n")

    member _.ScriptPath = scriptPath

    /// Sets the script whose cells are evaluated and moves the current directory to its folder.
    member _.Init(path: string) =
        let full = Path.GetFullPath path
        scriptPath <- full
        let dir = Path.GetDirectoryName full
        if Directory.Exists dir then
            Directory.SetCurrentDirectory dir
        // Warm up FSI in the background so the first cell runs faster.
        evalThread.Post(fun () -> fsi.Force() |> ignore)

    /// Evaluates code whose first line is line `startLine` (1-based) of the script.
    /// `display` shows the value as an image or raw HTML instead of its usual text.
    member _.Eval(code: string, startLine: int, ?display: Display) : EvalResult =
        let path = scriptPath
        evalThread.Run(fun () ->
            let captured = new StringWriter()
            let oldOut, oldErr = Console.Out, Console.Error
            let sw = Stopwatch.StartNew()
            Console.SetOut captured
            Console.SetError captured
            let result, diagnostics =
                try
                    // Pad with blank lines so line numbers in diagnostics and stack traces are the
                    // script's own. (A `# <line> "<file>"` directive is not applied to some parse errors.)
                    let text = String('\n', max 0 (startLine - 1)) + code
                    fsi.Force().EvalInteractionNonThrowing(text, path)
                finally
                    Console.Out.Flush()
                    Console.SetOut oldOut
                    Console.SetError oldErr
            sw.Stop()
            let diagnostics = diagnostics |> Array.map toDiagnostic |> Array.toList

            let errors = diagnostics |> List.filter (fun d -> d.Severity = "error")
            let errorText () =
                errors
                |> List.map (fun d -> sprintf "(%d,%d): %s" d.StartLine (d.StartCol + 1) d.Message)
                |> fun l -> String.Join("\n", l)

            let ok, valueText, valueHtml, typeName, error =
                match result with
                // Parse errors can come back as a "successful" empty result.
                | Choice1Of2 _ when not errors.IsEmpty -> false, None, None, None, Some(errorText ())
                | Choice1Of2 None -> true, None, None, None, None
                | Choice1Of2(Some v) ->
                    try
                        match display with
                        | Some d ->
                            let output =
                                match d with
                                | Image -> Formatting.imageOutput v.ReflectionValue
                                | Html -> Formatting.htmlOutput v.ReflectionValue
                            match output with
                            | Ok(text, html) -> true, Some text, Some html, Some(formatTypeName v), None
                            | Error message -> false, None, None, Some(formatTypeName v), Some message
                        | None ->
                            match Formatting.formatValue v.ReflectionValue v.ReflectionType with
                            | Some text ->
                                let html = Formatting.formatHtml v.ReflectionValue v.ReflectionType
                                true, Some text, html, Some(formatTypeName v), None
                            | None -> true, None, None, None, None
                    with e ->
                        false, None, None, Some(formatTypeName v), Some("Formatting the value failed: " + formatException path e)
                | Choice2Of2 e ->
                    let message = if errors.IsEmpty then formatException path e else errorText ()
                    false, None, None, None, Some message

            // FSI's own note after a failed interaction; the error is reported separately.
            let stripStopped (s: string) =
                if not ok && s.EndsWith "Stopped due to error\n" then s.Substring(0, s.Length - "Stopped due to error\n".Length) else s

            // Said once, in the first cell that runs after the problem.
            let notice =
                match fsiProblem with
                | Some reason ->
                    fsiProblem <- None
                    sprintf "fsx-cells: `fsi` is not available in this session (%s). fsi.AddPrinter, fsi.PrintLength and fsi.PrintDepth won't work.\n" reason
                | None -> ""

            { Ok = ok
              Stdout = notice + stripStopped (normalizeNewlines (captured.ToString()))
              ValueText = valueText |> Option.map normalizeNewlines
              ValueHtml = valueHtml
              TypeName = typeName
              Error = error
              Diagnostics = diagnostics
              ElapsedMs = sw.ElapsedMilliseconds })

    interface IDisposable with
        member _.Dispose() =
            if fsi.IsValueCreated then (fsi.Value :> IDisposable).Dispose()
            (evalThread :> IDisposable).Dispose()
