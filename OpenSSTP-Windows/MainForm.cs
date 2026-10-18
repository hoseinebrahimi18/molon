using System.Drawing.Drawing2D;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace OpenSSTP.Windows;

public sealed class MainForm : Form
{
    TextBox host = new() { PlaceholderText = "VPN Gate hostname or IP[:port]", Width = 280 };
    TextBox user = new() { PlaceholderText = "Username (VPN Gate: vpn)", Width = 280 };
    TextBox pass = new() { PlaceholderText = "Password (VPN Gate often blank)", Width = 280, UseSystemPasswordChar = true };
    CheckBox rememberMe = new() { Text = "Remember me  |  مرا به خاطر بسپار", AutoSize = true, MinimumSize = new Size(0, 28), ForeColor = Color.FromArgb(220, 228, 240), BackColor = Color.Transparent, Margin = new Padding(0, 8, 0, 4) };
    Label state = new() { Text = "NOT CONNECTED", AutoSize = true, ForeColor = Color.FromArgb(255, 190, 90), Font = new Font("Segoe UI", 10, FontStyle.Bold) };
    Label rx = new() { Text = "↓ 0 KB/s", AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 17) };
    Label tx = new() { Text = "↑ 0 KB/s", AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 17) };
    Label note = new() { AutoSize = false, Height = 52, Dock = DockStyle.Bottom, ForeColor = Color.FromArgb(255, 205, 130), TextAlign = ContentAlignment.MiddleLeft };
    TrafficChart chart = new() { Dock = DockStyle.Fill, BackColor = Color.FromArgb(19, 25, 36) };
    System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    Button connectButton = new() { Text = "Connect", Width = 170, Height = 42, FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(46, 119, 235), ForeColor = Color.White, Margin = new Padding(0, 14, 0, 0) };
    SoftEtherClientEngine? engine;
    bool connected;
    bool connecting;
    bool polling;
    DateTime lastStatusPoll = DateTime.MinValue;
    string? lastAdapterId;
    long lastIn, lastOut;
    readonly NotifyIcon trayIcon = new();
    readonly ContextMenuStrip trayMenu = new();
    readonly ToolStripMenuItem trayStatusItem = new("Open-SSTP — Not connected") { Enabled = false };
    readonly ToolStripMenuItem trayConnectionItem = new("Connect VPN");
    readonly ToolStripMenuItem trayAutoStartItem = new("Start with Windows");
    Icon? trayIconIdle, trayIconBusy, trayIconOn;
    EventWaitHandle? showEvent;
    RegisteredWaitHandle? showEventWait;
    bool startHidden;
    bool exitRequested;
    bool exiting;
    bool trayReady;
    bool trayHintShown;

    const string AutoStartKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string AutoStartValue = "OpenSSTP";

    [DllImport("user32.dll", SetLastError = true)]
    static extern bool DestroyIcon(IntPtr handle);

    public MainForm(bool startInTray = false)
    {
        startHidden = startInTray;
        try { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        Text = "Open-SSTP for Windows"; MinimumSize = new Size(760, 700); Size = new Size(900, 800);
        BackColor = Color.FromArgb(13, 18, 28); ForeColor = Color.White; Font = new Font("Segoe UI", 10); StartPosition = FormStartPosition.CenterScreen;
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(24), ColumnCount = 1, RowCount = 4 };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 74)); root.RowStyles.Add(new RowStyle(SizeType.Absolute, 275)); root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));
        Controls.Add(root);
        var title = new Label { Text = "Open-SSTP", Font = new Font("Segoe UI Semibold", 25), AutoSize = true, ForeColor = Color.White, Dock = DockStyle.Fill };
        root.Controls.Add(title, 0, 0);
        var details = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1, BackColor = Color.FromArgb(24, 32, 46), Padding = new Padding(18) };
        details.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 56)); details.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 44));
        var fields = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false };
        fields.Controls.Add(MakeField("SERVER / HOST (optionally :port)", host)); fields.Controls.Add(MakeField("USERNAME", user)); fields.Controls.Add(MakeField("PASSWORD", pass));
        fields.Controls.Add(rememberMe);
        details.Controls.Add(fields, 0, 0);
        var statusPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, Padding = new Padding(12, 4, 0, 0), WrapContents = false };
        statusPanel.Controls.Add(new Label { Text = "CONNECTION STATUS", AutoSize = true, ForeColor = Color.FromArgb(155, 170, 190) }); statusPanel.Controls.Add(state);
        connectButton.FlatAppearance.BorderSize = 0; connectButton.Click += async (_, _) => await ToggleConnectionAsync(); statusPanel.Controls.Add(connectButton);
        details.Controls.Add(statusPanel, 1, 0); root.Controls.Add(details, 0, 1);
        var graphPanel = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1, Padding = new Padding(0, 18, 0, 0) };
        graphPanel.RowStyles.Add(new RowStyle(SizeType.Absolute, 48)); graphPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var metrics = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        metrics.Controls.Add(rx); metrics.Controls.Add(new Label { Width = 28 }); metrics.Controls.Add(tx); graphPanel.Controls.Add(metrics, 0, 0); graphPanel.Controls.Add(chart, 0, 1); root.Controls.Add(graphPanel, 0, 2);
        note.Text = "Built-in SoftEther engine. First connection may request Administrator permission to register the engine service and VPN adapter. Graph measures VPN adapter traffic only."; root.Controls.Add(note, 0, 3);
        timer.Tick += async (_, _) => await OnTimerTickAsync(); timer.Start();
        LoadRememberedData();
        rememberMe.CheckedChanged += (_, _) => { if (!rememberMe.Checked) RememberMeStore.Clear(); };

        // Notification-area (system tray) icon. Windows may place it under the "^" / "Show hidden icons"
        // flyout; the user can drag it onto the taskbar to keep it always visible.
        BuildTrayIcons();
        trayConnectionItem.Click += async (_, _) => await ToggleConnectionAsync();
        trayAutoStartItem.Click += (_, _) => ToggleAutoStart();
        trayMenu.Items.Add(trayStatusItem);
        trayMenu.Items.Add(new ToolStripSeparator());
        trayMenu.Items.Add(trayConnectionItem);
        trayMenu.Items.Add("Open Open-SSTP", null, (_, _) => RestoreFromTray());
        trayMenu.Items.Add(new ToolStripSeparator());
        trayMenu.Items.Add(trayAutoStartItem);
        trayMenu.Items.Add(new ToolStripSeparator());
        trayMenu.Items.Add("Exit", null, async (_, _) => await ExitApplicationAsync());
        trayMenu.Opening += (_, _) => { RefreshTrayMenu(); trayAutoStartItem.Checked = IsAutoStartEnabled(); };
        trayConnectionItem.Font = new Font(trayConnectionItem.Font, FontStyle.Bold);
        trayIcon.ContextMenuStrip = trayMenu;
        trayIcon.Icon = trayIconIdle ?? SystemIcons.Application;
        trayIcon.Text = "Open-SSTP — Not connected";
        trayIcon.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) RestoreFromTray(); };
        trayIcon.BalloonTipClicked += (_, _) => RestoreFromTray();
        trayIcon.Visible = true;
        trayReady = true;
        RefreshTrayMenu();

        // A second launch of the exe (or Start-menu shortcut) brings this instance back from the tray.
        try
        {
            showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ShowEventName);
            showEventWait = ThreadPool.RegisterWaitForSingleObject(showEvent, (_, _) =>
            {
                try { if (!IsDisposed && IsHandleCreated) BeginInvoke(new Action(RestoreFromTray)); } catch { }
            }, null, Timeout.Infinite, false);
        }
        catch { }

        if (startHidden) ShowInTaskbar = false;
        Resize += (_, _) => { if (WindowState == FormWindowState.Minimized) HideToTray(); };
        FormClosing += (_, e) =>
        {
            SaveRememberedData();
            bool systemClosing = e.CloseReason is CloseReason.WindowsShutDown or CloseReason.TaskManagerClosing or CloseReason.ApplicationExitCall;
            if (!exitRequested && !systemClosing && trayReady)
            {
                e.Cancel = true;
                HideToTray();
                if (!trayHintShown)
                {
                    trayHintShown = true;
                    trayIcon.ShowBalloonTip(3000, "Open-SSTP", "Still running in the system tray. Right-click the icon to connect, disconnect or exit.", ToolTipIcon.Info);
                }
            }
            else
            {
                timer.Stop();
                showEventWait?.Unregister(null);
                showEvent?.Dispose();
                trayIcon.Visible = false;
                trayIcon.Dispose();
                trayMenu.Dispose();
                trayIconIdle?.Dispose(); trayIconBusy?.Dispose(); trayIconOn?.Dispose();
            }
        };
    }

    // When started with --tray (e.g. from "Start with Windows") the window stays hidden until the tray icon is used.
    protected override void SetVisibleCore(bool value)
    {
        if (startHidden)
        {
            value = false;
            if (!IsHandleCreated) CreateHandle();
        }
        base.SetVisibleCore(value);
    }

    void HideToTray()
    {
        ShowInTaskbar = false;
        Hide();
        trayIcon.Visible = true;
        RefreshTrayMenu();
    }

    void RestoreFromTray()
    {
        startHidden = false;
        ShowInTaskbar = true;
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        // Briefly toggling TopMost reliably brings the window forward even when Windows blocks plain Activate().
        TopMost = true; TopMost = false;
        Activate();
    }

    async Task ExitApplicationAsync()
    {
        if (exiting) return;
        exiting = true;
        try
        {
            // Leaving the app should not leave a VPN session running with no UI to stop it.
            if (connected && engine is not null)
            {
                trayIcon.Text = "Open-SSTP — Disconnecting…";
                try { await engine.DisconnectAsync(); } catch { }
                connected = false;
            }
        }
        finally
        {
            exitRequested = true;
            trayReady = false;
            Close();
        }
    }

    void BuildTrayIcons()
    {
        Icon baseIcon = Icon ?? SystemIcons.Application;
        trayIconIdle = MakeStatusIcon(baseIcon, null);
        trayIconBusy = MakeStatusIcon(baseIcon, Color.FromArgb(255, 190, 60));
        trayIconOn = MakeStatusIcon(baseIcon, Color.FromArgb(46, 204, 113));
    }

    // Draws the app icon with a small coloured status dot (none = idle, amber = busy, green = connected).
    static Icon MakeStatusIcon(Icon baseIcon, Color? dot)
    {
        if (dot is null) return (Icon)baseIcon.Clone();
        try
        {
            const int size = 32;
            using var bmp = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.Clear(Color.Transparent);
                g.DrawImage(baseIcon.ToBitmap(), new Rectangle(0, 0, size, size));
                var r = new Rectangle(size - 15, size - 15, 14, 14);
                using var ring = new SolidBrush(Color.White);
                using var fill = new SolidBrush(dot.Value);
                g.FillEllipse(ring, r);
                g.FillEllipse(fill, Rectangle.Inflate(r, -2, -2));
            }
            IntPtr h = bmp.GetHicon();
            try { return (Icon)System.Drawing.Icon.FromHandle(h).Clone(); }
            finally { DestroyIcon(h); }
        }
        catch { return (Icon)baseIcon.Clone(); }
    }

    static bool IsAutoStartEnabled()
    {
        try { using var k = Registry.CurrentUser.OpenSubKey(AutoStartKey); return k?.GetValue(AutoStartValue) is string v && v.Length > 0; }
        catch { return false; }
    }

    void ToggleAutoStart()
    {
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(AutoStartKey);
            if (IsAutoStartEnabled()) k.DeleteValue(AutoStartValue, false);
            else k.SetValue(AutoStartValue, "\"" + Application.ExecutablePath + "\" --tray");
            trayAutoStartItem.Checked = IsAutoStartEnabled();
        }
        catch (Exception ex) { MessageBox.Show("Could not change the Start with Windows setting.\n\n" + ex.Message, "Open-SSTP", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
    }

    void RefreshTrayMenu()
    {
        if (!trayReady) return;
        string status = connected ? "Connected" : connecting ? "Working…" : "Not connected";
        trayStatusItem.Text = "Open-SSTP — " + status;
        trayConnectionItem.Text = connected ? "Disconnect VPN" : connecting ? "Please wait…" : "Connect VPN";
        trayConnectionItem.Enabled = !connecting;
        trayIcon.Text = "Open-SSTP — " + status;
        trayIcon.Icon = connected ? (trayIconOn ?? trayIconIdle ?? SystemIcons.Application)
                      : connecting ? (trayIconBusy ?? trayIconIdle ?? SystemIcons.Application)
                      : (trayIconIdle ?? SystemIcons.Application);
    }

    void NotifyTray(string message, ToolTipIcon icon = ToolTipIcon.Info)
    {
        // Balloons are only useful when the window is out of sight.
        if (!Visible) trayIcon.ShowBalloonTip(2500, "Open-SSTP", message, icon);
    }

    async Task ToggleConnectionAsync()
    {
        if (connecting) return;
        if (connected)
        {
            connecting = true; connectButton.Enabled = false; state.Text = "DISCONNECTING..."; RefreshTrayMenu();
            try
            {
                engine ??= new SoftEtherClientEngine();
                await engine.DisconnectAsync();
                connected = false; ResetTraffic();
                state.Text = "NOT CONNECTED"; state.ForeColor = Color.FromArgb(255, 190, 90);
                note.Text = "Disconnected. VPN traffic graph is paused.";
                NotifyTray("Disconnected");
            }
            catch (Exception ex) { MessageBox.Show(DescribeError(ex), "Disconnect failed", MessageBoxButtons.OK, MessageBoxIcon.Error); }
            finally { connecting = false; connectButton.Enabled = true; connectButton.Text = connected ? "Disconnect" : "Connect"; RefreshTrayMenu(); }
            return;
        }

        if (string.IsNullOrWhiteSpace(host.Text))
        {
            // Nothing to connect to yet (e.g. clicked "Connect" in the tray on first run): ask for the server.
            RestoreFromTray(); host.Focus();
            note.Text = "Enter a VPN Gate server first, then press Connect.";
            return;
        }
        connecting = true; connectButton.Enabled = false; state.Text = "CONNECTING..."; state.ForeColor = Color.FromArgb(255, 205, 130);
        RefreshTrayMenu();
        note.Text = "Preparing the bundled SoftEther engine, then connecting to hub VPNGATE...";
        try
        {
            engine ??= new SoftEtherClientEngine();
            await engine.ConnectAsync(host.Text, user.Text, pass.Text);
            lastStatusPoll = DateTime.MinValue;
            note.Text = "Connection request sent. Waiting for SoftEther to confirm the session...";
            await PollConnectionStateAsync();
        }
        catch (Exception ex)
        {
            connected = false; ResetTraffic(); state.Text = "CONNECTION FAILED"; state.ForeColor = Color.FromArgb(255, 115, 115);
            note.Text = "SoftEther connection failed. See the error message.";
            NotifyTray("Connection failed", ToolTipIcon.Error);
            MessageBox.Show(DescribeError(ex), "VPN connection failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally { connecting = false; connectButton.Enabled = true; connectButton.Text = connected ? "Disconnect" : "Connect"; RefreshTrayMenu(); }
    }

    async Task OnTimerTickAsync()
    {
        if (IsDisposed || connecting) return;
        if (DateTime.UtcNow - lastStatusPoll >= TimeSpan.FromSeconds(4)) await PollConnectionStateAsync();
        UpdateVpnTraffic();
    }

    async Task PollConnectionStateAsync()
    {
        if (polling || engine is null) return;
        polling = true; lastStatusPoll = DateTime.UtcNow;
        try
        {
            bool nowConnected = await engine.IsConnectedAsync();
            if (nowConnected != connected)
            {
                connected = nowConnected;
                if (connected)
                {
                    state.Text = "CONNECTED"; state.ForeColor = Color.FromArgb(63, 220, 164);
                    note.Text = "Connected through SoftEther. Speed graph reads counters from the VPN adapter.";
                    connectButton.Text = "Disconnect";
                    ResetTraffic();
                    NotifyTray("Connected");
                }
                else
                {
                    state.Text = "NOT CONNECTED"; state.ForeColor = Color.FromArgb(255, 190, 90);
                    note.Text = "SoftEther reports no active session. VPN speed is shown as zero.";
                    connectButton.Text = "Connect";
                    ResetTraffic();
                }
            }
        }
        catch
        {
            // A temporary status-query failure must not fabricate a connected state or traffic.
            connected = false; state.Text = "STATUS UNAVAILABLE"; state.ForeColor = Color.FromArgb(255, 190, 90);
            connectButton.Text = "Connect"; ResetTraffic();
        }
        finally { polling = false; RefreshTrayMenu(); }
    }

    void UpdateVpnTraffic()
    {
        if (!connected) { rx.Text = "↓ 0 KB/s"; tx.Text = "↑ 0 KB/s"; chart.Caption = "VPN traffic (idle until SoftEther reports connected)"; chart.Add(0, 0); return; }
        var nic = SoftEtherClientEngine.FindVpnInterface();
        if (nic is null)
        {
            rx.Text = "↓ 0 KB/s"; tx.Text = "↑ 0 KB/s"; chart.Caption = "Connected session reported; VPN adapter counters unavailable"; chart.Add(0, 0); return;
        }
        try
        {
            var stats = nic.GetIPStatistics();
            if (!string.Equals(lastAdapterId, nic.Id, StringComparison.Ordinal))
            {
                lastAdapterId = nic.Id; lastIn = stats.BytesReceived; lastOut = stats.BytesSent;
                rx.Text = "↓ 0 KB/s"; tx.Text = "↑ 0 KB/s"; chart.Add(0, 0); return;
            }
            long inNow = stats.BytesReceived, outNow = stats.BytesSent;
            long down = Math.Max(0, inNow - lastIn), up = Math.Max(0, outNow - lastOut);
            lastIn = inNow; lastOut = outNow;
            rx.Text = "↓ " + Fmt(down); tx.Text = "↑ " + Fmt(up); chart.Caption = "Measured SoftEther VPN adapter traffic (bytes per second)"; chart.Add(down, up);
        }
        catch { rx.Text = "↓ 0 KB/s"; tx.Text = "↑ 0 KB/s"; chart.Add(0, 0); }
    }

    void ResetTraffic()
    {
        lastAdapterId = null; lastIn = 0; lastOut = 0; rx.Text = "↓ 0 KB/s"; tx.Text = "↑ 0 KB/s"; chart.ClearSamples();
    }

    void LoadRememberedData()
    {
        try
        {
            var saved = RememberMeStore.Load();
            if (saved is null) return;
            host.Text = saved.Host; user.Text = saved.UserName; pass.Text = saved.Password; rememberMe.Checked = true;
        }
        catch (Exception ex) { MessageBox.Show("Saved login information could not be read. You can enter it again.\n\n" + ex.Message, "Remember me", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
    }

    void SaveRememberedData()
    {
        try { if (rememberMe.Checked) RememberMeStore.Save(host.Text.Trim(), user.Text, pass.Text); else RememberMeStore.Clear(); }
        catch (Exception ex) { MessageBox.Show("Could not save the login information securely. The password was not saved.\n\n" + ex.Message, "Remember me", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
    }

    Control MakeField(string caption, TextBox box)
    {
        var panel = new FlowLayoutPanel { Width = 330, Height = 54, FlowDirection = FlowDirection.TopDown, WrapContents = false, Margin = new Padding(0, 0, 0, 3) };
        panel.Controls.Add(new Label { Text = caption, AutoSize = true, ForeColor = Color.FromArgb(155, 170, 190), Font = new Font("Segoe UI", 8, FontStyle.Bold) });
        box.BackColor = Color.FromArgb(13, 18, 28); box.ForeColor = Color.White; box.BorderStyle = BorderStyle.FixedSingle; panel.Controls.Add(box); return panel;
    }

    // Builds a full, never-empty error text (type + message + inner errors + location) and saves it to
    // %LocalAppData%\OpenSSTP\last-error.log so it can be copied or sent for diagnosis.
    static string DescribeError(Exception ex)
    {
        var sb = new System.Text.StringBuilder();
        for (Exception? e = ex; e is not null; e = e.InnerException)
        {
            sb.AppendLine(e.GetType().FullName + (string.IsNullOrWhiteSpace(e.Message) ? " (no message)" : ": " + e.Message.Trim()));
            if (e is System.ComponentModel.Win32Exception w) sb.AppendLine("Win32 error code: " + w.NativeErrorCode);
        }
        var stack = (ex.StackTrace ?? string.Empty).Split('\n').Take(6);
        sb.AppendLine().AppendLine("Where:").AppendLine(string.Join("\n", stack).Trim());
        var text = sb.ToString().Trim();
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OpenSSTP");
            Directory.CreateDirectory(dir);
            File.WriteAllText(Path.Combine(dir, "last-error.log"), DateTime.Now.ToString("u") + Environment.NewLine + text);
            text += "\n\n(Saved to: " + Path.Combine(dir, "last-error.log") + ")";
        }
        catch { }
        return text;
    }

    static string Fmt(long bytesPerSecond) => bytesPerSecond >= 1024 * 1024 ? $"{bytesPerSecond / 1024d / 1024:0.00} MB/s" : $"{bytesPerSecond / 1024d:0.0} KB/s";
}

public sealed class TrafficChart : Control
{
    readonly Queue<double> incoming = new(), outgoing = new();
    public string Caption { get; set; } = "Measured VPN adapter traffic";
    public TrafficChart() { DoubleBuffered = true; SetStyle(ControlStyles.ResizeRedraw, true); }
    public void Add(long rx, long tx) { incoming.Enqueue(rx / 1024d); outgoing.Enqueue(tx / 1024d); while (incoming.Count > 90) incoming.Dequeue(); while (outgoing.Count > 90) outgoing.Dequeue(); Invalidate(); }
    public void ClearSamples() { incoming.Clear(); outgoing.Clear(); Add(0, 0); }
    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e); e.Graphics.SmoothingMode = SmoothingMode.AntiAlias; e.Graphics.Clear(BackColor);
        using var grid = new Pen(Color.FromArgb(42, 55, 73)); for (int i = 1; i < 5; i++) e.Graphics.DrawLine(grid, 0, Height * i / 5, Width, Height * i / 5);
        Draw(incoming, Color.FromArgb(72, 170, 255), e.Graphics); Draw(outgoing, Color.FromArgb(63, 220, 164), e.Graphics);
        using var font = new Font("Segoe UI", 8); using var brush = new SolidBrush(Color.FromArgb(155, 170, 190)); e.Graphics.DrawString(Caption, font, brush, 8, 8);
    }
    void Draw(IEnumerable<double> vals, Color color, Graphics g)
    {
        var a = vals.ToArray(); if (a.Length < 2) return; double max = Math.Max(10, a.Max()) * 1.15;
        var pts = a.Select((v, i) => new PointF(i * (Width - 2f) / Math.Max(1, a.Length - 1), Height - 8 - (float)(v / max * (Height - 24)))).ToArray();
        using var pen = new Pen(color, 2.2f); g.DrawLines(pen, pts);
    }
}
