namespace FsxCells.Interactive

open System
open System.Runtime.CompilerServices
open FsxCells.Host

/// `display` and `.Display()`, as in Polyglot Notebooks. Opened automatically in every fsx-cells session.
/// The value is shown in the cell's output, in order with anything printed by the cell.
[<AutoOpen>]
module Display =

    let private show (text: string option) (html: string option) =
        match text with
        | None -> ()
        | Some text ->
            match Output.current with
            | Some collector -> collector.Display(text, html)
            | None -> Console.Out.WriteLine text

    /// Shows a value under the cell now, formatted like a cell's result.
    let display (value: 'T) : unit =
        show (Formatting.formatValue (box value) typeof<'T>) (Formatting.formatHtml (box value) typeof<'T>)

    /// Shows a value as "text/html", "text/plain" or "application/json".
    let displayAs (mimeType: string) (value: 'T) : unit =
        match mimeType with
        | "text/html" ->
            show (Formatting.formatValue (box value) typeof<'T>) (Formatting.formatHtmlWith false (box value) typeof<'T>)
        | _ -> show (Formatting.formatAs mimeType (box value) typeof<'T>) None

    /// Shows HTML markup under the cell, like Polyglot's `HTML(...)`.
    let displayHtml (html: string) : unit = show (Some html) (Some html)

[<Extension; AbstractClass; Sealed>]
type DisplayExtensions =
    /// Shows the value under the cell now, formatted like a cell's result.
    [<Extension>]
    static member Display(value: 'T) : unit = display value

    /// Shows the value as "text/html", "text/plain" or "application/json".
    [<Extension>]
    static member Display(value: 'T, mimeType: string) : unit = displayAs mimeType value

    /// The text the value would be shown as.
    [<Extension>]
    static member ToDisplayString(value: 'T) : string =
        Formatting.formatValue (box value) typeof<'T> |> Option.defaultValue ""

    /// The value as "text/plain", "text/html" (markup, or text when it has no HTML layout) or "application/json".
    [<Extension>]
    static member ToDisplayString(value: 'T, mimeType: string) : string =
        Formatting.formatAs mimeType (box value) typeof<'T> |> Option.defaultValue ""
