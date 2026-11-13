using System.IO.Compression;
using System.Text;

namespace ZekoRat.Server;

/// <summary>
/// Zip writer tự viết: cần kiểm soát offset để (a) căn 4 byte cho entries,
/// (b) resources.arsc phải STORED + aligned (Android 11+ bắt buộc).
/// </summary>
public static class Zip
{
    public sealed record Entry(string Name, byte[] Data, bool Store);

    static readonly uint[] CrcTable = BuildCrcTable();

    static uint[] BuildCrcTable()
    {
        var t = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            uint c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            t[i] = c;
        }
        return t;
    }

    public static uint Crc32(byte[] data)
    {
        uint c = 0xFFFFFFFFu;
        foreach (var b in data) c = CrcTable[(c ^ b) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFFu;
    }

    public static byte[] Deflate(byte[] data)
    {
        using var ms = new MemoryStream();
        using (var ds = new DeflateStream(ms, CompressionLevel.Optimal, true))
            ds.Write(data, 0, data.Length);
        return ms.ToArray();
    }

    /// <summary>Ghi zip chuẩn APK: mọi entry căn 4 byte, riêng arsc lưu không nén.</summary>
    public static byte[] Write(IEnumerable<Entry> entries)
    {
        using var ms = new MemoryStream();
        using var w = new BinaryWriter(ms, Encoding.UTF8, true);
        var central = new List<(string name, uint crc, int csize, int usize, int method, int offset)>();
        long pos = 0;

        foreach (var e in entries)
        {
            var nameBytes = Encoding.UTF8.GetBytes(e.Name);
            var raw = e.Data;
            uint crc = Crc32(raw);

            // resources.arsc bắt buộc store; còn lại nén nếu có lợi
            bool store = e.Store || e.Name.EndsWith("resources.arsc", StringComparison.Ordinal);
            byte[] payload = store ? raw : Deflate(raw);
            if (!store && payload.Length >= raw.Length) { store = true; payload = raw; }
            int method = store ? 0 : 8;

            // căn 4: chọn extraLen sao cho (pos + 30 + nameLen + extraLen) % 4 == 0
            int baseLen = 30 + nameBytes.Length;
            int pad = (4 - ((int)((pos + baseLen) % 4))) % 4;

            int offset = (int)pos;
            w.Write(0x04034b50u);
            w.Write((ushort)20);
            w.Write((ushort)0);
            w.Write((ushort)method);
            w.Write((ushort)0);            // time
            w.Write((ushort)0x21);         // date
            w.Write(crc);
            w.Write((uint)payload.Length);
            w.Write((uint)raw.Length);
            w.Write((ushort)nameBytes.Length);
            w.Write((ushort)pad);
            w.Write(nameBytes);
            for (int i = 0; i < pad; i++) w.Write((byte)0);
            w.Write(payload);
            pos += baseLen + pad + payload.Length;

            central.Add((e.Name, crc, payload.Length, raw.Length, method, offset));
        }

        long cdStart = pos;
        foreach (var c in central)
        {
            var nameBytes = Encoding.UTF8.GetBytes(c.name);
            w.Write(0x02014b50u);
            w.Write((ushort)20);
            w.Write((ushort)20);
            w.Write((ushort)0);
            w.Write((ushort)c.method);
            w.Write((ushort)0);
            w.Write((ushort)0x21);
            w.Write(c.crc);
            w.Write((uint)c.csize);
            w.Write((uint)c.usize);
            w.Write((ushort)nameBytes.Length);
            w.Write((ushort)0);   // extra
            w.Write((ushort)0);   // comment
            w.Write((ushort)0);   // disk
            w.Write((ushort)0);   // internal attrs
            w.Write(0u);          // external attrs
            w.Write((uint)c.offset);
            w.Write(nameBytes);
            pos += 46 + nameBytes.Length;
        }
        long cdSize = pos - cdStart;

        w.Write(0x06054b50u);
        w.Write((ushort)0);
        w.Write((ushort)0);
        w.Write((ushort)central.Count);
        w.Write((ushort)central.Count);
        w.Write((uint)cdSize);
        w.Write((uint)cdStart);
        w.Write((ushort)0);
        w.Flush();
        return ms.ToArray();
    }
}
