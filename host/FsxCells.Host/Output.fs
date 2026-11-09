/// Collects what a cell shows while it runs: printed text and `display`ed values, in order.
module FsxCells.Host.Output

open System.Collections.Generic
open System.IO

/// One piece of a cell's output.
type Segment =
    /// Text the cell printed (stdout and stderr).
    | Printed of text: string
    /// A value passed to `display`: its text, and its HTML when it has an HTML layout.
    | Displayed of text: string * html: string option

/// Captures one evaluation's console output. `display` adds its value as its own segment and also writes the
/// text to the console capture, so the plain `stdout` still holds everything in order.
type Collector() =
    let writer = new StringWriter()
    let segments = List<Segment>()
    let mutable printedUpTo = 0

    let flushPrinted () =
        writer.Flush()
        let all = writer.GetStringBuilder()
        if all.Length > printedUpTo then
            segments.Add(Printed(all.ToString(printedUpTo, all.Length - printedUpTo)))
            printedUpTo <- all.Length

    member _.Writer: TextWriter = writer

    member _.Display(text: string, html: string option) =
        flushPrinted ()
        writer.WriteLine text
        writer.Flush()
        printedUpTo <- writer.GetStringBuilder().Length
        segments.Add(Displayed(text, html))

    /// Everything printed and displayed, as text.
    member _.Text = writer.ToString()

    /// The segments, or an empty list when nothing was displayed (then `Text` says it all).
    member _.Segments() : Segment list =
        if segments |> Seq.exists (function Displayed _ -> true | Printed _ -> false) then
            flushPrinted ()
            List.ofSeq segments
        else
            []

/// The collector of the evaluation that is running, if any. Evaluations run one at a time on one thread.
let mutable current: Collector option = None
