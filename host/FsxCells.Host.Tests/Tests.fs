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
    let ``other values use %A`` () =
        Assert.Equal(Some "Some 3", fmt (Some 3))
        Assert.Equal(Some "None", fmt (None: int option))
        Assert.Equal(Some "[1; 2; 3]", fmt [ 1; 2; 3 ])
        Assert.Equal(Some "42", fmt 42)

    /// Runs `f` with a culture whose own date format differs from ISO 8601 (20.07.1969).
    let withNorwegianCulture f =
        let previous = Globalization.CultureInfo.CurrentCulture
        Globalization.CultureInfo.CurrentCulture <- Globalization.CultureInfo("nb-NO")
        try f () finally Globalization.CultureInfo.CurrentCulture <- previous

    [<Fact>]
    let ``dates and times are ISO 8601 whatever the culture`` () =
        withNorwegianCulture (fun () ->
            Assert.Equal(Some "1969-07-20", fmt (DateOnly(1969, 7, 20)))
            Assert.Equal(Some "1969-07-20", fmt (DateTime(1969, 7, 20)))
            Assert.Equal(Some "1969-07-20 20:17:40", fmt (DateTime(1969, 7, 20, 20, 17, 40)))
            Assert.Equal(Some "1969-07-20 20:17:40.5Z", fmt (DateTime(1969, 7, 20, 20, 17, 40, 500, DateTimeKind.Utc)))
            Assert.Equal(Some "1969-07-20 00:00:00Z", fmt (DateTime(1969, 7, 20, 0, 0, 0, DateTimeKind.Utc)))
            Assert.Equal(Some "1969-07-20 20:17:40 +02:00", fmt (DateTimeOffset(1969, 7, 20, 20, 17, 40, TimeSpan.FromHours 2.0)))
            Assert.Equal(Some "20:17:40.25", fmt (TimeOnly(20, 17, 40, 250))))

    [<Fact>]
    let ``dates in tables and HTML are ISO 8601 too`` () =
        withNorwegianCulture (fun () ->
            let rows = [ {| Event = "Landing"; On = DateOnly(1969, 7, 20) |} ]
            Assert.Contains("1969-07-20", (fmt rows).Value)
            Assert.Contains("1969-07-20", (Formatting.formatHtml (box rows) (rows.GetType())).Value)
            Assert.DoesNotContain("20.07.1969", (fmt rows).Value))

    [<Fact>]
    let ``char grids, string arrays and point sets have no layout of their own`` () =
        Assert.Equal(Some "[|\"x\"; \"y\"|]", fmt [| "x"; "y" |])
        Assert.Equal(Some "[\"x\"; \"y\"]", fmt [ "x"; "y" ])
        Assert.Equal(Some "set [(0, 0); (2, 1)]", fmt (set [ (0, 0); (2, 1) ]))
        Assert.Equal(Some "[|[|'a'; 'b'|]|]", fmt [| "ab".ToCharArray() |])
        Assert.Equal(Some "[['a'; 'b']\n ['c'; 'd']]", fmt (array2D [ [ 'a'; 'b' ]; [ 'c'; 'd' ] ]))

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
        Assert.Equal(Eval("7", "1+1", 3, None), parseRequest """{"type":"eval","id":7,"code":"1+1","startLine":3}""")
        Assert.Equal(Eval("\"x\"", "1", 1, None), parseRequest """{"type":"eval","id":"x","code":"1"}""")

    [<Fact>]
    let ``parses the display of an eval`` () =
        Assert.Equal(Eval("1", "x", 1, Some Image), parseRequest """{"type":"eval","id":1,"code":"x","display":"image"}""")
        Assert.Equal(Eval("1", "x", 1, Some Html), parseRequest """{"type":"eval","id":1,"code":"x","display":"html"}""")
        Assert.Equal(Eval("1", "x", 1, None), parseRequest """{"type":"eval","id":1,"code":"x","display":null}""")

    [<Fact>]
    let ``an unknown display is an error`` () =
        let e = Assert.Throws<Exception>(fun () -> parseRequest """{"type":"eval","id":1,"code":"x","display":"video"}""" |> ignore)
        Assert.Equal("unknown display 'video'", e.Message)

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

module DisplayTests =

    let onePixelPng =
        Convert.FromBase64String "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    /// `length` bytes starting with `header`, zero-filled.
    let bytesOf (header: int list) (length: int) =
        let b = Array.zeroCreate<byte> length
        header |> List.iteri (fun i x -> b[i] <- byte x)
        b

    let ascii (s: string) = s |> Seq.map int |> List.ofSeq

    let png (w: int) (h: int) (length: int) =
        let be (x: int) = [ x >>> 24; x >>> 16; x >>> 8; x ] |> List.map (fun v -> v &&& 0xFF)
        bytesOf ([ 0x89; 0x50; 0x4E; 0x47; 0x0D; 0x0A; 0x1A; 0x0A; 0; 0; 0; 13 ] @ ascii "IHDR" @ be w @ be h) length

    let ok (r: Result<string * string, string>) =
        match r with
        | Ok v -> v
        | Error e -> failwithf "expected Ok, got Error %s" e

    let error (r: Result<string * string, string>) =
        match r with
        | Error e -> e
        | Ok v -> failwithf "expected Error, got Ok %A" v

    let imageText (v: obj) = fst (ok (Formatting.imageOutput v))

    [<Fact>]
    let ``png bytes become an img with a data uri`` () =
        let text, html = ok (Formatting.imageOutput (box onePixelPng))
        Assert.Equal("image/png, 1×1, 70 bytes", text)
        Assert.Equal("<img src=\"data:image/png;base64," + Convert.ToBase64String onePixelPng + "\">", html)

    [<Fact>]
    let ``png dimensions and size are in the text`` () =
        Assert.Equal("image/png, 640×480, 22 KB", imageText (box (png 640 480 23_000)))

    [<Fact>]
    let ``size is shown in bytes, KB or MB`` () =
        Assert.Equal("image/png, 1×1, 100 bytes", imageText (box (png 1 1 100)))
        Assert.Equal("image/png, 1×1, 2 KB", imageText (box (png 1 1 2048)))
        Assert.Equal("image/png, 1×1, 1.5 MB", imageText (box (png 1 1 1_572_864)))

    [<Fact>]
    let ``gif bmp and jpeg are sniffed and measured`` () =
        let gif = bytesOf (ascii "GIF89a" @ [ 0x40; 0x01; 0xF0; 0x00 ]) 2048
        Assert.Equal("image/gif, 320×240, 2 KB", imageText (box gif))
        // BM header, then width 100 and height -50 (a top-down bitmap) at offsets 18 and 22.
        let bmp = bytesOf (ascii "BM" @ List.replicate 16 0 @ [ 100; 0; 0; 0; 0xCE; 0xFF; 0xFF; 0xFF ]) 500
        Assert.Equal("image/bmp, 100×50, 500 bytes", imageText (box bmp))
        // SOI, an APP0 segment of length 4, then SOF0 with height 480 and width 640.
        let jpeg = bytesOf [ 0xFF; 0xD8; 0xFF; 0xE0; 0; 4; 0; 0; 0xFF; 0xC0; 0; 17; 8; 0x01; 0xE0; 0x02; 0x80 ] 1024
        Assert.Equal("image/jpeg, 640×480, 1 KB", imageText (box jpeg))

    [<Fact>]
    let ``webp is recognised without dimensions`` () =
        let webp = bytesOf (ascii "RIFF" @ [ 0; 0; 0; 0 ] @ ascii "WEBP") 2048
        Assert.Equal("image/webp, 2 KB", imageText (box webp))

    [<Fact>]
    let ``an svg string becomes an image`` () =
        let svg = "  <svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"1\"/></svg>"
        let text, html = ok (Formatting.imageOutput (box svg))
        Assert.Equal("image/svg+xml, 63 bytes", text)
        let b64 = Convert.ToBase64String(Text.Encoding.UTF8.GetBytes svg)
        Assert.Equal("<img src=\"data:image/svg+xml;base64," + b64 + "\">", html)
        Assert.StartsWith("image/svg+xml", imageText (box "<?xml version=\"1.0\"?>\n<svg></svg>"))

    [<Fact>]
    let ``a string is an image file path, relative to the current directory`` () =
        let dir = Path.Combine(Path.GetTempPath(), "fsx-cells-tests", Guid.NewGuid().ToString("N"))
        Directory.CreateDirectory dir |> ignore
        File.WriteAllBytes(Path.Combine(dir, "a.png"), onePixelPng)
        File.WriteAllText(Path.Combine(dir, "b.svg"), "<g/>")
        File.WriteAllText(Path.Combine(dir, "c.txt"), "not an image")
        let original = Directory.GetCurrentDirectory()
        try
            Directory.SetCurrentDirectory dir
            Assert.Equal("image/png, 1×1, 70 bytes", imageText (box "a.png"))
            Assert.Equal("image/png, 1×1, 70 bytes", imageText (box (Path.Combine(dir, "a.png"))))
            Assert.StartsWith("image/svg+xml", imageText (box "b.svg"))
            Assert.Equal("[image] file not found: missing.png", error (Formatting.imageOutput (box "missing.png")))
            Assert.Contains("c.txt", error (Formatting.imageOutput (box "c.txt")))
        finally
            Directory.SetCurrentDirectory original

    [<Fact>]
    let ``image errors for unknown bytes and other types`` () =
        Assert.Contains("is not a PNG", error (Formatting.imageOutput (box [| 1uy; 2uy; 3uy |])))
        Assert.Equal(
            "[image] expects byte[], an SVG string or an image file path, got Int32",
            error (Formatting.imageOutput (box 42)))
        Assert.Equal(
            "[image] expects byte[], an SVG string or an image file path, got list<Int32>",
            error (Formatting.imageOutput (box [ 1 ])))

    [<Fact>]
    let ``html passes a string through`` () =
        Assert.Equal(("<b>hi</b>", "<b>hi</b>"), ok (Formatting.htmlOutput (box "<b>hi</b>")))
        Assert.Equal("[html] expects a string, got Int32", error (Formatting.htmlOutput (box 1)))
        Assert.Equal("[html] expects a string, got null", error (Formatting.htmlOutput null))

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
    member _.``display image shows the value as an image``() =
        let png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        let r = session.Eval(sprintf "System.Convert.FromBase64String \"%s\"" png, 1, Image)
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(Some "image/png, 1×1, 70 bytes", r.ValueText)
        Assert.StartsWith("<img src=\"data:image/png;base64,", r.ValueHtml.Value)
        Assert.Equal(Some "byte array", r.TypeName)

    [<Fact>]
    member _.``display with a value of the wrong type fails the cell``() =
        let r = session.Eval("42", 1, Image)
        Assert.False r.Ok
        Assert.Equal(Some "[image] expects byte[], an SVG string or an image file path, got Int32", r.Error)
        Assert.Equal(None, r.ValueHtml)
        let h = session.Eval("\"<b>x</b>\"", 1, Html)
        Assert.True(h.Ok, string h.Error)
        Assert.Equal(Some "<b>x</b>", h.ValueHtml)
        Assert.Equal(Some "<b>x</b>", h.ValueText)

    [<Fact>]
    member _.``display on a cell without a value shows nothing``() =
        let r = session.Eval("let y = 1", 1, Image)
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(None, r.ValueText)
        Assert.Equal(None, r.ValueHtml)

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
    member _.``value from a cell is formatted with its type``() =
        let r = eval "array2D [ \"#.\"; \".#\" ]" 1
        Assert.True(r.Ok, string r.Error)
        Assert.Equal(Some "[['#'; '.']\n ['.'; '#']]", r.ValueText)
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
        Assert.Equal(Some(Formatting.formatA (box [| scriptPath |]) typeof<string[]>), r.ValueText)

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

module HostProcessTests =

    open System.Diagnostics

    /// The built host, next to this test project (same configuration).
    let hostDll () =
        let testDir = Path.GetDirectoryName(typeof<Person>.Assembly.Location)
        let config = DirectoryInfo(testDir).Parent.Name // bin/<config>/net10.0
        Path.GetFullPath(Path.Combine(testDir, "..", "..", "..", "..", "FsxCells.Host", "bin", config, "net10.0", "FsxCells.Host.dll"))

    [<Fact>]
    let ``the host process loads F# from the installed SDK and runs cells`` () =
        let scriptPath = makeScriptDir ()
        let info = ProcessStartInfo("dotnet", "\"" + hostDll () + "\"")
        info.WorkingDirectory <- Path.GetDirectoryName scriptPath
        info.RedirectStandardInput <- true
        info.RedirectStandardOutput <- true
        info.RedirectStandardError <- true
        // The protocol is UTF-8 (as the plugin reads it); the default is the console code page, not UTF-8 on Windows.
        let utf8 = Text.UTF8Encoding(false)
        info.StandardInputEncoding <- utf8
        info.StandardOutputEncoding <- utf8
        info.StandardErrorEncoding <- utf8
        info.UseShellExecute <- false
        use p = Process.Start info
        p.StandardInput.WriteLine(sprintf """{"type":"init","scriptPath":%s}""" (Text.Json.JsonSerializer.Serialize scriptPath))
        p.StandardInput.WriteLine("""{"type":"eval","id":1,"code":"typeof<int list>.Assembly.Location"}""")
        p.StandardInput.WriteLine("""{"type":"eval","id":2,"code":"fsi.AddPrinter(fun (x: int) -> \"I\" + string x)\n[ {| N = 1 |} ]"}""")
        p.StandardInput.Close()
        let fsharpCore = Text.Json.JsonDocument.Parse(p.StandardOutput.ReadLine()).RootElement
        let table = Text.Json.JsonDocument.Parse(p.StandardOutput.ReadLine()).RootElement
        p.WaitForExit()
        Assert.True(fsharpCore.GetProperty("ok").GetBoolean(), p.StandardError.ReadToEnd())
        // .../sdk/<version>/FSharp/FSharp.Core.dll
        let location = fsharpCore.GetProperty("valueText").GetString()
        Assert.Equal("FSharp", Path.GetFileName(Path.GetDirectoryName location))
        Assert.Equal("sdk", Path.GetFileName(Path.GetDirectoryName(Path.GetDirectoryName(Path.GetDirectoryName location))))
        Assert.Equal("# │ N\n──┼───\n0 │ I1", table.GetProperty("valueText").GetString())
        Assert.Equal(0, p.ExitCode)
