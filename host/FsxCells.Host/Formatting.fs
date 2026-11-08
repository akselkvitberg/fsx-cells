/// Turns an evaluated value into display text for the block under a cell.
module FsxCells.Host.Formatting

open System
open System.Collections.Generic
open System.Text

/// Points grids larger than this many cells fall back to %A.
let maxGridCells = 4_000_000
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
        if w * h > int64 maxGridCells then
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

/// Returns the display text for a value, or None when there is nothing to show (unit).
/// `ty` is the static type reported by FSI (may be more general than value.GetType()).
let formatValue (value: obj) (ty: Type) : string option =
    let ty = if isNull ty then (if isNull value then typeof<obj> else value.GetType()) else ty
    if ty = typeof<unit> || ty = typeof<Void> then
        None
    else
        let special =
            match value with
            | null -> None
            | :? string as s -> Some s
            | :? (char[,]) as a -> Some(charGrid2D a)
            | :? (bool[,]) as a -> Some(boolGrid2D a)
            | :? (char[][]) as a -> Some(a |> Seq.map (fun row -> if isNull row then "" else String(row)) |> joinLines)
            | :? (string[]) as a -> Some(joinLines a)
            | :? (string list) as l -> Some(joinLines l)
            | :? Map<int * int, char> as m -> charMapGrid m
            | :? seq<int * int> as points when isPointCollectionType ty || isPointCollectionType (value.GetType()) ->
                pointGrid points
            | _ -> None
        match special with
        | Some s -> Some s
        | None -> Some(formatA value ty)
