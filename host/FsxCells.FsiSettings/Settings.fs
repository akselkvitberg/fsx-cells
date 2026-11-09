// The F# Interactive settings object (`fsi`), with the same API as FSharp.Compiler.Interactive.Settings from the
// F# compiler (dotnet/fsharp, src/FSharp.Compiler.Interactive.Settings, MIT license). FCS reads it by reflection:
// GetDefaultConfiguration(fsi, useFsiAuxLib = true) reads its properties, and adds this assembly as a reference to
// every script so they can use `fsi`.
namespace FSharp.Compiler.Interactive

open System
open System.Globalization

/// An event loop used by F# Interactive (for GUI scripts in dotnet fsi).
type IEventLoop =
    /// Runs the loop; returns true to be restarted.
    abstract Run: unit -> bool
    /// Runs a function on the loop's thread.
    abstract Invoke: (unit -> 'T) -> 'T
    /// Asks the loop to restart.
    abstract ScheduleRestart: unit -> unit

/// fsx-cells runs evaluations on its own thread and has no GUI loop, so work runs where it is invoked.
[<Sealed>]
type internal ImmediateEventLoop() =
    interface IEventLoop with
        member _.Run() = false
        member _.Invoke(f) = f ()
        member _.ScheduleRestart() = ()

/// The settings of an F# Interactive session: the type of `fsi`.
[<Sealed>]
type InteractiveSession() =
    let mutable eventLoop = ImmediateEventLoop() :> IEventLoop
    let mutable showIDictionary = true
    let mutable showDeclarationValues = true
    let mutable args = Environment.GetCommandLineArgs()
    let mutable floatingPointFormat = "g10"
    let mutable formatProvider = CultureInfo.InvariantCulture :> IFormatProvider
    let mutable printWidth = 78
    let mutable printDepth = 100
    let mutable printLength = 100
    let mutable printSize = 10000
    let mutable showIEnumerable = true
    let mutable showProperties = true
    let mutable addedPrinters: Choice<Type * (obj -> string), Type * (obj -> obj)> list = []

    /// The format used for floating point numbers, e.g. "g10".
    member _.FloatingPointFormat
        with get () = floatingPointFormat
        and set v = floatingPointFormat <- v

    /// The format provider used for values.
    member _.FormatProvider
        with get () = formatProvider
        and set v = formatProvider <- v

    /// The width of printed output.
    member _.PrintWidth
        with get () = printWidth
        and set v = printWidth <- v

    /// How deep nested values are printed.
    member _.PrintDepth
        with get () = printDepth
        and set v = printDepth <- v

    /// How many items of a collection are printed.
    member _.PrintLength
        with get () = printLength
        and set v = printLength <- v

    /// The total amount of output printed for a value.
    member _.PrintSize
        with get () = printSize
        and set v = printSize <- v

    /// Whether declared values are printed.
    member _.ShowDeclarationValues
        with get () = showDeclarationValues
        and set v = showDeclarationValues <- v

    /// Whether properties of objects are printed.
    member _.ShowProperties
        with get () = showProperties
        and set v = showProperties <- v

    /// Whether sequences are printed.
    member _.ShowIEnumerable
        with get () = showIEnumerable
        and set v = showIEnumerable <- v

    member internal _.ShowIDictionary
        with get () = showIDictionary
        and set v = showIDictionary <- v

    /// Printers and print transformers, latest first. Read by F# Interactive and by fsx-cells.
    member internal _.AddedPrinters
        with get () = addedPrinters
        and set v = addedPrinters <- v

    /// The command line arguments of the script; the first is the script path.
    member _.CommandLineArgs
        with get () = args
        and set v = args <- v

    /// Shows values of type 'T as the text the printer returns.
    member _.AddPrinter(printer: 'T -> string) =
        addedPrinters <- Choice1Of2(typeof<'T>, (fun (x: obj) -> printer (unbox x))) :: addedPrinters

    /// Shows values of type 'T as the value the transformer returns (null for no change).
    member _.AddPrintTransformer(printer: 'T -> obj) =
        addedPrinters <- Choice2Of2(typeof<'T>, (fun (x: obj) -> printer (unbox x))) :: addedPrinters

    /// The event loop F# Interactive runs code on.
    member _.EventLoop
        with get () = eventLoop
        and set (x: IEventLoop) =
            eventLoop.ScheduleRestart()
            eventLoop <- x

/// Holds the `fsi` object. Opened automatically wherever this assembly is referenced.
[<AutoOpen>]
module Settings =
    /// The settings of the current F# Interactive session.
    let fsi = InteractiveSession()

[<assembly: AutoOpen("FSharp.Compiler.Interactive.Settings")>]
do ()
