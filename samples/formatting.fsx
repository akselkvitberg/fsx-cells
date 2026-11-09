// Tables, objects, `display` and `Formatter`, like Polyglot Notebooks' plain-text output.
// `display` and `Formatter` exist only in fsx-cells sessions, so this file doesn't run under plain `dotnet fsi`.

// %% A list of records is a table
type Person = { Name: string; Age: int; Languages: string list }

let people =
    [ { Name = "Ada"; Age = 36; Languages = [ "Analytical Engine" ] }
      { Name = "Grace"; Age = 85; Languages = [ "COBOL"; "FLOW-MATIC" ] } ]

people

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

// %% Back to the defaults
Formatter.ResetToDefault()
people
