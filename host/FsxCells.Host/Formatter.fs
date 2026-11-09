namespace FsxCells.Interactive

open System
open System.Collections.Generic

/// A formatter registered with Formatter.Register.
type internal RegisteredFormatter =
    { Type: Type
      MimeType: string
      Format: obj -> string
      Order: int }

/// Settings and custom formatters for how values are shown under a cell, modelled on .NET Interactive's
/// `Microsoft.DotNet.Interactive.Formatting.Formatter`. Opened automatically in every fsx-cells session.
[<AbstractClass; Sealed>]
type Formatter private () =
    static let defaultListExpansionLimit = 20
    static let defaultRecursionLimit = 6
    static let registered = List<RegisteredFormatter>()
    static let mutable order = 0
    static let mutable listExpansionLimit = defaultListExpansionLimit
    static let mutable recursionLimit = defaultRecursionLimit

    /// The MIME type values are shown as. Only text/plain is rendered for now.
    static member DefaultMimeType = "text/plain"

    /// How many items of a sequence are shown before "(N more)". Default 20.
    static member ListExpansionLimit
        with get () = listExpansionLimit
        and set (v: int) = listExpansionLimit <- max 0 v

    /// How deep nested objects are expanded before only their type name is shown. Default 6.
    static member RecursionLimit
        with get () = recursionLimit
        and set (v: int) = recursionLimit <- max 0 v

    /// Shows values of type 'T (and subtypes) as the text `format` returns, also inside tables and objects.
    /// Formatters for a MIME type other than text/plain are kept but not used yet.
    static member Register<'T>(format: Func<'T, string>, ?mimeType: string) =
        Formatter.Register(typeof<'T>, Func<obj, string>(fun o -> format.Invoke(unbox<'T> o)), ?mimeType = mimeType)

    /// Like Register<'T>; `ty` may also be an open generic type such as typedefof<list<_>>.
    static member Register(ty: Type, format: Func<obj, string>, ?mimeType: string) =
        if isNull ty then nullArg "ty"
        if isNull format then nullArg "format"
        lock registered (fun () ->
            order <- order + 1
            registered.Add
                { Type = ty
                  MimeType = defaultArg mimeType Formatter.DefaultMimeType
                  Format = format.Invoke
                  Order = order })

    /// Removes registered formatters and restores the default limits.
    static member ResetToDefault() =
        lock registered (fun () -> registered.Clear())
        listExpansionLimit <- defaultListExpansionLimit
        recursionLimit <- defaultRecursionLimit

    static member internal HasRegistrations = registered.Count > 0

    /// The most specific text/plain formatter for a runtime type; the latest registration wins ties.
    static member internal TryFind(ty: Type) : (obj -> string) option =
        if registered.Count = 0 then
            None
        else
            // Distance from ty up its base class chain; interfaces rank just above object.
            let chain =
                ty |> List.unfold (fun t -> if isNull t then None else Some(t, t.BaseType)) |> List.toArray
            let objectDistance = float (chain.Length - 1)
            let score (r: RegisteredFormatter) =
                if r.Type.IsGenericTypeDefinition then
                    chain
                    |> Array.tryFindIndex (fun t -> t.IsGenericType && t.GetGenericTypeDefinition() = r.Type)
                    |> Option.map (fun i -> float i + 0.5)
                    |> Option.orElse (
                        if r.Type.IsInterface
                           && ty.GetInterfaces() |> Array.exists (fun i -> i.IsGenericType && i.GetGenericTypeDefinition() = r.Type)
                        then Some(objectDistance - 0.25)
                        else None)
                else
                    match Array.IndexOf(chain, r.Type) with
                    | -1 when r.Type.IsInterface && r.Type.IsAssignableFrom ty -> Some(objectDistance - 0.5)
                    | -1 -> None
                    | i -> Some(float i)
            lock registered (fun () ->
                registered
                |> Seq.filter (fun r -> r.MimeType = Formatter.DefaultMimeType)
                |> Seq.choose (fun r -> score r |> Option.map (fun s -> s, -r.Order, r))
                |> Seq.sortBy (fun (s, o, _) -> s, o)
                |> Seq.tryHead
                |> Option.map (fun (_, _, r) -> r.Format))
