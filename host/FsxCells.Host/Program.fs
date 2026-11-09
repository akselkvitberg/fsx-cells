module FsxCells.Host.Program

open System
open System.IO
open System.Text
open FsxCells.Host.Protocol
open FsxCells.Host.Session

[<EntryPoint>]
let main _ =
    let utf8 = UTF8Encoding(false)
    // The protocol owns the real stdout. Anything else that writes to Console.Out outside an
    // evaluation (e.g. a background thread started by user code) goes to stderr instead.
    let protocolOut = new StreamWriter(Console.OpenStandardOutput(), utf8, AutoFlush = false)
    let protocolIn = new StreamReader(Console.OpenStandardInput(), utf8)
    let stderr = new StreamWriter(Console.OpenStandardError(), utf8, AutoFlush = true)
    Console.SetOut stderr
    Console.SetError stderr

    let send (line: string) =
        protocolOut.Write line
        protocolOut.Write '\n'
        protocolOut.Flush()

    use session =
        try
            new Session()
        with e ->
            // Goes to the plugin's log and into the message it shows when the host exits.
            eprintfn "fsx-cells host failed to start: %O" e
            reraise ()

    let rec loop () =
        match protocolIn.ReadLine() with
        | null -> ()
        | line when String.IsNullOrWhiteSpace line -> loop ()
        | line ->
            try
                match parseRequest line with
                | Init path -> session.Init path
                | Eval(id, code, startLine) -> send (serializeEvalResponse id (session.Eval(code, startLine)))
            with e ->
                send (serializeProtocolError (tryReadId line) e.Message)
            loop ()

    loop ()
    0
