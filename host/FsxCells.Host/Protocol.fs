/// JSON-lines protocol between the Rider plugin and the host.
/// One JSON object per line on stdin (requests) and stdout (responses).
module FsxCells.Host.Protocol

open System
open System.IO
open System.Text
open System.Text.Json

/// How a cell's value is shown instead of its usual text: set by `[image]` / `[html]` on the cell marker.
type Display =
    | Image
    | Html

type Request =
    /// Sets the script path (used for #load, __SOURCE_DIRECTORY__ and diagnostics)
    /// and changes the current directory to the script's folder. No response.
    | Init of scriptPath: string
    /// Evaluates code. startLine is the 1-based line in the script of the first line of code.
    /// id is the raw JSON text of the request's id, echoed back as-is.
    /// display, if set, shows the value as an image or as raw HTML.
    | Eval of id: string * code: string * startLine: int * display: Display option
    /// Stops the running evaluation, and any received before this request that haven't started. Handled as soon as
    /// it is read, even while a cell runs. No response of its own: the interrupted evaluations answer.
    | Interrupt

type Diagnostic =
    { StartLine: int
      StartCol: int
      EndLine: int
      EndCol: int
      Severity: string
      Message: string
      FileName: string }

type EvalResult =
    { Ok: bool
      Stdout: string
      ValueText: string option
      ValueHtml: string option
      TypeName: string option
      Error: string option
      Diagnostics: Diagnostic list
      ElapsedMs: int64
      /// True when the evaluation was stopped by an `interrupt` request.
      Interrupted: bool }

/// Ids are kept as raw JSON (string or number) and echoed back unchanged.
let private readId (el: JsonElement) =
    match el.ValueKind with
    | JsonValueKind.String
    | JsonValueKind.Number -> el.GetRawText()
    | _ -> failwith "'id' must be a string or a number"

let private tryProp (name: string) (root: JsonElement) =
    match root.TryGetProperty name with
    | true, v when v.ValueKind <> JsonValueKind.Null -> Some v
    | _ -> None

let private requireProp name root =
    match tryProp name root with
    | Some v -> v
    | None -> failwithf "missing '%s'" name

/// Tries to read the id from a request line, for error replies to malformed requests.
let tryReadId (line: string) =
    try
        use doc = JsonDocument.Parse line
        tryProp "id" doc.RootElement |> Option.map readId
    with _ -> None

let parseRequest (line: string) : Request =
    use doc = JsonDocument.Parse line
    let root = doc.RootElement
    match (requireProp "type" root).GetString() with
    | "init" -> Init((requireProp "scriptPath" root).GetString())
    | "eval" ->
        let id = readId (requireProp "id" root)
        let code = (requireProp "code" root).GetString()
        let startLine =
            match tryProp "startLine" root with
            | Some v -> v.GetInt32()
            | None -> 1
        let display =
            match tryProp "display" root with
            | None -> None
            | Some v ->
                match v.GetString() with
                | "image" -> Some Image
                | "html" -> Some Html
                | other -> failwithf "unknown display '%s'" other
        Eval(id, code, startLine, display)
    | "interrupt" -> Interrupt
    | other -> failwithf "unknown request type '%s'" other

let private writeLine (write: Utf8JsonWriter -> unit) =
    use ms = new MemoryStream()
    do
        use w = new Utf8JsonWriter(ms, JsonWriterOptions(Encoder = Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping))
        write w
    Encoding.UTF8.GetString(ms.ToArray())

/// id is raw JSON text (as returned in Request.Eval).
let serializeEvalResponse (id: string) (r: EvalResult) : string =
    writeLine (fun w ->
        w.WriteStartObject()
        w.WriteString("type", "result")
        w.WritePropertyName("id")
        w.WriteRawValue(id)
        w.WriteBoolean("ok", r.Ok)
        w.WriteString("stdout", r.Stdout)
        r.ValueText |> Option.iter (fun v -> w.WriteString("valueText", v))
        r.ValueHtml |> Option.iter (fun v -> w.WriteString("valueHtml", v))
        r.TypeName |> Option.iter (fun v -> w.WriteString("typeName", v))
        r.Error |> Option.iter (fun v -> w.WriteString("error", v))
        w.WriteStartArray("diagnostics")
        for d in r.Diagnostics do
            w.WriteStartObject()
            w.WriteNumber("startLine", d.StartLine)
            w.WriteNumber("startCol", d.StartCol)
            w.WriteNumber("endLine", d.EndLine)
            w.WriteNumber("endCol", d.EndCol)
            w.WriteString("severity", d.Severity)
            w.WriteString("message", d.Message)
            w.WriteString("fileName", d.FileName)
            w.WriteEndObject()
        w.WriteEndArray()
        w.WriteNumber("elapsedMs", r.ElapsedMs)
        if r.Interrupted then w.WriteBoolean("interrupted", true)
        w.WriteEndObject())

/// Console output an evaluation wrote while it runs. The result's `stdout` still has all of it.
let serializeOutput (id: string) (text: string) : string =
    writeLine (fun w ->
        w.WriteStartObject()
        w.WriteString("type", "output")
        w.WritePropertyName("id")
        w.WriteRawValue(id)
        w.WriteString("text", text)
        w.WriteEndObject())

/// Reply to a request the host could not understand.
let serializeProtocolError (id: string option) (message: string) : string =
    writeLine (fun w ->
        w.WriteStartObject()
        w.WriteString("type", "protocolError")
        id |> Option.iter (fun v -> w.WritePropertyName("id"); w.WriteRawValue(v))
        w.WriteBoolean("ok", false)
        w.WriteString("error", message)
        w.WriteEndObject())
