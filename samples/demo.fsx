// %%
let x = 1

// %%
// fsx-cells demo. Put the caret in a cell and press Ctrl+Enter.
// The whole
// file also runs as a normal script (up to the deliberate error cell):  dotnet fsi samples/demo.fsx

let input = """
..#..
.###.
#####
..#..
"""

// %%

// %%




// %% Parse into a grid
let grid =
    input.Trim().Split('\n')
    
    |> Array.map (fun l -> l.Trim().ToCharArray())

grid

// %%

// %%

(* cell *)
// The (x, y) points of the '#' cells
let points =
    set [ for y in 0 .. grid.Length - 1 do
            for x in 0 .. grid[y].Length - 1 do
                if grid[y][x] = '#' then (x, y) ]

points

// %% Output and a value
printfn "Found %d points" points.Count
points |> Set.map fst |> Set.maxElement

// %% A multi-line string
String.concat "\n" [ for i in 1 .. 100 -> String.replicate i "*" ]

// %% Runtime error
let explode () : int = failwith "boom"
explode ()

// %% Deep recursion (runs on a 256 MB stack)
let rec sumTo n = if n = 0L then 0L else n + sumTo (n - 1L)
sumTo 1_000_000L

// %% Test long line
let fooBar = "abca;alksdf ;laksjd f;lkajsdf ;lkajsd flkj asdf hjkg asdf jhgasdkf jhg asdkfj hg asdkfj hgasdkfj hgasdkjfh gaksjdhfg kajshdgf kjh"
fooBar