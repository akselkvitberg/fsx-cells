module FsxCells.Host.Tests

open System
open System.IO
open Xunit
open FsxCells.Host
open FsxCells.Host.Protocol
open FsxCells.Host.Session

// Evaluations redirect Console.Out and change the current directory, which are process-wide.
[<assembly: CollectionBehavior(DisableTestParallelization = true)>]
do ()

let fmt (v: 'T) = Formatting.formatValue (box v) typeof<'T>

module FormattingTests =

    [<Fact>]
    let ``string is raw with real newlines`` () =
        Assert.Equal(Some "a \"b\"\nc", fmt "a \"b\"\nc")

    [<Fact>]
    let ``unit has no value`` () = Assert.Equal(None, fmt ())

    [<Fact>]
    let ``char 2D array is a grid`` () =
        let a = array2D [ [ 'a'; 'b'; 'c' ]; [ 'd'; 'e'; 'f' ] ]
        Assert.Equal(Some "abc\ndef", fmt a)

    [<Fact>]
    let ``jagged char array is a grid`` () =
        Assert.Equal(Some "ab\ncd", fmt [| "ab".ToCharArray(); "cd".ToCharArray() |])

    [<Fact>]
    let ``string array and list are lines`` () =
        Assert.Equal(Some "x\ny", fmt [| "x"; "y" |])
        Assert.Equal(Some "x\ny", fmt [ "x"; "y" ])

    [<Fact>]
    let ``bool 2D array is hash and dot`` () =
        Assert.Equal(Some "#.\n.#", fmt (array2D [ [ true; false ]; [ false; true ] ]))

    [<Fact>]
    let ``set of points is a bounding box grid with x as column`` () =
        let expected = Some "#..\n..#"
        Assert.Equal(expected, fmt (set [ (0, 0); (2, 1) ]))
        Assert.Equal(expected, fmt [ (10, 5); (12, 6) ])
        Assert.Equal(expected, fmt [| (-1, -1); (1, 0) |])
        Assert.Equal(expected, fmt (Seq.ofList [ (0, 0); (2, 1) ]))

    [<Fact>]
    let ``sparse points that would make a huge grid fall back to %A`` () =
        let text = fmt (set [ (0, 0); (3_000_000, 0) ])
        Assert.Equal(Some "set [(0, 0); (3000000, 0)]", text)

    [<Fact>]
    let ``a lazy seq of points is enumerated once`` () =
        let mutable count = 0
        let points = seq { for i in 0 .. 2 do count <- count + 1; yield (i * 5000, 0) }  // too wide for a grid
        let text = fmt points
        Assert.Equal(3, count)
        Assert.True(text.IsSome && text.Value.Contains "10000")

    [<Fact>]
    let ``empty set of points falls back to %A`` () =
        Assert.Equal(Some "set []", fmt (Set.empty<int * int>))

    [<Fact>]
    let ``map of points to chars is a grid with spaces for gaps`` () =
        let m = Map [ (0, 0), 'S'; (2, 0), 'E'; (1, 1), '#' ]
        Assert.Equal(Some "S E\n # ", fmt m)

    [<Fact>]
    let ``other values use %A`` () =
        Assert.Equal(Some "Some 3", fmt (Some 3))
        Assert.Equal(Some "None", fmt (None: int option))
        Assert.Equal(Some "[1; 2; 3]", fmt [ 1; 2; 3 ])
        Assert.Equal(Some "42", fmt 42)

    [<Fact>]
    let ``%A wraps at width 120`` () =
        let text = (fmt [ 1..100 ]).Value
        let lines = text.Split '\n'
        Assert.True(lines.Length > 1)
        Assert.All(lines, fun l -> Assert.True(l.Length <= 120, l))

type Person = { Name: string; Age: int }

type Account(owner: string, balance: decimal) =
    member _.Owner = owner
    member _.Balance = balance

type Node(name: string) =
    member _.Name = name
    member val Next: Node = Unchecked.defaultof<_> with get, set

type Card =
    | A of int
    | B of string

type Shape =
    | Circle of radius: float
    | Rect of width: float * height: float
    | Empty

type Color =
    | Red
    | Green

type Throws() =
    member _.Ok = 1
    member _.Bad: int = failwith "nope"

let lines (text: string option) = text.Value.Split '\n' |> Array.toList

module ObjectFormattingTests =
    let people = [ { Name = "Ada"; Age = 36 }; { Name = "Grace"; Age = 145 } ]

    [<Fact>]
    let ``a list of records is a table with numbers right-aligned`` () =
        Assert.Equal<string list>(
            [ "# │ Name  │ Age"
              "──┼───────┼────"
              "0 │ Ada   │  36"
              "1 │ Grace │ 145" ],
            lines (fmt people))

    [<Fact>]
    let ``a single record keeps %A`` () =
        Assert.Equal(Some "{ Name = \"Ada\"\n  Age = 36 }", fmt people.Head)

    [<Fact>]
    let ``a class is its type name and properties`` () =
        Assert.Equal<string list>([ "Account"; "  Owner: Ada"; "  Balance: 10.5" ], lines (fmt (Account("Ada", 10.5m))))

    [<Fact>]
    let ``a list of classes and of anonymous records are tables`` () =
        Assert.Equal<string list>(
            [ "# │ Owner │ Balance"; "──┼───────┼────────"; "0 │ Ada   │       1" ],
            lines (fmt [ Account("Ada", 1m) ]))
        Assert.Equal<string list>(
            [ "# │ Id │ Ok"; "──┼────┼─────"; "0 │  1 │ true" ],
            lines (fmt [| {| Id = 1; Ok = true |} |]))

    [<Fact>]
    let ``nested objects stop at the depth limit`` () =
        let a, b = Node "a", Node "b"
        a.Next <- b
        b.Next <- a
        let text = lines (fmt a)
        // Six levels of Node, then just the type name.
        Assert.Equal(1 + 2 * Formatting.defaultDepthLimit, text.Length)
        Assert.Equal(String(' ', 2 * Formatting.defaultDepthLimit) + "Next: Node", List.last text)

    [<Fact>]
    let ``a dictionary is a key value table`` () =
        Assert.Equal<string list>(
            [ "key │ value"; "────┼──────"; "a   │     1"; "bb  │    22" ],
            lines (fmt (dict [ "a", 1; "bb", 22 ])))
        Assert.Equal<string list>(
            [ "key │ Name  │ Age"; "────┼───────┼────"; "x   │ Ada   │  36"; "y   │ Grace │ 145" ],
            lines (fmt (Map [ "x", people[0]; "y", people[1] ])))

    [<Fact>]
    let ``long sequences stop at the list limit`` () =
        let text = lines (fmt [ for i in 1..30 -> { Name = string i; Age = i } ])
        Assert.Equal(2 + Formatting.defaultListLimit + 1, text.Length)
        Assert.Equal("(10 more)", List.last text)

    [<Fact>]
    let ``a lazy sequence of records is enumerated only as far as shown`` () =
        let mutable count = 0
        let items = seq { for i in 1..1000 do count <- count + 1; yield { Name = "x"; Age = i } }
        let text = lines (fmt items)
        Assert.Equal("(more)", List.last text)
        Assert.Equal(21, count)

    [<Fact>]
    let ``sequences of plain values keep %A`` () =
        Assert.Equal(Some "[1; 2; 3]", fmt [ 1; 2; 3 ])
        Assert.Equal(Some "[(1, \"a\")]", fmt [ (1, "a") ])

    [<Fact>]
    let ``a list of unions is a table with the case and its fields`` () =
        Assert.Equal<string list>(
            [ "# │ Case │ Item"; "──┼──────┼──────"; "0 │ A    │ 1"; "1 │ B    │ hello" ],
            lines (fmt [ A 1; B "hello" ]))
        Assert.Equal<string list>(
            [ "# │ Case   │ radius │ width │ height"
              "──┼────────┼────────┼───────┼───────"
              "0 │ Circle │    1.5 │       │"
              "1 │ Rect   │        │   2.0 │    3.0"
              "2 │ Empty  │        │       │" ],
            lines (fmt [| Circle 1.5; Rect(2.0, 3.0); Empty |]))

    [<Fact>]
    let ``single unions, options and enum-like unions keep %A`` () =
        Assert.Equal(Some "A 1", fmt (A 1))
        Assert.Equal(Some "[Some 1; None]", fmt [ Some 1; None ])
        Assert.Equal(Some "[Red; Green]", fmt [ Red; Green ])

    [<Fact>]
    let ``a list of results is a table`` () =
        Assert.Equal<string list>(
            [ "# │ Case  │ ResultValue │ ErrorValue"
              "──┼───────┼─────────────┼───────────"
              "0 │ Ok    │           1 │"
              "1 │ Error │             │ x" ],
            lines (fmt [ Ok 1; Error "x" ]))

    [<Fact>]
    let ``unions in html tables`` () =
        Assert.Contains("<th>Case</th><th>Item</th>", (Formatting.formatHtml (box [ A 1; B "x" ]) typeof<Card list>).Value)

    [<Fact>]
    let ``mixed items get a value column`` () =
        Assert.Equal<string list>(
            [ "# │ Name │ Age │ value"; "──┼──────┼─────┼───────"; "0 │      │     │ 1"; "1 │ Ada  │  36 │"; "2 │      │     │ <null>" ],
            lines (fmt [ box 1; box people.Head; null ]))

    [<Fact>]
    let ``a property that throws shows the error`` () =
        Assert.Equal<string list>([ "Throws"; "  Ok: 1"; "  Bad: <Exception: nope>" ], lines (fmt (Throws())))

    let html (v: 'T) = Formatting.formatHtml (box v) typeof<'T>

    [<Fact>]
    let ``html for a list of records is a table`` () =
        Assert.Equal(
            Some(
                "<table class=\"fc-table\"><thead><tr><th>#</th><th>Name</th><th>Age</th></tr></thead><tbody>"
                + "<tr><td class=\"fc-index\">0</td><td>Ada</td><td class=\"fc-num\">36</td></tr>"
                + "<tr><td class=\"fc-index\">1</td><td>Grace</td><td class=\"fc-num\">145</td></tr></tbody></table>"),
            html people)

    [<Fact>]
    let ``html escapes text and folds nested objects`` () =
        let h = (html [ Account("<b>", 1m) ]).Value
        Assert.Contains("&lt;b&gt;", h)
        let a = (html (Account("a", 1m))).Value
        Assert.StartsWith("<details class=\"fc-object\" open><summary>Account</summary><table class=\"fc-props\">", a)
        Assert.Contains("<tr><th>Owner</th><td>a</td></tr>", a)

    [<Fact>]
    let ``no html for values with a text layout or none of their own`` () =
        Assert.Equal(None, html "text")
        Assert.Equal(None, html [ 1; 2 ])
        Assert.Equal(None, html people.Head)
        Assert.Equal(None, html (set [ (0, 0) ]))
        Assert.Equal(None, html (array2D [ [ 'a' ] ]))

module ProtocolTests =

    [<Fact>]
    let ``parses init and eval`` () =
        Assert.Equal(Init @"C:\a\b.fsx", parseRequest """{"type":"init","scriptPath":"C:\\a\\b.fsx"}""")
        Assert.Equal(Eval("7", "1+1", 3), parseRequest """{"type":"eval","id":7,"code":"1+1","startLine":3}""")
        Assert.Equal(Eval("\"x\"", "1", 1), parseRequest """{"type":"eval","id":"x","code":"1"}""")

    [<Fact>]
    let ``serializes eval response and echoes the id`` () =
        let r =
            { Ok = true
              Stdout = "hi\n"
              ValueText = Some "2"
              ValueHtml = None
              TypeName = Some "int"
              Error = None
              Diagnostics = []
              ElapsedMs = 5L }
        Assert.Equal(
            """{"type":"result","id":7,"ok":true,"stdout":"hi\n","valueText":"2","typeName":"int","diagnostics":[],"elapsedMs":5}""",
            serializeEvalResponse "7" r)

module ProtocolHtmlTests =

    [<Fact>]
    let ``serializes valueHtml`` () =
        let r =
            { Ok = true
              Stdout = ""
              ValueText = Some "v"
              ValueHtml = Some "<b>v</b>"
              TypeName = Some "X"
              Error = None
              Diagnostics = []
              ElapsedMs = 1L }
        Assert.Equal(
            """{"type":"result","id":1,"ok":true,"stdout":"","valueText":"v","valueHtml":"<b>v</b>","typeName":"X","diagnostics":[],"elapsedMs":1}""",
            serializeEvalResponse "1" r)

/// A temp folder layout like the Advent of Code repo: <root>/utils.fsx and <root>/2025/Day01.fsx.
let makeScriptDir () =
    let root = Path.Combine(Path.GetTempPath(), "fsx-cells-tests", Guid.NewGuid().ToString("N"))
    let year = Path.Combine(root, "2025")
    Directory.CreateDirectory year |> ignore
    File.WriteAllText(
        Path.Combine(root, "utils.fsx"),
        "module Utils\nlet year () = System.IO.DirectoryInfo(System.IO.Directory.GetCurrentDirectory()).Name\nlet double x = x * 2\n")
    Path.Combine(year, "Day01.fsx")

type SessionTests() =
    let originalDir = Directory.GetCurrentDirectory()
    let scriptPath = makeScriptDir ()
    let session = new Session()
    do session.Init scriptPath

    let eval code line = session.Eval(code, line)

    interface IDisposable with
        member _.Dispose() =
            (session :> IDisposable).Dispose()
            Directory.SetCurrentDirectory originalDir

    [<Fact>]
    member _.``value defined in one eval is used in the next``() =
        let r1 = eval "let x = 20" 1
        Assert.True(r1.Ok, string r1.Error)
        Assert.Equal(None, r1.ValueText)
        let r2 = eval "x + 1" 3
        Assert.True(r2.Ok, string r2.Error)
        Assert.Equal(Some "21", r2.ValueText)
        Assert.Equal(Some "int", r2.TypeName)

    [<Fact>]
    member _.``compile error diagnostics carry script line numbers``() =
        let r = eval "let a = 1\n\nlet b: string = a" 20
        Assert.False r.Ok
        let d = r.Diagnostics |> List.find (fun d -> d.Severity = "error")
        Assert.Equal(22, d.StartLine)
        Assert.Equal(16, d.StartCol)
        Assert.Equal(scriptPath, d.FileName)
        Assert.Contains("(22,17)", r.Error.Value)

    [<Fact>]
    member _.``parse error on the first line carries the script line number``() =
        let r = eval "1 : int" 10
        Assert.False r.Ok
        Assert.All(r.Diagnostics, fun d -> Assert.Equal(10, d.StartLine))

    [<Fact>]
    member _.``load with relative path and current directory are the script's``() =
        let r = eval "#load \"../utils.fsx\"\nopen Utils\nyear (), double 21, __SOURCE_DIRECTORY__" 1
        Assert.True(r.Ok, string r.Error)
        let dir = Path.GetDirectoryName scriptPath
        Assert.Equal(Some(sprintf "(\"2025\", 42, %A)" dir), r.ValueText)

    [<Fact>]
    member _.``stdout and stderr are captured``() =
        let r = eval "printfn \"hello\"\neprintfn \"oops\"\nprintf \"x=%d\" 3" 1
        Assert.True(r.Ok, string r.Error)
        Assert.Equal("hello\noops\nx=3", r.Stdout)
        Assert.Equal(None, r.ValueText)

    [<Fact>]
    member _.``runtime exception is reported as error with script line``() =
        let r = eval "printfn \"before\"\n(failwith \"boom\" : int)" 5
        Assert.False r.Ok
        Assert.Equal("before\n", r.Stdout)
        Assert.StartsWith("System.Exception: boom", r.Error.Value)
        Assert.Contains("Day01.fsx:line 6", r.Error.Value)

    [<Fact>]
    member _.``deep recursion runs on the big stack``() =
        let r = eval "let rec depth n = if n = 0 then 0 else 1 + depth (n - 1)\ndepth 1_000_000" 1
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(Some "1000000", r.ValueText)

    [<Fact>]
    member _.``grid value from a cell is formatted``() =
        let r = eval "array2D [ \"#.\"; \".#\" ]" 1
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(Some "#.\n.#", r.ValueText)
        Assert.Equal(Some "char array2d", r.TypeName)

    [<Fact>]
    member _.``a class from the script is shown by its properties``() =
        let r = eval "type Acc(o: string) =\n    member _.Owner = o\nAcc \"Ada\"" 1
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(Some "Acc\n  Owner: Ada", r.ValueText)

    [<Fact>]
    member _.``fsi works as in dotnet fsi``() =
        let r = eval "fsi.CommandLineArgs" 1
        Assert.True(r.Ok, string r.Error)
        // A string[] is shown one item per line.
        Assert.Equal(Some scriptPath, r.ValueText)

    [<Fact>]
    member _.``fsi.AddPrinter is used at the top, in tables and not as html``() =
        let r =
            eval "type Sw = { C: string }\ntype Row = { N: int; S: Sw }\nfsi.AddPrinter(fun (s: Sw) -> \"#\" + s.C)\n[ { N = 1; S = { C = \"red\" } } ]" 1
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(Some "# │ N │ S\n──┼───┼─────\n0 │ 1 │ #red", r.ValueText)
        Assert.Contains("<td>#red</td>", r.ValueHtml.Value)
        let top = eval "{ C = \"blue\" }" 10
        Assert.Equal(Some "#blue", top.ValueText)
        Assert.Equal(None, top.ValueHtml)

    [<Fact>]
    member _.``fsi.PrintLength and fsi.PrintDepth set the limits``() =
        try
            let r = eval "fsi.PrintLength <- 2\n[ for i in 1 .. 5 -> {| N = i |} ]" 1
            Assert.True(r.Ok, string r.Error)
            Assert.Equal(Some "# │ N\n──┼──\n0 │ 1\n1 │ 2\n(3 more)", r.ValueText)
            let d = eval "type Nd() =\n    member val Next: Nd = Unchecked.defaultof<_> with get, set\nlet n = Nd()\nn.Next <- n\nfsi.PrintDepth <- 1\nn" 10
            Assert.Equal(Some "Nd\n  Next: Nd", d.ValueText)
        finally
            eval "fsi.PrintLength <- 100\nfsi.PrintDepth <- 100" 1 |> ignore
