// FSX Cells showcase: every kind of output, in one file to screenshot.
// Run a cell with Ctrl+Enter, or Shift+Enter to run it and go to the next one.
// It's a plain script too: without the deliberate compile error near the end, `dotnet fsi samples/showcase.fsx` runs
// it all (up to the runtime error in the last cell).

open System
open System.Collections.Generic

// %% A value
let primes = [ 2; 3; 5; 7; 11; 13; 17; 19; 23; 29 ]
primes |> List.sum

// %% Printed output and a value
for p in primes |> List.take 4 do
    printfn "%d squared is %d" p (p * p)
primes |> List.averageBy float

(* cell Strings keep their line breaks *)
[ for i in 1 .. 6 -> String.replicate i "▁▂▃▅▇" ] |> String.concat "\n"

// %% A list of records is a table
type Planet = { Name: string; Moons: int; DistanceAu: float; Rings: bool }

let planets =
    [ { Name = "Mercury"; Moons = 0;   DistanceAu = 0.39; Rings = false }
      { Name = "Venus";   Moons = 0;   DistanceAu = 0.72; Rings = false }
      { Name = "Earth";   Moons = 1;   DistanceAu = 1.00; Rings = false }
      { Name = "Mars";    Moons = 2;   DistanceAu = 1.52; Rings = false }
      { Name = "Jupiter"; Moons = 95;  DistanceAu = 5.20; Rings = true }
      { Name = "Saturn";  Moons = 146; DistanceAu = 9.54; Rings = true } ]

planets

// %% Anonymous records too
planets
|> List.groupBy _.Rings
|> List.map (fun (rings, ps) -> {| Rings = rings; Count = ps.Length; Moons = ps |> List.sumBy _.Moons |})

// %% Union values: a Case column, then each case's fields
type Shape =
    | Circle of radius: float
    | Rectangle of width: float * height: float
    | Triangle of a: float * b: float * c: float
    | Point

[ Circle 1.5; Rectangle(2.0, 3.0); Triangle(3.0, 4.0, 5.0); Point; Circle 0.5 ]

// %% Result is a union as well
let parse (s: string) =
    match Int32.TryParse s with
    | true, n -> Ok n
    | _ -> Error $"'{s}' is not a number"

[ "42"; "7"; "seven"; "-3"; "" ] |> List.map parse

// %% Dictionaries and maps: a key column, then the value's fields
planets |> List.map (fun p -> p.Name, p) |> Map.ofList

// %% A class is shown as its properties, with nested objects and tables you can fold open
type Address(street: string, city: string) =
    member _.Street = street
    member _.City = city

type Team(name: string, founded: DateOnly, office: Address, members: Planet list) =
    member _.Name = name
    member _.Founded = founded
    member _.Office = office
    member _.Members = members
    member _.Tags = Dictionary(dict [ "lang", "F#"; "editor", "Rider" ])

Team("Inner planets", DateOnly(1969, 7, 20), Address("1 Orbit Way", "Houston"), planets |> List.take 4)

// %% Long and endless sequences stop after 20 rows
Seq.initInfinite (fun i -> {| N = i; Square = i * i; Cube = i * i * i |})

// %% fsi.AddPrinter: your own text for a type, also inside tables (and in dotnet fsi)
type Temperature = Celsius of float

fsi.AddPrinter(fun (Celsius c) -> $"%.1f{c} °C")

[ {| City = "Oslo"; Today = Celsius -3.5 |}
  {| City = "Lisbon"; Today = Celsius 17.0 |}
  {| City = "Cairo"; Today = Celsius 26.5 |} ]

// %% Moons per planet [image]
// [image] shows a value as a picture: SVG text, image bytes, or the path of an image file.
let barChart (data: (string * int) list) =
    let w, rowH, labelW = 420, 26, 70
    let maxV = data |> List.map snd |> List.max |> float
    let bars =
        data
        |> List.mapi (fun i (label, v) ->
            let y = i * rowH + 6
            let len = float (w - labelW - 50) * float v / maxV |> int |> max 2
            $"""<text x="{labelW - 8}" y="{y + 15}" text-anchor="end">{label}</text>
                <rect x="{labelW}" y="{y + 2}" width="{len}" height="{rowH - 8}" rx="3" fill="#6b8afd"/>
                <text x="{labelW + len + 6}" y="{y + 15}">{v}</text>""")
        |> String.concat "\n"
    $"""<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{data.Length * rowH + 12}"
             font-family="sans-serif" font-size="13" fill="#888">
            {bars}
        </svg>"""

planets |> List.map (fun p -> p.Name, p.Moons) |> barChart

// %% A report [html]
// [html] shows a string as HTML.
let card (p: Planet) =
    let rings = if p.Rings then "💍 rings" else "no rings"
    let moons = if p.Moons = 1 then "1 moon" else $"{p.Moons} moons"
    $"""<div style="display:inline-block; margin:4px; padding:8px 12px; border-radius:8px; border:1px solid #8884">
            <b>{p.Name}</b><br>
            <small>{p.DistanceAu:F2} AU · {moons} · {rings}</small>
        </div>"""

planets |> List.map card |> String.concat ""

// %% fsi.PrintLength and fsi.PrintDepth set how many rows and how deep nested objects go
fsi.PrintLength <- 3
planets

// %%
fsi.PrintLength <- 20 // back to the default

// %% A compile error is underlined in the script
let total: int = "not a number"

// %% A runtime error points at the line it came from
let moonsPerRing () =
    let withRings = planets |> List.filter _.Rings
    let without = planets |> List.filter (fun p -> not p.Rings)
    (withRings |> List.sumBy _.Moons) / (without |> List.filter (fun p -> p.Moons > 100)).Length

moonsPerRing ()
