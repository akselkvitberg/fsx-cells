// Tables and objects, like Polyglot Notebooks. In fsx-cells they are drawn as HTML (or text without JCEF).
// Everything here is plain F#: `dotnet fsi samples/formatting.fsx` runs it too, printing with %A.

// %% A list of records is a table
type Person = { Name: string; Age: int; Languages: string list }

let people =
    [ { Name = "Ada"; Age = 36; Languages = [ "Analytical Engine" ] }
      { Name = "Grace"; Age = 85; Languages = [ "COBOL"; "FLOW-MATIC" ] } ]

people

// %% So is a list of union values: the case, then its fields
type Shape =
    | Circle of radius: float
    | Rect of width: float * height: float
    | Empty

[ Circle 1.5; Rect(2.0, 3.0); Empty ]

// %% A class is shown by its properties
type Account(owner: Person, balance: decimal) =
    member _.Owner = owner
    member _.Balance = balance
    member _.Statements = [ for m in 1..3 -> {| Month = m; Total = balance * decimal m |} ]

Account(people[1], 1200m)

// %% Dictionaries are key/value tables
Map [ "ada", people[0]; "grace", people[1] ]

// %% Long and lazy sequences stop after 20 rows, or fsi.PrintLength if you set it
seq { for i in 1..1_000_000 -> {| N = i; Square = int64 i * int64 i |} }

// %% fsi.AddPrinter sets how a type is shown, also inside tables (and in dotnet fsi)
type Swatch = { Color: string }
fsi.AddPrinter(fun (s: Swatch) -> sprintf "■ %s" s.Color)

[ {| Name = "Sky"; Swatch = { Color = "blue" } |}; {| Name = "Sun"; Swatch = { Color = "gold" } |} ]

// %% fsi.PrintLength and fsi.PrintDepth set how many rows and how deep nested objects go
fsi.PrintLength <- 3
[ for i in 1..10 -> {| N = i |} ]
