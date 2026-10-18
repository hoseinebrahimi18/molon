using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace OpenSSTP.Windows;

/// <summary>
/// Stores the password in the current Windows user's Credential Manager.
/// Only the non-secret server name is kept in a small JSON settings file.
/// </summary>
internal static class RememberMeStore
{
    private const string CredentialTarget = "OpenSSTP.Windows.RememberMe";
    private static readonly string SettingsDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OpenSSTP.Windows");
    private static readonly string SettingsPath = Path.Combine(SettingsDirectory, "remember-me.json");

    internal sealed record SavedLogin(string Host, string UserName, string Password);
    private sealed record SavedSettings(string Host);

    public static SavedLogin? Load()
    {
        if (!File.Exists(SettingsPath)) return null;
        var json = File.ReadAllText(SettingsPath, Encoding.UTF8);
        var settings = JsonSerializer.Deserialize<SavedSettings>(json);
        if (settings is null || !CredRead(CredentialTarget, 1, 0, out var credentialPointer)) return null;
        try
        {
            var credential = Marshal.PtrToStructure<ReadCredential>(credentialPointer);
            var username = Marshal.PtrToStringUni(credential.UserName) ?? string.Empty;
            var password = credential.CredentialBlobSize > 0
                ? Marshal.PtrToStringUni(credential.CredentialBlob) ?? string.Empty
                : string.Empty;
            return new SavedLogin(settings.Host, username, password);
        }
        finally { CredFree(credentialPointer); }
    }

    public static void Save(string host, string username, string password)
    {
        Directory.CreateDirectory(SettingsDirectory);
        var blob = Marshal.StringToCoTaskMemUni(password ?? string.Empty);
        try
        {
            var credential = new NativeCredential
            {
                Type = 1, // CRED_TYPE_GENERIC
                TargetName = CredentialTarget,
                CredentialBlobSize = checked((uint)((password ?? string.Empty).Length * 2)),
                CredentialBlob = blob,
                Persist = 2, // CRED_PERSIST_LOCAL_MACHINE (within this Windows user profile)
                UserName = username ?? string.Empty
            };
            if (!CredWrite(ref credential, 0))
                throw new InvalidOperationException("Windows Credential Manager rejected the saved credential (error " + Marshal.GetLastWin32Error() + ").");
            File.WriteAllText(SettingsPath, JsonSerializer.Serialize(new SavedSettings(host)), Encoding.UTF8);
        }
        finally { Marshal.ZeroFreeCoTaskMemUnicode(blob); }
    }

    public static void Clear()
    {
        try { CredDelete(CredentialTarget, 1, 0); } catch { }
        try { if (File.Exists(SettingsPath)) File.Delete(SettingsPath); } catch { }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NativeCredential
    {
        public uint Flags;
        public uint Type;
        public string TargetName;
        public string? Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public string? TargetAlias;
        public string UserName;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ReadCredential
    {
        public uint Flags;
        public uint Type;
        public IntPtr TargetName;
        public IntPtr Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public IntPtr TargetAlias;
        public IntPtr UserName;
    }

    [DllImport("Advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredWrite(ref NativeCredential userCredential, uint flags);

    [DllImport("Advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);

    [DllImport("Advapi32.dll", EntryPoint = "CredFree", SetLastError = false)]
    private static extern void CredFree(IntPtr buffer);

    [DllImport("Advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredDelete(string target, uint type, uint flags);

}
