/// A persistent F# Interactive session that evaluates cells and captures their output.
module FsxCells.Host.Session

// ControlledExecution is obsolete (it aborts a thread, which can leave shared state half-updated); it is still what
// dotnet fsi uses to interrupt an evaluation, and there is no other way to stop code that doesn't cooperate.
#nowarn "44"

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

/// How long after an interrupt code that is still running is aborted.
let abortDelayMs = 300

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

    /// The thread the work runs on.
    member _.Thread = thread

    /// Queues work without waiting for it.
    member _.Post(f: unit -> unit) = queue.Add(fun () -> try f () with _ -> ())

    /// Queues work; the task completes with its result.
    member _.Start(f: unit -> 'T) : Task<'T> =
        let tcs = TaskCompletionSource<'T>(TaskCreationOptions.RunContinuationsAsynchronously)
        queue.Add(fun () ->
            try tcs.SetResult(f ())
            with e -> tcs.SetException e)
        tcs.Task

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

/// Captures an evaluation's console output. With `onChunk`, what was written is also passed on while the evaluation
/// runs, at most every `intervalMs`, so a long-running cell shows its progress. After `Finish` nothing more is passed
/// on, so the result can be sent after the last chunk.
type StreamingWriter(onChunk: (string -> unit) option, ?intervalMs: int) =
    inherit TextWriter()
    let all = StringBuilder()
    let pending = StringBuilder()
    let gate = obj ()
    let mutable closed = false

    let flushPending () =
        lock gate (fun () ->
            if not closed && pending.Length > 0 then
                let text = pending.ToString().Replace("\r\n", "\n")
                pending.Clear() |> ignore
                onChunk |> Option.iter (fun f -> try f text with _ -> ()))

    let timer =
        onChunk
        |> Option.map (fun _ ->
            let ms = defaultArg intervalMs 100
            new Timer((fun _ -> flushPending ()), null, ms, ms))

    let append (s: string) =
        lock gate (fun () ->
            all.Append s |> ignore
            if not closed && onChunk.IsSome then pending.Append s |> ignore)

    override _.Encoding = Encoding.UTF8
    override _.Write(c: char) = append (string c)
    override _.Write(s: string) = if not (isNull s) then append s
    override _.Write(buffer: char[], index: int, count: int) = append (String(buffer, index, count))

    /// Everything written so far.
    override _.ToString() = lock gate (fun () -> all.ToString())

    /// Stops passing output on; what wasn't passed on yet is only in `ToString`.
    member _.Finish() =
        timer |> Option.iter (fun t -> t.Dispose())
        lock gate (fun () -> closed <- true)

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

    /// Interrupts so far. An evaluation started before an interrupt (still queued, or running) is stopped by it.
    let mutable interrupts = 0
    /// The running evaluation's cancellation (passed to FSI) and abort (for code that doesn't stop) sources.
    let mutable running: (CancellationTokenSource * CancellationTokenSource) option = None
    let interruptGate = obj ()

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

    /// Stops the running evaluation, and the queued ones started before this call; they return `Interrupted`
    /// results. Can be called from any thread. Code that is stopped is aborted where it is (as Ctrl+C in dotnet fsi
    /// does): values it defined are not defined, and a mutable value it was changing may be half-changed.
    member _.Interrupt() =
        lock interruptGate (fun () ->
            interrupts <- interrupts + 1
            running
            |> Option.iter (fun ((cancel, _) as sources) ->
                // FSI stops compiling when asked, but not the code it runs: that is aborted if it is still running
                // shortly after. (Aborting the compiler itself could leave the session in a bad state.)
                cancel.Cancel()
                Task.Delay(abortDelayMs).ContinueWith(fun (_: Task) ->
                    lock interruptGate (fun () ->
                        match running with
                        | Some(_, abort) as current when obj.ReferenceEquals(current.Value, sources) ->
                            // The abort doesn't reach a thread that is waiting (Thread.Sleep, a lock, Task.Wait...), and
                            // an interrupt after it waits for the abort: interrupt first.
                            evalThread.Thread.Interrupt()
                            abort.Cancel()
                        | _ -> ()))
                |> ignore))

    /// Evaluates code whose first line is line `startLine` (1-based) of the script, and waits for the result.
    member this.Eval(code: string, startLine: int, ?display: Display, ?onOutput: string -> unit) : EvalResult =
        this.StartEval(code, startLine, ?display = display, ?onOutput = onOutput).GetAwaiter().GetResult()

    /// Queues an evaluation of code whose first line is line `startLine` (1-based) of the script. Evaluations run
    /// one at a time, in the order they were started. `display` shows the value as an image or raw HTML instead of
    /// its usual text. `onOutput` gets console output while the code runs (from another thread); the result's
    /// Stdout has all of it, and `onOutput` isn't called after the result is ready.
    member _.StartEval(code: string, startLine: int, ?display: Display, ?onOutput: string -> unit) : Task<EvalResult> =
        let path = scriptPath
        let startedAfter = lock interruptGate (fun () -> interrupts)
        evalThread.Start(fun () ->
            // Interrupted before it started: don't run it.
            let sources =
                lock interruptGate (fun () ->
                    if interrupts <> startedAfter then
                        None
                    else
                        let sources = new CancellationTokenSource(), new CancellationTokenSource()
                        running <- Some sources
                        Some sources)
            match sources with
            | None ->
                { Ok = false; Stdout = ""; ValueText = None; ValueHtml = None; TypeName = None
                  Error = Some "Interrupted before it started."; Diagnostics = []; ElapsedMs = 0L; Interrupted = true }
            | Some(cts, abort) ->
                let session = fsi.Force()
                let captured = new StreamingWriter(onOutput)
                let oldOut, oldErr = Console.Out, Console.Error
                let sw = Stopwatch.StartNew()
                Console.SetOut captured
                Console.SetError captured
                let mutable outcome = None
                let result, diagnostics =
                    try
                        try
                            // Pad with blank lines so line numbers in diagnostics and stack traces are the
                            // script's own. (A `# <line> "<file>"` directive is not applied to some parse errors.)
                            let text = String('\n', max 0 (startLine - 1)) + code
                            // Runs on this thread; cancelling `abort` aborts it, wherever it is.
                            System.Runtime.ControlledExecution.Run(
                                Action(fun () -> outcome <- Some(session.EvalInteractionNonThrowing(text, path, cts.Token))),
                                abort.Token)
                        with
                        | :? OperationCanceledException
                        | :? ThreadInterruptedException -> ()
                        match outcome with
                        | Some(r, d) -> r, d
                        | None -> Choice2Of2(OperationCanceledException() :> exn), [||]
                    finally
                        // First, so no interrupt comes after this. One that is still pending (it didn't find the
                        // thread waiting) would hit the next wait, perhaps in a later cell: clear it.
                        let rec stopInterrupts () =
                            try
                                lock interruptGate (fun () -> running <- None)
                                Thread.Sleep 0
                            with :? ThreadInterruptedException ->
                                stopInterrupts ()
                        stopInterrupts ()
                        Console.Out.Flush()
                        Console.SetOut oldOut
                        Console.SetError oldErr
                        captured.Finish()
                sw.Stop()
                // An interrupt that came as the code finished doesn't undo it.
                let interrupted =
                    cts.IsCancellationRequested
                    && (match result with
                        | Choice2Of2 _ -> true
                        | Choice1Of2 _ -> false)
                cts.Dispose()
                abort.Dispose()
                // FSI reports the cancellation as an internal error; an interrupted cell has no diagnostics of its own.
                let diagnostics = if interrupted then [] else diagnostics |> Array.map toDiagnostic |> Array.toList

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
                    | Choice2Of2 _ when interrupted -> false, None, None, None, Some "Interrupted."
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
                  ElapsedMs = sw.ElapsedMilliseconds
                  Interrupted = interrupted })

    interface IDisposable with
        member _.Dispose() =
            if fsi.IsValueCreated then (fsi.Value :> IDisposable).Dispose()
            (evalThread :> IDisposable).Dispose()
