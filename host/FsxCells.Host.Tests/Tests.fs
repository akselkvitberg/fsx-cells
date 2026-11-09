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

/// Runs f with a clean Formatter and resets it afterwards.
let withFormatter f =
    FsxCells.Interactive.Formatter.ResetToDefault()
    try f () finally FsxCells.Interactive.Formatter.ResetToDefault()

module ObjectFormattingTests =
    open FsxCells.Interactive

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
    let ``nested objects stop at the recursion limit`` () =
        withFormatter (fun () ->
            let a, b = Node "a", Node "b"
            a.Next <- b
            b.Next <- a
            Formatter.RecursionLimit <- 2
            Assert.Equal<string list>(
                [ "Node"; "  Name: a"; "  Next: Node"; "    Name: b"; "    Next: Node" ],
                lines (fmt a)))

    [<Fact>]
    let ``a dictionary is a key value table`` () =
        Assert.Equal<string list>(
            [ "key │ value"; "────┼──────"; "a   │     1"; "bb  │    22" ],
            lines (fmt (dict [ "a", 1; "bb", 22 ])))
        Assert.Equal<string list>(
            [ "key │ Name  │ Age"; "────┼───────┼────"; "x   │ Ada   │  36"; "y   │ Grace │ 145" ],
            lines (fmt (Map [ "x", people[0]; "y", people[1] ])))

    [<Fact>]
    let ``long sequences stop at the list expansion limit`` () =
        withFormatter (fun () ->
            Formatter.ListExpansionLimit <- 2
            let text = lines (fmt [ for i in 1..10 -> { Name = string i; Age = i } ])
            Assert.Equal(5, text.Length)
            Assert.Equal("(8 more)", List.last text))

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

    [<Fact>]
    let ``registered formatters are used at the top, in tables and in objects`` () =
        withFormatter (fun () ->
            Formatter.Register(fun (p: Person) -> sprintf "%s (%d)" p.Name p.Age)
            Assert.Equal(Some "Ada (36)", fmt people.Head)
            Assert.Equal<string list>([ "# │ value"; "──┼────────────"; "0 │ Ada (36)"; "1 │ Grace (145)" ], lines (fmt people))
            Assert.Equal<string list>(
                [ "key │ value"; "────┼─────────"; "a   │ Ada (36)" ],
                lines (fmt (dict [ "a", people.Head ]))))

    [<Fact>]
    let ``the most specific formatter wins and open generics work`` () =
        withFormatter (fun () ->
            Formatter.Register(fun (_: obj) -> "object")
            Formatter.Register(typedefof<list<_>>, fun _ -> "a list")
            Formatter.Register(fun (_: Account) -> "account")
            Assert.Equal(Some "account", fmt (Account("a", 1m)))
            Assert.Equal(Some "a list", fmt [ 1 ])
            Assert.Equal(Some "object", fmt 42))

    [<Fact>]
    let ``formatters for other MIME types are not used and reset clears them`` () =
        withFormatter (fun () ->
            Formatter.Register((fun (_: Account) -> "<b>html</b>"), "text/html")
            Assert.Equal("Account", (lines (fmt (Account("a", 1m)))).Head)
            Formatter.Register(fun (_: Account) -> "plain")
            Assert.Equal(Some "plain", fmt (Account("a", 1m)))
            Formatter.ResetToDefault()
            Assert.Equal("Account", (lines (fmt (Account("a", 1m)))).Head))

    let html (v: 'T) = Formatting.formatHtml (box v) typeof<'T>

    [<Fact>]
    let ``html for a list of records is a table`` () =
        withFormatter (fun () ->
            Assert.Equal(
                Some(
                    "<table class=\"fc-table\"><thead><tr><th>#</th><th>Name</th><th>Age</th></tr></thead><tbody>"
                    + "<tr><td class=\"fc-index\">0</td><td>Ada</td><td class=\"fc-num\">36</td></tr>"
                    + "<tr><td class=\"fc-index\">1</td><td>Grace</td><td class=\"fc-num\">145</td></tr></tbody></table>"),
                html people))

    [<Fact>]
    let ``html escapes text and folds nested objects`` () =
        withFormatter (fun () ->
            let h = (html [ Account("<b>", 1m) ]).Value
            Assert.Contains("&lt;b&gt;", h)
            let a = (html (Account("a", 1m))).Value
            Assert.StartsWith("<details class=\"fc-object\" open><summary>Account</summary><table class=\"fc-props\">", a)
            Assert.Contains("<tr><th>Owner</th><td>a</td></tr>", a))

    [<Fact>]
    let ``no html for values with a text layout or none of their own`` () =
        withFormatter (fun () ->
            Assert.Equal(None, html "text")
            Assert.Equal(None, html [ 1; 2 ])
            Assert.Equal(None, html people.Head)
            Assert.Equal(None, html (set [ (0, 0) ]))
            Assert.Equal(None, html (array2D [ [ 'a' ] ])))

    [<Fact>]
    let ``html follows DefaultMimeType, preferred MIME types and html formatters`` () =
        withFormatter (fun () ->
            Formatter.DefaultMimeType <- "text/plain"
            Assert.Equal(None, html people)
            Formatter.DefaultMimeType <- "text/html"
            Formatter.SetPreferredMimeTypesFor(typeof<Account>, "text/plain")
            Assert.Equal(None, html (Account("a", 1m)))
            Formatter.Register((fun (p: Person) -> "<i>" + p.Name + "</i>"), "text/html")
            Assert.Equal(Some "<i>Ada</i>", html people.Head)
            Assert.Contains("<td><i>Grace</i></td>", (html people).Value)
            // The text formatter is not affected by an html one.
            Assert.Equal(Some "{ Name = \"Ada\"\n  Age = 36 }", fmt people.Head))

    [<Fact>]
    let ``json formatting`` () =
        Assert.Equal(Some "{\n  \"Name\": \"Ada\",\n  \"Age\": 36\n}", Formatting.formatAs "application/json" (box people.Head) typeof<Person>)

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
              Outputs = []
              ValueText = Some "2"
              ValueHtml = None
              TypeName = Some "int"
              Error = None
              Diagnostics = []
              ElapsedMs = 5L }
        Assert.Equal(
            """{"type":"result","id":7,"ok":true,"stdout":"hi\n","valueText":"2","typeName":"int","diagnostics":[],"elapsedMs":5}""",
            serializeEvalResponse "7" r)

module ProtocolOutputTests =

    [<Fact>]
    let ``serializes outputs and valueHtml`` () =
        let r =
            { Ok = true
              Stdout = "a\nT\n"
              Outputs = [ { Kind = "text"; Text = "a\n"; Html = None }; { Kind = "display"; Text = "T"; Html = Some "<i>T</i>" } ]
              ValueText = Some "v"
              ValueHtml = Some "<b>v</b>"
              TypeName = Some "X"
              Error = None
              Diagnostics = []
              ElapsedMs = 1L }
        Assert.Equal(
            """{"type":"result","id":1,"ok":true,"stdout":"a\nT\n","outputs":[{"type":"text","text":"a\n"},{"type":"display","text":"T","html":"<i>T</i>"}],"valueText":"v","valueHtml":"<b>v</b>","typeName":"X","diagnostics":[],"elapsedMs":1}""",
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
    member _.``display and Formatter are available in the session``() =
        let r =
            eval
                "type P = { N: int }\nFormatter.Register(fun (p: P) -> sprintf \"P%d\" p.N)\ndisplay { N = 1 }\nprintfn \"mid\"\n{ N = 2 }.Display()\n[ { N = 3 } ]"
                1
        try
            Assert.True(r.Ok, string r.Error)
            Assert.Equal("P1\nmid\nP2\n", r.Stdout)
            Assert.Equal(Some "# │ value\n──┼──────\n0 │ P3", r.ValueText)
            Assert.Equal(Some "<table class=\"fc-table\"><thead><tr><th>#</th><th>value</th></tr></thead><tbody><tr><td class=\"fc-index\">0</td><td>P3</td></tr></tbody></table>", r.ValueHtml)
            Assert.Equal<Protocol.OutputItem list>(
                [ { Kind = "display"; Text = "P1"; Html = None }
                  { Kind = "text"; Text = "mid\n"; Html = None }
                  { Kind = "display"; Text = "P2"; Html = None } ],
                r.Outputs)
        finally
            FsxCells.Interactive.Formatter.ResetToDefault()

    [<Fact>]
    member _.``displayed tables carry html``() =
        let r = eval "type Q = { A: int }\nprintf \"x\"\ndisplay [ { A = 1 } ]\ndisplayHtml \"<b>hi</b>\"" 1
        Assert.True(r.Ok, string r.Error)
        match r.Outputs with
        | [ { Kind = "text"; Text = "x" }; { Kind = "display"; Html = Some table }; { Kind = "display"; Text = "<b>hi</b>"; Html = Some "<b>hi</b>" } ] ->
            Assert.StartsWith("<table", table)
        | other -> failwithf "%A" other
        Assert.Equal(None, r.ValueText)

    [<Fact>]
    member _.``no outputs list when nothing is displayed``() =
        let r = eval "printfn \"a\"\n1" 1
        Assert.Equal<Protocol.OutputItem list>([], r.Outputs)
        Assert.Equal(None, r.ValueHtml)

    [<Fact>]
    member _.``a class from the script is shown by its properties``() =
        let r = eval "type Acc(o: string) =\n    member _.Owner = o\nAcc \"Ada\"" 1
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(Some "Acc\n  Owner: Ada", r.ValueText)
