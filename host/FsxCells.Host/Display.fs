namespace FsxCells.Interactive

open System
open System.Runtime.CompilerServices
open FsxCells.Host

/// `display` and `.Display()`, as in Polyglot Notebooks. Opened automatically in every fsx-cells session.
/// The value is written to the cell's output, in order with anything printed by the cell.
[<AutoOpen>]
module Display =

    let private write (text: string option) =
        text |> Option.iter (fun t -> Console.Out.WriteLine t)

    /// Shows a value under the cell now, formatted like a cell's result.
    let display (value: 'T) : unit =
        write (Formatting.formatValue (box value) typeof<'T>)

    /// Shows a value as "text/plain" (the default) or "application/json".
    let displayAs (mimeType: string) (value: 'T) : unit =
        write (Formatting.formatAs mimeType (box value) typeof<'T>)

[<Extension; AbstractClass; Sealed>]
type DisplayExtensions =
    /// Shows the value under the cell now, formatted like a cell's result.
    [<Extension>]
    static member Display(value: 'T) : unit = display value

    /// Shows the value as "text/plain" or "application/json".
    [<Extension>]
    static member Display(value: 'T, mimeType: string) : unit = displayAs mimeType value

    /// The text the value would be shown as.
    [<Extension>]
    static member ToDisplayString(value: 'T) : string =
        Formatting.formatValue (box value) typeof<'T> |> Option.defaultValue ""

    /// The text the value would be shown as for "text/plain" or "application/json".
    [<Extension>]
    static member ToDisplayString(value: 'T, mimeType: string) : string =
        Formatting.formatAs mimeType (box value) typeof<'T> |> Option.defaultValue ""
