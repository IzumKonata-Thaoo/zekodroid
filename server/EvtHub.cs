using System.Collections.Concurrent;
using System.Text.Json.Nodes;

namespace ZekoRat.Server;

/// <summary>
/// Ring buffer event realtime (sms mới, vị trí, clipboard, audio PCM, screen state...).
/// Panel poll /events?since=seq để lấy phần mới.
/// </summary>
public static class EvtHub
{
    public sealed record Evt(long Seq, string Type, JsonNode? Data, DateTime Ts);

    static readonly ConcurrentDictionary<string, List<Evt>> _byAgent = new();
    static long _seq;
    const int MaxPerAgent = 300;
    // audio chunk nhiều → giữ ít hơn
    const int MaxAudio = 80;

    public static long Put(string hwid, string type, JsonNode? data)
    {
        var list = _byAgent.GetOrAdd(hwid, _ => new List<Evt>());
        var e = new Evt(Interlocked.Increment(ref _seq), type, data, DateTime.UtcNow);
        lock (list)
        {
            list.Add(e);
            if (list.Count > MaxPerAgent)
                list.RemoveRange(0, list.Count - MaxPerAgent);
            int audio = list.Count(x => x.Type == "audio");
            if (audio > MaxAudio)
            {
                int drop = audio - MaxAudio;
                for (int i = 0; i < list.Count && drop > 0; i++)
                {
                    if (list[i].Type != "audio") continue;
                    list.RemoveAt(i);
                    i--; drop--;
                }
            }
        }
        return e.Seq;
    }

    public static (long Seq, JsonArray Items) Since(string hwid, long since, int limit = 120)
    {
        var arr = new JsonArray();
        long last = since;
        if (!_byAgent.TryGetValue(hwid, out var list)) return (last, arr);
        List<Evt> snapshot;
        lock (list) { snapshot = list.ToList(); }
        foreach (var e in snapshot.Where(x => x.Seq > since).Take(limit))
        {
            var o = new JsonObject
            {
                ["seq"] = e.Seq,
                ["type"] = e.Type,
                ["ts"] = e.Ts.ToString("HH:mm:ss"),
                ["data"] = e.Data?.DeepClone()
            };
            arr.Add(o);
            last = e.Seq;
        }
        return (last, arr);
    }

    public static void Clear(string hwid) => _byAgent.TryRemove(hwid, out _);
}
