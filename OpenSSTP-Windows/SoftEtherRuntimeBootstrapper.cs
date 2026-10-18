using System.ComponentModel;
using System.Diagnostics;
using System.Text.RegularExpressions;

namespace OpenSSTP.Windows;

/// <summary>
/// Deploys only the SoftEther engine files (no VPN Client Manager UI, Start-menu shortcuts,
/// or conventional SoftEther product registration) and registers the engine as a Windows service.
/// Windows will request administrator consent because a service and virtual network adapter are system components.
/// </summary>
internal static class SoftEtherRuntimeBootstrapper
{
    internal const string ServiceName = "SEVPNCLIENT";
    internal const string NicName = "VPN";
    internal static readonly string RuntimeDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "OpenSSTP", "SoftEtherRuntime");

    private static string BundledDirectory => Path.Combine(AppContext.BaseDirectory, "softether-runtime");
    private static string BundledVpnCmd => Path.Combine(BundledDirectory, "vpncmd.exe");

    public static async Task EnsureReadyAsync(CancellationToken cancellationToken = default)
    {
        if (await IsEngineReadyAsync(cancellationToken)) return;
        if (!File.Exists(BundledVpnCmd) || !File.Exists(Path.Combine(BundledDirectory, "vpnclient.exe")) || !File.Exists(Path.Combine(BundledDirectory, "hamcore.se2")))
            throw new FileNotFoundException(
                "The bundled SoftEther engine files are missing. Build the project using the GitHub Actions workflow; it compiles SoftEther and includes its engine files in the output.");

        Process? elevated = null;
        try
        {
            elevated = Process.Start(new ProcessStartInfo
            {
                FileName = Application.ExecutablePath,
                Arguments = "--install-softether-runtime",
                UseShellExecute = true,
                Verb = "runas",
                WorkingDirectory = AppContext.BaseDirectory
            });
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            throw new OperationCanceledException("Administrator permission was declined. OpenSSTP needs permission once to register the VPN engine service and create its virtual adapter.", ex);
        }

        if (elevated is null) throw new InvalidOperationException("Windows did not start the elevated engine setup process.");
        using (elevated)
        {
            await elevated.WaitForExitAsync(cancellationToken);
            if (elevated.ExitCode != 0)
                throw new InvalidOperationException("The bundled SoftEther engine could not be prepared. Run OpenSSTP again and approve the Windows administrator prompt, then check that Windows allows the signed network driver.");
        }

        if (!await IsEngineReadyAsync(cancellationToken))
            throw new InvalidOperationException("The SoftEther engine service or virtual adapter is still unavailable after setup. Restart Windows if the driver installation requested it, then try again.");
    }

    /// <summary>Called only by the same application re-launched with UAC elevation.</summary>
    public static void InstallFromBundle()
    {
        if (!IsAdministrator()) throw new UnauthorizedAccessException("Engine setup must run as administrator.");
        if (!File.Exists(BundledVpnCmd) || !File.Exists(Path.Combine(BundledDirectory, "vpnclient.exe")) || !File.Exists(Path.Combine(BundledDirectory, "hamcore.se2")))
            throw new FileNotFoundException("Required bundled SoftEther files are missing from softether-runtime.");

        Directory.CreateDirectory(RuntimeDirectory);
        foreach (var fileName in new[] { "vpnclient.exe", "vpncmd.exe", "hamcore.se2", "SOFTETHER-LICENSE.txt" })
        {
            var source = Path.Combine(BundledDirectory, fileName);
            if (File.Exists(source)) File.Copy(source, Path.Combine(RuntimeDirectory, fileName), overwrite: true);
        }

        var clientExe = Path.Combine(RuntimeDirectory, "vpnclient.exe");
        if (!ServiceExists())
        {
            var create = RunSystemCommand("sc.exe", new[]
            {
                "create", ServiceName,
                "binPath=", $"\"{clientExe}\" /service",
                "start=", "auto",
                "DisplayName=", "OpenSSTP VPN Engine"
            });
            if (create.ExitCode != 0 && !ServiceExists())
                throw new InvalidOperationException("Windows could not register the OpenSSTP VPN engine service. " + create.Output);
        }

        // A pre-existing SoftEther service is reused; we do not overwrite its executable path.
        var start = RunSystemCommand("sc.exe", new[] { "start", ServiceName });
        // sc.exe returns a nonzero code if the service is already running; verify the state instead.
        if (start.ExitCode != 0 && !ServiceIsRunning())
            throw new InvalidOperationException("Windows could not start the SoftEther engine service. " + start.Output);

        var vpncmd = Path.Combine(RuntimeDirectory, "vpncmd.exe");
        string nicList = WaitForClientService(vpncmd, TimeSpan.FromSeconds(35));
        if (!ContainsNic(nicList, NicName))
        {
            var createNic = RunVpnCmd(vpncmd, "NicCreate", NicName, TimeSpan.FromMinutes(2));
            nicList = WaitForClientService(vpncmd, TimeSpan.FromSeconds(15));
            if (!ContainsNic(nicList, NicName))
                throw new InvalidOperationException("SoftEther did not create its virtual network adapter. " + ShortOutput(createNic.Output));
        }
    }

    private static async Task<bool> IsEngineReadyAsync(CancellationToken ct)
    {
        if (!ServiceExists()) return false;
        var candidates = new[]
        {
            Path.Combine(RuntimeDirectory, "vpncmd.exe"),
            BundledVpnCmd,
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "SoftEther VPN Client", "vpncmd.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "SoftEther VPN Client", "vpncmd.exe")
        };
        var vpncmd = candidates.FirstOrDefault(File.Exists);
        if (vpncmd is null) return false;
        try
        {
            var result = await Task.Run(() => RunVpnCmd(vpncmd, "NicList", null, TimeSpan.FromSeconds(8)), ct);
            return result.ExitCode == 0 && !HasCommandError(result.Output) && ContainsNic(result.Output, NicName);
        }
        catch { return false; }
    }

    private static string WaitForClientService(string vpncmd, TimeSpan timeout)
    {
        var until = DateTime.UtcNow + timeout;
        string output = string.Empty;
        while (DateTime.UtcNow < until)
        {
            var result = RunVpnCmd(vpncmd, "NicList", null, TimeSpan.FromSeconds(8));
            output = result.Output;
            if (result.ExitCode == 0 && !HasCommandError(output)) return output;
            Thread.Sleep(1000);
        }
        throw new TimeoutException("The SoftEther service did not respond to vpncmd. " + ShortOutput(output));
    }

    private static bool ContainsNic(string output, string name) =>
        Regex.IsMatch(output, @"(?im)^\s*" + Regex.Escape(name) + @"\s*(?:\||$)") ||
        Regex.IsMatch(output, @"(?i)Virtual Network Adapter Name\s*\|\s*" + Regex.Escape(name));

    private static bool ServiceExists() => RunSystemCommand("sc.exe", new[] { "query", ServiceName }).ExitCode == 0;

    private static bool ServiceIsRunning()
    {
        var result = RunSystemCommand("sc.exe", new[] { "query", ServiceName });
        return result.ExitCode == 0 && result.Output.Contains("RUNNING", StringComparison.OrdinalIgnoreCase);
    }

    private static (int ExitCode, string Output) RunVpnCmd(string vpncmd, string command, string? parameter, TimeSpan timeout)
    {
        var psi = new ProcessStartInfo { FileName = vpncmd, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        psi.ArgumentList.Add("localhost"); psi.ArgumentList.Add("/CLIENT"); psi.ArgumentList.Add("/CMD"); psi.ArgumentList.Add(command);
        if (!string.IsNullOrWhiteSpace(parameter)) psi.ArgumentList.Add(parameter);
        return RunProcess(psi, timeout);
    }

    private static (int ExitCode, string Output) RunSystemCommand(string executable, IEnumerable<string> arguments)
    {
        var psi = new ProcessStartInfo { FileName = executable, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        foreach (var argument in arguments) psi.ArgumentList.Add(argument);
        return RunProcess(psi, TimeSpan.FromSeconds(30));
    }

    private static (int ExitCode, string Output) RunProcess(ProcessStartInfo psi, TimeSpan timeout)
    {
        using var process = new Process { StartInfo = psi };
        process.Start();
        var stdout = process.StandardOutput.ReadToEndAsync();
        var stderr = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit((int)timeout.TotalMilliseconds))
        {
            try { process.Kill(entireProcessTree: true); } catch { }
            throw new TimeoutException($"{Path.GetFileName(psi.FileName)} did not finish in time.");
        }
        return (process.ExitCode, stdout.GetAwaiter().GetResult() + Environment.NewLine + stderr.GetAwaiter().GetResult());
    }

    private static bool HasCommandError(string output) => Regex.IsMatch(output, @"Error occurred|Error code|Command failed|Failed to execute|not found", RegexOptions.IgnoreCase);
    private static string ShortOutput(string output) => output.Length > 1800 ? output[^1800..] : output;

    private static bool IsAdministrator()
    {
        using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
        return new System.Security.Principal.WindowsPrincipal(identity).IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
    }
}
