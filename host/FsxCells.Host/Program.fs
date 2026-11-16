module FsxCells.Host.Program

open System
open System.IO
open System.Text
open FsxCells.Host.Protocol
open FsxCells.Host.Session

/// The protocol loop. Kept out of `main` so nothing here loads FSharp.Core before the SDK resolver is installed.
[<System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)>]
let private run () =
    let utf8 = UTF8Encoding(false)
    // The protocol owns the real stdout. Anything else that writes to Console.Out outside an
    // evaluation (e.g. a background thread started by user code) goes to stderr instead.
    let protocolOut = new StreamWriter(Console.OpenStandardOutput(), utf8, AutoFlush = false)
    let protocolIn = new StreamReader(Console.OpenStandardInput(), utf8)
    let stderr = new StreamWriter(Console.OpenStandardError(), utf8, AutoFlush = true)
    Console.SetOut stderr
    Console.SetError stderr

    // Called from the reader thread, the evaluation thread (output) and result continuations.
    let sendGate = obj ()
    let send (line: string) =
        lock sendGate (fun () ->
            protocolOut.Write line
            protocolOut.Write '\n'
            protocolOut.Flush())

    use session =
        try
            new Session()
        with e ->
            // Goes to the plugin's log and into the message it shows when the host exits.
            eprintfn "fsx-cells host failed to start: %O" e
            reraise ()

    // Requests are read while a cell runs, so `interrupt` takes effect at once; evaluations queue up and run in order.
    let results = Collections.Generic.List<Threading.Tasks.Task>()
    let rec loop () =
        match protocolIn.ReadLine() with
        | null -> ()
        | line when String.IsNullOrWhiteSpace line -> loop ()
        | line ->
            try
                match parseRequest line with
                | Init path -> session.Init path
                | Interrupt -> session.Interrupt()
                | Eval(id, code, startLine, display) ->
                    let evaluation =
                        session.StartEval(code, startLine, ?display = display, onOutput = (fun text -> send (serializeOutput id text)))
                    results.Add(
                        evaluation.ContinueWith(fun (t: Threading.Tasks.Task<EvalResult>) ->
                            if t.IsCompletedSuccessfully then send (serializeEvalResponse id t.Result)
                            else send (serializeProtocolError (Some id) (t.Exception.GetBaseException().Message))))
            with e ->
                send (serializeProtocolError (tryReadId line) e.Message)
            loop ()

    loop ()
    // stdin closed: answer what was asked before exiting.
    Threading.Tasks.Task.WaitAll(results.ToArray())
    0

[<EntryPoint>]
let main _ =
    // First, before any F# library code runs: load the F# compiler and FSharp.Core from the installed SDK.
    // (So only .NET calls here: even `exit` and `printfn` live in FSharp.Core.)
    let mutable sdkReady = true
    try
        Sdk.useFSharpFrom (Sdk.fsharpDir ())
    with e ->
        Console.Error.WriteLine("fsx-cells host: can't use the installed .NET SDK's F#: " + e.Message)
        sdkReady <- false
    if sdkReady then run () else 2
