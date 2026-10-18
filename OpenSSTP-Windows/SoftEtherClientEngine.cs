using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Text;
using System.Text.RegularExpressions;

namespace OpenSSTP.Windows;

/// <summary>
/// Controls the bundled SoftEther service engine through vpncmd. The service/adapter bootstrapper
/// deploys only the engine files; it does not install the Client Manager GUI or official installer.
/// </summary>
internal sealed class SoftEtherClientEngine
{
    private const string AccountName = "OpenSSTP_VPNGate";
    private const string HubName = "VPNGATE";
    private const string NicName = "VPN";
    private string? vpncmdPath;

    public SoftEtherClientEngine()
    {
        // Prefer the private engine bundled with OpenSSTP. If this is a first run, the engine
        // bootstrapper will deploy the service and create its virtual NIC after UAC consent.
        vpncmdPath = FindVpnCmd();
    }

    public async Task ConnectAsync(string hostInput, string usernameInput, string password, CancellationToken cancellationToken = default)
    {
        await SoftEtherRuntimeBootstrapper.EnsureReadyAsync(cancellationToken);
        vpncmdPath = FindVpnCmd() ?? throw new FileNotFoundException("The bundled SoftEther command utility could not be found.");

        var (host, port) = ParseHost(hostInput);
        var username = string.IsNullOrWhiteSpace(usernameInput) ? "vpn" : usernameInput.Trim();
        EnsureCommandValue(host, nameof(host));
        EnsureCommandValue(username, nameof(username));
        EnsureCommandValue(password ?? string.Empty, "password");

        // Ensure the local SoftEther VPN Client service responds before changing account settings.
        var nicList = await RunCommandAsync("NicList", cancellationToken);
        if (HasCommandError(nicList))
            throw new InvalidOperationException("SoftEther VPN Client did not accept commands. Ensure its service is installed and running.\n\n" + ShortOutput(nicList));
        if (!nicList.Contains(NicName, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("The SoftEther virtual network adapter named 'VPN' was not found. Install/enable a virtual adapter in SoftEther VPN Client (or rename it to VPN), then try again.\n\n" + ShortOutput(nicList));

        var accountList = await RunCommandAsync("AccountList", cancellationToken);
        if (HasCommandError(accountList))
            throw new InvalidOperationException("Could not list SoftEther VPN accounts.\n\n" + ShortOutput(accountList));

        var accountExists = accountList.Contains(AccountName, StringComparison.OrdinalIgnoreCase);
        var commands = new List<string>();
        if (accountExists)
        {
            commands.Add($"AccountDisconnect {AccountName}");
            commands.Add($"AccountSet {AccountName} /SERVER:{host}:{port} /HUB:{HubName}");
            commands.Add($"AccountUsernameSet {AccountName} /USERNAME:{Quote(username)}");
            commands.Add($"AccountNicSet {AccountName} /NICNAME:{NicName}");
        }
        else
        {
            commands.Add($"AccountCreate {AccountName} /SERVER:{host}:{port} /HUB:{HubName} /USERNAME:{Quote(username)} /NICNAME:{NicName}");
        }
        commands.Add($"AccountPasswordSet {AccountName} /PASSWORD:{Quote(password ?? string.Empty)} /TYPE:standard");
        commands.Add($"AccountConnect {AccountName}");

        var output = await RunInteractiveAsync(commands, cancellationToken);
        if (HasCommandError(StripBenignErrors(output)))
            throw new InvalidOperationException("SoftEther could not configure or start the VPN connection. Check the host, port, VPN Gate hub, and credentials.\n\n" + ShortOutput(output));
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        var list = await RunCommandAsync("AccountList", cancellationToken);
        if (!list.Contains(AccountName, StringComparison.OrdinalIgnoreCase)) return;
        var output = await RunInteractiveAsync(new[] { $"AccountDisconnect {AccountName}" }, cancellationToken);
        if (HasCommandError(StripBenignErrors(output)))
            throw new InvalidOperationException("SoftEther could not disconnect the account.\n\n" + ShortOutput(output));
    }

    public async Task<bool> IsConnectedAsync(CancellationToken cancellationToken = default)
    {
        var list = await RunCommandAsync("AccountList", cancellationToken);
        if (!list.Contains(AccountName, StringComparison.OrdinalIgnoreCase)) return false;
        var output = await RunCommandAsync("AccountStatusGet", cancellationToken, AccountName);
        if (HasCommandError(output)) return false;
        // SoftEther's status output has varied slightly between releases/locales; accept the common
        // English connected/session-established indicators, but never infer connection from UI state.
        return Regex.IsMatch(output, @"(Session\s+Status\s*\|\s*Connected|Connection\s+Status\s*\|\s*Connected|Connection\s+Established|Session\s+Status\s*\|\s*Established)", RegexOptions.IgnoreCase)
            || Regex.IsMatch(output, @"\bConnected\b", RegexOptions.IgnoreCase) && !Regex.IsMatch(output, @"\bNot Connected\b|\bDisconnected\b", RegexOptions.IgnoreCase);
    }

    public static NetworkInterface? FindVpnInterface()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up)
                .Where(n => n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .Where(n => (n.Name + " " + n.Description).Contains("VPN Client Adapter", StringComparison.OrdinalIgnoreCase)
                         || (n.Name + " " + n.Description).Contains("SoftEther", StringComparison.OrdinalIgnoreCase))
                .OrderBy(n => n.Name.Contains("VPN", StringComparison.OrdinalIgnoreCase) ? 0 : 1)
                .FirstOrDefault();
        }
        catch { return null; }
    }

    private async Task<string> RunCommandAsync(string command, CancellationToken ct, string? parameter = null)
    {
        var psi = CreateStartInfo();
        psi.ArgumentList.Add("localhost");
        psi.ArgumentList.Add("/CLIENT");
        psi.ArgumentList.Add("/CMD");
        psi.ArgumentList.Add(command);
        if (!string.IsNullOrWhiteSpace(parameter)) psi.ArgumentList.Add(parameter);
        return await RunProcessAsync(psi, null, ct);
    }

    private async Task<string> RunInteractiveAsync(IEnumerable<string> commands, CancellationToken ct)
    {
        var psi = CreateStartInfo();
        psi.ArgumentList.Add("localhost");
        psi.ArgumentList.Add("/CLIENT");
        return await RunProcessAsync(psi, string.Join(Environment.NewLine, commands) + Environment.NewLine + "exit" + Environment.NewLine, ct);
    }

    private ProcessStartInfo CreateStartInfo() => new()
    {
        FileName = vpncmdPath ?? FindVpnCmd() ?? throw new FileNotFoundException("vpncmd.exe was not found in the bundled engine or a standard SoftEther path."),
        UseShellExecute = false,
        RedirectStandardInput = true,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
        CreateNoWindow = true,
        StandardOutputEncoding = Encoding.UTF8,
        StandardErrorEncoding = Encoding.UTF8
    };

    private static async Task<string> RunProcessAsync(ProcessStartInfo psi, string? stdin, CancellationToken ct)
    {
        using var process = new Process { StartInfo = psi };
        if (!process.Start()) throw new InvalidOperationException("Could not start vpncmd.exe.");
        var stdoutTask = process.StandardOutput.ReadToEndAsync();
        var stderrTask = process.StandardError.ReadToEndAsync();
        if (stdin is not null) await process.StandardInput.WriteAsync(stdin.AsMemory(), ct);
        process.StandardInput.Close();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(35));
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch { }
            if (ct.IsCancellationRequested) throw;
            throw new TimeoutException("vpncmd.exe did not finish within 35 seconds.");
        }
        var stdout = await stdoutTask;
        var stderr = await stderrTask;
        return stdout + (string.IsNullOrWhiteSpace(stderr) ? string.Empty : Environment.NewLine + stderr);
    }

    private static string? FindVpnCmd()
    {
        var env = Environment.GetEnvironmentVariable("SOFTETHER_VPNCMD");
        if (!string.IsNullOrWhiteSpace(env) && File.Exists(env)) return env;
        var candidates = new[]
        {
            Path.Combine(SoftEtherRuntimeBootstrapper.RuntimeDirectory, "vpncmd.exe"),
            Path.Combine(AppContext.BaseDirectory, "softether-runtime", "vpncmd.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "SoftEther VPN Client", "vpncmd.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "SoftEther VPN Client", "vpncmd.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "SoftEther VPN Client", "vpncmd_x64.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "SoftEther VPN Client", "vpncmd_x86.exe")
        };
        return candidates.FirstOrDefault(File.Exists);
    }

    private static (string Host, int Port) ParseHost(string input)
    {
        var value = (input ?? string.Empty).Trim();
        if (value.Length == 0) throw new ArgumentException("Enter a VPN Gate server hostname or IP address.");
        if (value.Contains("://", StringComparison.Ordinal)) throw new ArgumentException("Enter only the hostname/IP, optionally followed by :port; do not include https://.");
        var colon = value.LastIndexOf(':');
        if (colon > 0 && value.IndexOf(':') == colon && int.TryParse(value[(colon + 1)..], out var port))
        {
            if (port is < 1 or > 65535) throw new ArgumentException("Port must be between 1 and 65535.");
            return (value[..colon], port);
        }
        return (value, 443);
    }

    private static void EnsureCommandValue(string value, string field)
    {
        if (value.Contains('\r') || value.Contains('\n')) throw new ArgumentException($"The {field} cannot contain a line break.");
    }

    private static string Quote(string value) => "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
    private static bool HasCommandError(string output) => Regex.IsMatch(output, @"Error occurred|Error code|Command failed|Failed to execute|not found", RegexOptions.IgnoreCase);
    // "AccountDisconnect" fails with error code 37 ("not connected") when the account is idle.
    // That is expected before a fresh connect and must not be reported as a failure.
    private static string StripBenignErrors(string output) =>
        Regex.Replace(output, @"Error occurred\.\s*\(Error code:\s*37\)[^\r\n]*(\r?\n[^\r\n]*not connected[^\r\n]*)?", string.Empty, RegexOptions.IgnoreCase);
    private static string ShortOutput(string output) => output.Length > 4500 ? output[^4500..] : output;
}
