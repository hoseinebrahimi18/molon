using System;
using System.Threading;
using System.Windows.Forms;
namespace OpenSSTP.Windows;
internal static class Program
{
    // Used so a second launch just brings the already-running (possibly tray-only) instance to the front.
    internal const string ShowEventName = @"Local\OpenSSTP.Windows.ShowWindow";
    const string MutexName = @"Local\OpenSSTP.Windows.SingleInstance";

    [STAThread]
    static void Main(string[] args)
    {
        if (args.Any(a => string.Equals(a, "--install-softether-runtime", StringComparison.OrdinalIgnoreCase)))
        {
            try
            {
                SoftEtherRuntimeBootstrapper.InstallFromBundle();
                Environment.ExitCode = 0;
            }
            catch (Exception ex)
            {
                MessageBox.Show("OpenSSTP could not prepare its built-in VPN engine.\n\n" + ex.Message,
                    "OpenSSTP engine setup failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
                Environment.ExitCode = 1;
            }
            return;
        }

        // Single instance: if OpenSSTP is already running (e.g. hidden in the tray), show it and quit.
        using var mutex = new Mutex(true, MutexName, out bool isFirstInstance);
        if (!isFirstInstance)
        {
            try { using var ev = EventWaitHandle.OpenExisting(ShowEventName); ev.Set(); } catch { }
            return;
        }

        bool startInTray = args.Any(a => string.Equals(a, "--tray", StringComparison.OrdinalIgnoreCase)
                                      || string.Equals(a, "--minimized", StringComparison.OrdinalIgnoreCase));
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(startInTray));
    }
}
