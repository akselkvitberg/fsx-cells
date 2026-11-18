/// Loads the F# compiler, FSharp.Core and the `fsi` settings assembly from the installed .NET SDK, the one
/// `dotnet fsi` would use in the script's folder, so new F# versions work without a new host.
///
/// This runs before anything else and must not itself need FSharp.Core (which isn't loadable until the resolver is
/// installed): only .NET types, no F# library functions, lists, options or printf here.
module FsxCells.Host.Sdk

open System
open System.Diagnostics
open System.IO
open System.Reflection
open System.Runtime.Loader

/// Set to a folder with FSharp.Compiler.Service.dll and FSharp.Core.dll to use those instead (for development).
let FSharpDirVariable = "FSXCELLS_FSHARP_DIR"

/// Output of `dotnet <args>`, run with the same dotnet that runs the host.
let private dotnet (args: string) : string =
    let processPath = Environment.ProcessPath
    let exe =
        if not (String.IsNullOrEmpty processPath)
           && Path.GetFileNameWithoutExtension(processPath).Equals("dotnet", StringComparison.OrdinalIgnoreCase) then
            processPath
        else
            "dotnet"
    let info = ProcessStartInfo(exe, args)
    info.RedirectStandardOutput <- true
    info.RedirectStandardError <- true
    info.UseShellExecute <- false
    use p = Process.Start info
    let output = p.StandardOutput.ReadToEnd()
    let error = p.StandardError.ReadToEnd()
    p.WaitForExit()
    if p.ExitCode <> 0 then
        raise (InvalidOperationException("`dotnet " + args + "` failed: " + error.Trim() + " " + output.Trim()))
    output.Trim()

/// The version in the output of `dotnet --version`: its last non-blank line (a first run can print a welcome banner first).
let parseSdkVersion (output: string) : string =
    let lines = output.Split('\n')
    let mutable version = ""
    for line in lines do
        let trimmed = line.Trim()
        if trimmed.Length > 0 then
            version <- trimmed
    version

/// The F# folder of the SDK that `dotnet` picks in the current directory (the script's folder; global.json applies).
let fsharpDir () : string =
    let overrideDir = Environment.GetEnvironmentVariable FSharpDirVariable
    if not (String.IsNullOrEmpty overrideDir) then
        overrideDir
    else
        let version = parseSdkVersion (dotnet "--version")
        // `--list-sdks` lines look like "10.0.403 [/usr/share/dotnet/sdk]".
        let mutable dir: string = null
        for line in dotnet("--list-sdks").Split('\n') do
            let line = line.Trim()
            let bracket = line.IndexOf(" [")
            if isNull dir && bracket > 0 && line.Substring(0, bracket) = version && line.EndsWith("]") then
                let sdks = line.Substring(bracket + 2, line.Length - bracket - 3)
                dir <- Path.Combine(sdks, version, "FSharp")
        if isNull dir then
            raise (InvalidOperationException("The .NET SDK " + version + " is not in `dotnet --list-sdks`."))
        dir

/// Makes .NET load the F# assemblies (and their satellite resources) from `dir`.
let useFSharpFrom (dir: string) =
    if not (File.Exists(Path.Combine(dir, "FSharp.Compiler.Service.dll"))) then
        raise (InvalidOperationException("No F# compiler (FSharp.Compiler.Service.dll) in " + dir + "."))
    AssemblyLoadContext.Default.add_Resolving (
        Func<AssemblyLoadContext, AssemblyName, Assembly>(fun context name ->
            let folder = if String.IsNullOrEmpty name.CultureName then dir else Path.Combine(dir, name.CultureName)
            let path = Path.Combine(folder, name.Name + ".dll")
            if File.Exists path then context.LoadFromAssemblyPath path else null)
    )

/// The folder the F# compiler was loaded from.
let loadedFSharpDir () =
    Path.GetDirectoryName(typeof<FSharp.Compiler.Interactive.Shell.FsiEvaluationSession>.Assembly.Location)
