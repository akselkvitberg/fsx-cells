/// Turns an evaluated value into display text for the block under a cell.
module FsxCells.Host.Formatting

open System
open System.Collections
open System.Collections.Concurrent
open System.Collections.Generic
open System.Globalization
open System.Numerics
open System.Reflection
open System.Text
open System.Text.RegularExpressions
open Microsoft.FSharp.Reflection
open FSharp.Compiler.Interactive.Shell

/// Points grids larger than this many cells fall back to %A.
let maxGridCells = 4_000_000
/// Points grids wider or taller than this fall back to %A, so sparse coordinates don't become huge lines.
let maxGridSide = 2_000
/// Point sequences with more items than this fall back to %A (also guards infinite seqs).
let maxPoints = 1_000_000

type private Generic =
    static member FormatA<'T>(x: 'T) : string = sprintf "%120A" x

let private formatAMethod =
    typeof<Generic>.GetMethod("FormatA", Reflection.BindingFlags.Static ||| Reflection.BindingFlags.Public ||| Reflection.BindingFlags.NonPublic)

/// sprintf "%A" at width 120, using the static type so e.g. None prints as "None".
let formatA (value: obj) (ty: Type) : string =
    let ty = if isNull ty then (if isNull value then typeof<obj> else value.GetType()) else ty
    try
        formatAMethod.MakeGenericMethod([| ty |]).Invoke(null, [| value |]) :?> string
    with
    | :? Reflection.TargetInvocationException as e when not (isNull e.InnerException) -> raise e.InnerException

let private joinLines (lines: seq<string>) = String.Join("\n", lines)

let charGrid2D (a: char[,]) =
    let b0, b1 = Array2D.base1 a, Array2D.base2 a
    seq {
        for r in 0 .. Array2D.length1 a - 1 ->
            String(Array.init (Array2D.length2 a) (fun c -> a[b0 + r, b1 + c]))
    }
    |> joinLines

let boolGrid2D (a: bool[,]) =
    let b0, b1 = Array2D.base1 a, Array2D.base2 a
    seq {
        for r in 0 .. Array2D.length1 a - 1 ->
            String(Array.init (Array2D.length2 a) (fun c -> if a[b0 + r, b1 + c] then '#' else '.'))
    }
    |> joinLines

/// Renders cells (x,y) -> char on a grid sized to the bounding box. x is the column, y the row.
let private renderCells (cells: IReadOnlyCollection<struct (int * int * char)>) (empty: char) : string option =
    if cells.Count = 0 then
        None
    else
        let mutable minX, maxX, minY, maxY = Int32.MaxValue, Int32.MinValue, Int32.MaxValue, Int32.MinValue
        for struct (x, y, _) in cells do
            minX <- min minX x
            maxX <- max maxX x
            minY <- min minY y
            maxY <- max maxY y
        let w = int64 maxX - int64 minX + 1L
        let h = int64 maxY - int64 minY + 1L
        if w * h > int64 maxGridCells || w > int64 maxGridSide || h > int64 maxGridSide then
            None
        else
            let grid = Array.init (int h) (fun _ -> Array.create (int w) empty)
            for struct (x, y, c) in cells do
                grid[y - minY][x - minX] <- c
            Some(grid |> Seq.map String |> joinLines)

/// `#` for each (x,y) point on a `.` grid.
let pointGrid (points: seq<int * int>) : string option =
    let cells = List<struct (int * int * char)>()
    use e = points.GetEnumerator()
    let mutable tooMany = false
    while not tooMany && e.MoveNext() do
        let x, y = e.Current
        cells.Add(struct (x, y, '#'))
        if cells.Count > maxPoints then tooMany <- true
    if tooMany then None else renderCells cells '.'

/// Map<(x,y), char> on a grid; missing cells are spaces.
let charMapGrid (m: Map<int * int, char>) : string option =
    let cells = List<struct (int * int * char)>()
    for KeyValue((x, y), c) in m do
        cells.Add(struct (x, y, c))
    renderCells cells ' '

/// True for the static types we treat as point collections.
let private isPointCollectionType (ty: Type) =
    ty = typeof<Set<int * int>>
    || ty = typeof<(int * int) list>
    || ty = typeof<(int * int)[]>
    || ty = typeof<seq<int * int>>
    || ty = typeof<ResizeArray<int * int>>
    || ty = typeof<HashSet<int * int>>

// ---------------------------------------------------------------------------------------------------------------
// Objects, tables and dictionaries, modelled on .NET Interactive's plain-text formatter.

/// Table cells and one-line values are cut to this many characters.
let maxCellWidth = 60
/// Lines in an object tree are cut to this many characters.
let maxLineWidth = 200

/// Stands in for a property whose getter threw; shown as its text.
type private GetterError(message: string) =
    override _.ToString() = "<" + message + ">"

type private Member = { Name: string; Get: obj -> obj }

let private scalarTypes =
    HashSet<Type>(
        [ typeof<string>; typeof<decimal>; typeof<DateTime>; typeof<DateTimeOffset>; typeof<TimeSpan>
          typeof<Guid>; typeof<DateOnly>; typeof<TimeOnly>; typeof<BigInteger>; typeof<Uri> ])

let private isScalarType (t: Type) = t.IsPrimitive || t.IsEnum || scalarTypes.Contains t

let private isNumericType (t: Type) =
    (t.IsPrimitive && t <> typeof<bool> && t <> typeof<char>) || t = typeof<decimal> || t = typeof<BigInteger>

let private overridesToString (t: Type) =
    let m = t.GetMethod("ToString", BindingFlags.Public ||| BindingFlags.Instance, null, Type.EmptyTypes, null)
    not (isNull m) && m.DeclaringType <> typeof<obj> && m.DeclaringType <> typeof<ValueType>

let private isFSharpType (t: Type) =
    FSharpType.IsUnion(t, true)
    || FSharpType.IsTuple t
    || FSharpType.IsExceptionRepresentation(t, true)
    || FSharpType.IsFunction t

let private memberCache = ConcurrentDictionary<Type, Member[]>()

/// Record fields, or the public readable properties and fields of a class, in declaration order.
let private membersOf (t: Type) : Member[] =
    memberCache.GetOrAdd(
        t,
        fun t ->
            let ofProperty (p: PropertyInfo) = { Name = p.Name; Get = fun o -> p.GetValue o }
            if FSharpType.IsRecord(t, true) then
                FSharpType.GetRecordFields(t, true) |> Array.map ofProperty
            else
                let props =
                    t.GetProperties(BindingFlags.Public ||| BindingFlags.Instance)
                    |> Array.filter (fun p ->
                        p.CanRead && not (isNull p.GetMethod) && p.GetMethod.IsPublic && p.GetIndexParameters().Length = 0)
                    |> Array.map (fun p -> p.MetadataToken, ofProperty p)
                let fields =
                    t.GetFields(BindingFlags.Public ||| BindingFlags.Instance)
                    |> Array.map (fun f -> f.MetadataToken, { Name = f.Name; Get = fun o -> f.GetValue o })
                Array.append props fields |> Array.sortBy fst |> Array.map snd)

let private readMember (m: Member) (o: obj) : obj =
    try
        m.Get o
    with e ->
        let e = match e with :? TargetInvocationException as t when not (isNull t.InnerException) -> t.InnerException | e -> e
        box (GetterError(e.GetType().Name + ": " + e.Message))

/// Classes and structs shown by their properties: not scalars, F# unions/tuples, collections, delegates,
/// or types with their own ToString.
let private isPlainObjectType (t: Type) =
    not (isScalarType t)
    && not (FSharpType.IsRecord(t, true))
    && not (isFSharpType t)
    && not (typeof<IEnumerable>.IsAssignableFrom t)
    && not (typeof<Delegate>.IsAssignableFrom t)
    && not t.IsPointer
    && not (overridesToString t)
    && (membersOf t).Length > 0

/// The union type itself for a union case subclass (e.g. Shape+Circle -> Shape).
let rec private unionRoot (t: Type) =
    if not (isNull t.BaseType) && FSharpType.IsUnion(t.BaseType, true) then unionRoot t.BaseType else t

/// Unions whose values make table rows: at least one case has fields. Option, voption and list are not rows,
/// and neither are enum-like unions (`[Red; Green]` reads better as %A).
let private isUnionRowType (t: Type) =
    FSharpType.IsUnion(t, true)
    && not (typeof<IEnumerable>.IsAssignableFrom t)
    && (let root = unionRoot t
        let generic = if root.IsGenericType then root.GetGenericTypeDefinition() else root
        generic <> typedefof<option<_>>
        && generic <> typedefof<voption<_>>
        && FSharpType.GetUnionCases(root, true) |> Array.exists (fun c -> c.GetFields().Length > 0))

/// Types whose values make a table row: records, plain objects and unions with fields.
let private isRowType (t: Type) =
    FSharpType.IsRecord(t, true) || isPlainObjectType t || isUnionRowType t

let private unionCaseCache = ConcurrentDictionary<Type, (obj -> int) * Member[][]>()

/// The columns of a value in a table row. For a union value: "Case", then the fields of its case
/// (named fields by name, unnamed ones as Item, Item1, Item2, ...).
let private rowMembers (v: obj) : Member[] =
    let t = v.GetType()
    if FSharpType.IsUnion(t, true) then
        let readTag, caseMembers =
            unionCaseCache.GetOrAdd(
                unionRoot t,
                fun root ->
                    let cases = FSharpType.GetUnionCases(root, true)
                    let readTag = FSharpValue.PreComputeUnionTagReader(root, true)
                    let members =
                        cases
                        |> Array.map (fun c ->
                            let caseName = { Name = "Case"; Get = fun _ -> box c.Name }
                            let fields =
                                c.GetFields() |> Array.map (fun p -> { Name = p.Name; Get = fun o -> p.GetValue o })
                            Array.append [| caseName |] fields)
                    readTag, members)
        caseMembers[readTag v]
    else
        membersOf t

type private Kind =
    | Null
    | Scalar
    | Record
    | FSharpValue
    | Dictionary
    | Sequence
    | PlainObject
    | Other

let private dictionaryInterface (t: Type) =
    t.GetInterfaces()
    |> Array.exists (fun i ->
        i = typeof<IDictionary>
        || (i.IsGenericType
            && (i.GetGenericTypeDefinition() = typedefof<IDictionary<_, _>>
                || i.GetGenericTypeDefinition() = typedefof<IReadOnlyDictionary<_, _>>)))

let private kindOf (v: obj) =
    match v with
    | null -> Null
    | _ ->
        let t = v.GetType()
        if isScalarType t then Scalar
        elif FSharpType.IsRecord(t, true) then Record
        elif dictionaryInterface t then Dictionary
        elif v :? IEnumerable then Sequence
        elif isFSharpType t then FSharpValue
        elif isPlainObjectType t then PlainObject
        else Other

/// F# names for the F# collection types, as F# writes them.
let private fsharpNames =
    dict [ typedefof<list<_>>, "list"; typedefof<Map<_, _>>, "Map"; typedefof<Set<_>>, "Set"; typedefof<option<_>>, "option" ]

let rec private typeDisplayName (t: Type) =
    if t.IsArray then
        typeDisplayName (t.GetElementType()) + "[]"
    elif t.Name.StartsWith "<>f__AnonymousType" then
        "{| " + String.Join("; ", membersOf t |> Array.map _.Name) + " |}"
    elif t.IsGenericType then
        let def = t.GetGenericTypeDefinition()
        let baseName =
            match fsharpNames.TryGetValue def with
            | true, n -> n
            | _ ->
                let tick = t.Name.IndexOf '`'
                if tick >= 0 then t.Name.Substring(0, tick) else t.Name
        baseName + "<" + String.Join(", ", t.GetGenericArguments() |> Array.map typeDisplayName) + ">"
    else
        t.Name

let private newlines = Regex(@"\s*\r?\n\s*", RegexOptions.Compiled)

/// Puts multi-line text on one line.
let private collapse (s: string) = newlines.Replace(s, " ")

let private truncate (width: int) (s: string) =
    if s.Length <= width then s else s.Substring(0, width - 1) + "…"

// ---------------------------------------------------------------------------------------------------------------
// Settings from the session's `fsi` object, so scripts configure output with plain F# that also runs in dotnet fsi.

/// The running session's configuration, which reads the script's `fsi` object (set by Session; None in tests).
let mutable fsiConfig: FsiEvaluationSessionHostConfig option = None

/// Rows shown before "(N more)" unless the script sets fsi.PrintLength.
let defaultListLimit = 20
/// Levels of nested objects expanded unless the script sets fsi.PrintDepth.
let defaultDepthLimit = 6

// fsi's own defaults; a script that leaves them alone gets ours, which suit tables better.
let private fsiDefaultPrintLength = 100
let private fsiDefaultPrintDepth = 100

/// How many items of a sequence are shown: fsi.PrintLength if the script changed it.
let listLimit () =
    match fsiConfig with
    | Some c when c.PrintLength <> fsiDefaultPrintLength -> max 0 c.PrintLength
    | _ -> defaultListLimit

/// How deep nested objects are expanded: fsi.PrintDepth if the script changed it.
let depthLimit () =
    match fsiConfig with
    | Some c when c.PrintDepth <> fsiDefaultPrintDepth -> max 0 c.PrintDepth
    | _ -> defaultDepthLimit

/// Printers added with fsi.AddPrinter, latest first.
let private printers () =
    match fsiConfig with
    | None -> []
    | Some c ->
        c.AddedPrinters
        |> List.choose (function
            | Choice1Of2(ty, f) -> Some(ty, f)
            | Choice2Of2 _ -> None)

/// The text of an fsi.AddPrinter printer for the value's type: an exact match, else the latest one for a base type
/// or interface.
let private tryCustom (v: obj) =
    if isNull v then
        None
    else
        match printers () with
        | [] -> None
        | ps ->
            let t = v.GetType()
            ps
            |> List.tryFind (fun (ty, _) -> ty = t)
            |> Option.orElse (ps |> List.tryFind (fun (ty, _) -> ty.IsAssignableFrom t))
            |> Option.map (fun (_, f) -> f v)

let private hasCustom (v: obj) = (tryCustom v).IsSome

/// Whether an fsi.AddPrinter printer could apply to values of type `t` (or its subtypes).
let private mayHavePrinter (t: Type) =
    printers () |> List.exists (fun (ty, _) -> ty.IsAssignableFrom t || t.IsAssignableFrom ty)

/// Values that make a table row: records and plain objects without a registered formatter.
let private isRowValue (v: obj) =
    not (isNull v) && isRowType (v.GetType()) && not (hasCustom v)

/// Scalars as text; strings and chars are quoted when they are part of a larger value.
let private scalarText (nested: bool) (v: obj) =
    match v with
    | :? string as s -> if nested then "\"" + s + "\"" else s
    | :? char as c -> if nested then "'" + string c + "'" else string c
    | :? bool as b -> if b then "true" else "false"
    | :? float
    | :? float32 -> formatA v (v.GetType())
    | :? IFormattable as f when isNumericType (v.GetType()) -> f.ToString(null, CultureInfo.InvariantCulture)
    | _ -> string v

/// The count of an in-memory collection, without enumerating it.
let private tryCount (v: obj) =
    match v with
    | :? ICollection as c -> Some c.Count
    | _ ->
        v.GetType().GetInterfaces()
        |> Array.tryFind (fun i -> i.IsGenericType && i.GetGenericTypeDefinition() = typedefof<IReadOnlyCollection<_>>)
        |> Option.map (fun i -> i.GetProperty("Count").GetValue v :?> int)

/// Up to `limit` items, plus the "(N more)" text when there are more.
let private takeItems (limit: int) (v: IEnumerable) : obj[] * string option =
    let items = List<obj>()
    let e = v.GetEnumerator()
    try
        while items.Count <= limit && e.MoveNext() do
            items.Add e.Current
    finally
        match e with
        | :? IDisposable as d -> d.Dispose()
        | _ -> ()
    if items.Count <= limit then
        items.ToArray(), None
    else
        let shown = items.GetRange(0, limit).ToArray()
        let more =
            match tryCount v with
            | Some n -> sprintf "(%d more)" (n - limit)
            | None -> "(more)"
        shown, Some more

let private keyValue (pair: obj) =
    let t = pair.GetType()
    let get name = t.GetProperty(name).GetValue pair
    get "Key", get "Value"

/// A value on one line, for table cells and object properties. `depth` counts the objects it is nested in.
let rec private inlineText (depth: int) (nested: bool) (v: obj) : string =
    match tryCustom v with
    | Some s -> collapse s
    | None ->
        let tooDeep = depth >= depthLimit ()
        match kindOf v with
        | Null -> "<null>"
        | Scalar -> scalarText nested v
        | FSharpValue -> collapse (formatA v (v.GetType()))
        | Record
        | PlainObject when tooDeep -> typeDisplayName (v.GetType())
        | Record
        | PlainObject ->
            let fields =
                membersOf (v.GetType())
                |> Array.map (fun m -> m.Name + " = " + inlineText (depth + 1) true (readMember m v))
            "{ " + String.Join("; ", fields) + " }"
        | Dictionary
        | Sequence when tooDeep -> typeDisplayName (v.GetType())
        | Dictionary ->
            // Only as many items as fit in a cell anyway.
            let items, more = takeItems (min (listLimit ()) 10) (v :?> IEnumerable)
            let texts =
                items
                |> Array.map (fun p ->
                    let k, value = keyValue p
                    inlineText (depth + 1) true k + ": " + inlineText (depth + 1) true value)
            "{ " + String.Join("; ", Array.append texts (Option.toArray more)) + " }"
        | Sequence ->
            let items, more = takeItems (min (listLimit ()) 20) (v :?> IEnumerable)
            let texts = items |> Array.map (inlineText (depth + 1) true)
            "[" + String.Join("; ", Array.append texts (Option.toArray more)) + "]"
        | Other -> collapse (string v)

let private cellText depth (v: obj) =
    truncate maxCellWidth (inlineText depth false v)

let private isNumericValue (v: obj) =
    not (isNull v) && isNumericType (v.GetType()) && (tryCustom v).IsNone

/// Columns separated by │, a ─ line under the header, numbers right-aligned, then the footer.
let private drawTable (headers: string[]) (rows: (string * bool)[][]) (footer: string option) : string list =
    let columns = headers.Length
    let widths =
        Array.init columns (fun c -> rows |> Array.fold (fun w row -> max w (fst row[c]).Length) headers[c].Length)
    let rightAlign =
        Array.init columns (fun c ->
            let filled = rows |> Array.filter (fun row -> fst row[c] <> "")
            filled.Length > 0 && filled |> Array.forall (fun row -> snd row[c]))
    let pad c (s: string) = if rightAlign[c] then s.PadLeft widths[c] else s.PadRight widths[c]
    let line (cells: string[]) = String.Join(" │ ", cells |> Array.mapi pad).TrimEnd()
    [ yield line headers
      yield String.Join("─┼─", widths |> Array.map (fun w -> String('─', w)))
      for row in rows -> line (row |> Array.map fst)
      yield! Option.toList footer ]

/// Column names for a set of row values: the members of each row type, in order of first appearance.
let private columnsOf (values: obj[]) =
    let names = List<string>()
    let seen = HashSet<string>()
    for v in values do
        if isRowValue v then
            for m in rowMembers v do
                if seen.Add m.Name then names.Add m.Name
    names.ToArray()

/// The cells of one row value under `columns`: its member values, or the value itself in a "value" column.
let private rowCells depth (columns: string[]) (hasValueColumn: bool) (v: obj) =
    let isRow = isRowValue v
    let members = if isRow then rowMembers v else [||]
    [| for c in columns do
           match members |> Array.tryFind (fun m -> m.Name = c) with
           | Some m ->
               let x = readMember m v
               yield cellText depth x, isNumericValue x
           | None -> yield "", false
       if hasValueColumn then
           yield (if isRow then "", false else cellText depth v, isNumericValue v) |]

/// A sequence as a table: an index column, then a column per member of the items (or a value column).
let private sequenceTable depth (items: obj[]) (more: string option) =
    let columns = columnsOf items
    let hasValueColumn = columns.Length = 0 || items |> Array.exists (not << isRowValue)
    let headers = [| "#"; yield! columns; if hasValueColumn then "value" |]
    let rows =
        items
        |> Array.mapi (fun i v -> [| string i, true; yield! rowCells (depth + 1) columns hasValueColumn v |])
    drawTable headers rows more

/// A dictionary as a key column plus the value's members (or a value column).
let private dictionaryTable depth (pairs: obj[]) (more: string option) =
    let pairs = pairs |> Array.map keyValue
    let values = pairs |> Array.map snd
    let columns = columnsOf values
    let hasValueColumn = columns.Length = 0 || values |> Array.exists (not << isRowValue)
    let headers = [| "key"; yield! columns; if hasValueColumn then "value" |]
    let rows =
        pairs
        |> Array.map (fun (k, v) ->
            [| cellText (depth + 1) k, isNumericValue k; yield! rowCells (depth + 1) columns hasValueColumn v |])
    drawTable headers rows more

/// The element type of a sequence's static or runtime type, if it is IEnumerable<T>.
let private elementType (ty: Type) (v: obj) =
    let find (t: Type) =
        if isNull t then None
        elif t.IsGenericType && t.GetGenericTypeDefinition() = typedefof<IEnumerable<_>> then Some(t.GetGenericArguments()[0])
        else
            t.GetInterfaces()
            |> Array.tryFind (fun i -> i.IsGenericType && i.GetGenericTypeDefinition() = typedefof<IEnumerable<_>>)
            |> Option.map (fun i -> i.GetGenericArguments()[0])
    find (v.GetType()) |> Option.orElse (find ty)

/// The items to show of a sequence that makes a table: its items are records or objects (or have a registered
/// formatter). Lazy sequences are only enumerated when their element type says they hold rows.
let private tryTableItems (ty: Type) (v: IEnumerable) =
    let elem = elementType ty v
    let rowsByType = elem |> Option.exists (fun t -> isRowType t && not (mayHavePrinter t))
    let inMemory = (tryCount v).IsSome
    let mightHoldRows =
        match elem with
        | None -> true
        | Some t -> t = typeof<obj> || t.IsInterface || t.IsAbstract || mayHavePrinter t
    if rowsByType || (inMemory && mightHoldRows) then
        let items, more = takeItems (listLimit ()) v
        let shouldTable =
            rowsByType
            || items |> Array.exists (fun x -> isRowValue x || hasCustom x)
        if shouldTable then Some(items, more) else None
    else
        None

let private trySequenceTable depth (ty: Type) (v: IEnumerable) =
    tryTableItems ty v |> Option.map (fun (items, more) -> sequenceTable depth items more)

let private indent (lines: string list) = lines |> List.map (fun l -> "  " + l)

/// An object as its type name followed by one indented line (or block) per member.
let rec private objectTree depth (v: obj) : string list =
    typeDisplayName (v.GetType())
    :: [ for m in membersOf (v.GetType()) do
             yield! indent (memberLines (depth + 1) m.Name (readMember m v)) ]

and private memberLines depth (name: string) (v: obj) : string list =
    let single text = [ truncate maxLineWidth (name + ": " + text) ]
    let block (lines: string list) =
        match lines with
        | [ line ] -> single line
        | _ -> (name + ":") :: indent lines
    match tryCustom v with
    | Some s -> block (List.ofArray (s.Replace("\r\n", "\n").Split '\n'))
    | None ->
        let tooDeep = depth >= depthLimit ()
        match kindOf v with
        | Record
        | PlainObject when not tooDeep ->
            match objectTree depth v with
            | header :: members -> (name + ": " + header) :: members
            | [] -> single ""
        | Dictionary when not tooDeep ->
            let pairs, more = takeItems (listLimit ()) (v :?> IEnumerable)
            if pairs.Length = 0 then single "{ }" else block (dictionaryTable depth pairs more)
        | Sequence when not tooDeep ->
            match trySequenceTable depth (v.GetType()) (v :?> IEnumerable) with
            | Some table -> block table
            | None -> single (inlineText depth false v)
        | _ -> single (inlineText depth false v)

/// Returns the display text for a value, or None when there is nothing to show (unit).
/// `ty` is the static type reported by FSI (may be more general than value.GetType()).
let formatValue (value: obj) (ty: Type) : string option =
    let ty = if isNull ty then (if isNull value then typeof<obj> else value.GetType()) else ty
    if ty = typeof<unit> || ty = typeof<Void> then
        None
    else
        let special =
            match value with
            | _ when (tryCustom value).IsSome -> tryCustom value
            | null -> None
            | :? string as s -> Some s
            | :? (char[,]) as a -> Some(charGrid2D a)
            | :? (bool[,]) as a -> Some(boolGrid2D a)
            | :? (char[][]) as a -> Some(a |> Seq.map (fun row -> if isNull row then "" else String(row)) |> joinLines)
            | :? (string[]) as a -> Some(joinLines a)
            | :? (string list) as l -> Some(joinLines l)
            | :? Map<int * int, char> as m -> charMapGrid m
            | :? seq<int * int> as points when isPointCollectionType ty || isPointCollectionType (value.GetType()) ->
                match value with
                // In-memory collections can safely be enumerated again by %A if the grid doesn't fit.
                | :? IReadOnlyCollection<int * int> -> pointGrid points
                | _ ->
                    // A lazy seq: enumerate it once (bounded), so side effects and work happen only once.
                    let items = points |> Seq.truncate (maxPoints + 1) |> Seq.toArray
                    match pointGrid items with
                    | Some grid -> Some grid
                    | None -> Some(formatA (box items) typeof<(int * int)[]>)
            | _ ->
                match kindOf value with
                | Dictionary ->
                    let pairs, more = takeItems (listLimit ()) (value :?> IEnumerable)
                    if pairs.Length = 0 then None else Some(joinLines (dictionaryTable 0 pairs more))
                | Sequence -> trySequenceTable 0 ty (value :?> IEnumerable) |> Option.map joinLines
                | PlainObject -> Some(joinLines (objectTree 0 value))
                | _ -> None
        match special with
        | Some s -> Some s
        | None -> Some(formatA value ty)

// ---------------------------------------------------------------------------------------------------------------
// HTML, like .NET Interactive's default text/html formatter. The plugin wraps it in a page and styles the
// fc-* classes; the markup itself has no colours or fonts.

let private esc (s: string) = Net.WebUtility.HtmlEncode s

/// Values shown as text even when HTML is on: strings, grids and point sets have their own text layouts.
let private hasTextLayout (value: obj) (ty: Type) =
    match value with
    | :? string
    | :? (char[,])
    | :? (bool[,])
    | :? (char[][])
    | :? (string[])
    | :? (string list)
    | :? Map<int * int, char> -> true
    | :? seq<int * int> -> isPointCollectionType ty || isPointCollectionType (value.GetType())
    | _ -> false

let private numClass (v: obj) = if isNumericValue v then " class=\"fc-num\"" else ""

/// A value inside a table cell or object property. Nested objects and tables fold into <details>.
let rec private htmlInline (depth: int) (v: obj) : string =
    match tryCustom v with
    | Some s -> esc s
    | None ->
        let tooDeep = depth >= depthLimit ()
        match kindOf v with
        | Null -> "<span class=\"fc-null\">&lt;null&gt;</span>"
        | Scalar -> esc (scalarText false v)
        | Record
        | PlainObject when not tooDeep -> objectHtml depth false v
        | Dictionary when not tooDeep ->
            match takeItems (listLimit ()) (v :?> IEnumerable) with
            | [||], _ -> "{ }"
            | pairs, more -> folded (collectionSummary v) (dictionaryHtml depth pairs more)
        | Sequence when not tooDeep ->
            match tryTableItems (v.GetType()) (v :?> IEnumerable) with
            | Some(items, more) -> folded (collectionSummary v) (sequenceHtml depth items more)
            | None -> esc (inlineText depth true v)
        | Record
        | PlainObject
        | Dictionary
        | Sequence -> esc (typeDisplayName (v.GetType()))
        | FSharpValue
        | Other -> esc (inlineText depth false v)

/// "list<Person> (3 items)", or without the count for a lazy sequence.
and private collectionSummary (v: obj) =
    let name = typeDisplayName (v.GetType())
    match tryCount v with
    | Some 1 -> name + " (1 item)"
    | Some n -> sprintf "%s (%d items)" name n
    | None -> name

and private folded (summary: string) (body: string) =
    "<details class=\"fc-fold\"><summary>" + esc summary + "</summary>" + body + "</details>"

/// An object as a two-column table of its members, under its type name.
and private objectHtml depth (isOpen: bool) (v: obj) =
    let rows =
        membersOf (v.GetType())
        |> Array.map (fun m ->
            let x = readMember m v
            "<tr><th>" + esc m.Name + "</th><td" + numClass x + ">" + htmlInline (depth + 1) x + "</td></tr>")
    let summary = esc (typeDisplayName (v.GetType()))
    "<details class=\"fc-object\"" + (if isOpen then " open" else "") + "><summary>" + summary + "</summary>"
    + "<table class=\"fc-props\"><tbody>" + String.Concat rows + "</tbody></table></details>"

and private tableHtml depth (firstHeader: string) (keys: obj[]) (isIndex: bool) (values: obj[]) (more: string option) =
    let columns = columnsOf values
    let hasValueColumn = columns.Length = 0 || values |> Array.exists (not << isRowValue)
    let sb = StringBuilder()
    let add (s: string) = sb.Append s |> ignore
    add "<table class=\"fc-table\"><thead><tr><th>"
    add (esc firstHeader)
    add "</th>"
    for c in columns do add ("<th>" + esc c + "</th>")
    if hasValueColumn then add "<th>value</th>"
    add "</tr></thead><tbody>"
    for i in 0 .. values.Length - 1 do
        let v = values[i]
        add "<tr>"
        if isIndex then add ("<td class=\"fc-index\">" + string i + "</td>")
        else add ("<td" + numClass keys[i] + ">" + htmlInline (depth + 1) keys[i] + "</td>")
        let isRow = isRowValue v
        let members = if isRow then rowMembers v else [||]
        for c in columns do
            match members |> Array.tryFind (fun m -> m.Name = c) with
            | Some m ->
                let x = readMember m v
                add ("<td" + numClass x + ">" + htmlInline (depth + 1) x + "</td>")
            | None -> add "<td></td>"
        if hasValueColumn then
            if isRow then add "<td></td>"
            else add ("<td" + numClass v + ">" + htmlInline (depth + 1) v + "</td>")
        add "</tr>"
    add "</tbody></table>"
    more |> Option.iter (fun m -> add ("<div class=\"fc-more\">" + esc m + "</div>"))
    sb.ToString()

and private sequenceHtml depth (items: obj[]) (more: string option) =
    tableHtml depth "#" items true items more

and private dictionaryHtml depth (pairs: obj[]) (more: string option) =
    let pairs = pairs |> Array.map keyValue
    tableHtml depth "key" (Array.map fst pairs) false (Array.map snd pairs) more

/// The HTML for a value, or None when it is better shown as text: it has no table or object layout (plain values,
/// single records and unions, ...), its own text layout (grids, strings), or an fsi.AddPrinter printer.
let formatHtml (value: obj) (ty: Type) : string option =
    let ty = if isNull ty then (if isNull value then typeof<obj> else value.GetType()) else ty
    if isNull value || ty = typeof<unit> || ty = typeof<Void> || (tryCustom value).IsSome || hasTextLayout value ty then
        None
    else
        match kindOf value with
        | Dictionary ->
            match takeItems (listLimit ()) (value :?> IEnumerable) with
            | [||], _ -> None
            | pairs, more -> Some(dictionaryHtml 0 pairs more)
        | Sequence ->
            tryTableItems ty (value :?> IEnumerable) |> Option.map (fun (items, more) -> sequenceHtml 0 items more)
        | PlainObject -> Some(objectHtml 0 true value)
        | _ -> None
