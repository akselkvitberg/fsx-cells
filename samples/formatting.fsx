// Tables, objects, `display` and `Formatter`, like Polyglot Notebooks. Tables and objects are HTML when JCEF works.
// `display` and `Formatter` exist only in fsx-cells sessions, so this file doesn't run under plain `dotnet fsi`.

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

// %% display shows values partway through a cell
for p in people do
    printfn "Showing %s" p.Name
    display p
people.Head.Display("application/json")

// %% Long and lazy sequences stop after Formatter.ListExpansionLimit items
seq { for i in 1..1_000_000 -> {| N = i; Square = int64 i * int64 i |} }

// %% Custom formatters apply everywhere, including inside tables
Formatter.Register(fun (p: Person) -> sprintf "%s, %d" p.Name p.Age)
[ Account(people[0], 10m); Account(people[1], 20m) ]

// %% HTML formatters and raw HTML
type Swatch = { Color: string }
Formatter.Register((fun (s: Swatch) -> sprintf "<span style='background:%s'>&nbsp;&nbsp;&nbsp;&nbsp;</span> %s" s.Color s.Color), "text/html")
displayHtml "<b>Swatches</b>"
[ { Color = "tomato" }; { Color = "teal" }; { Color = "gold" } ]

// %% Text only
Formatter.DefaultMimeType <- "text/plain"
people

// %% Back to the defaults
Formatter.ResetToDefault()
people
