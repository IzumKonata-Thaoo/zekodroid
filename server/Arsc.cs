using System.Buffers.Binary;
using System.Text;

namespace ZekoRat.Server;

/// <summary>
/// Thay 1 chuỗi trong global string pool của resources.arsc → đổi tên app hiển thị.
/// Layout pool: [header][string offsets][style offsets][string data][style data]
/// </summary>
public static class Arsc
{
    const ushort RES_STRING_POOL = 0x0001;
    const ushort RES_TABLE = 0x0002;
    const uint UTF8_FLAG = 0x100;

    public static (byte[] Data, bool Changed, string? Error) ReplaceString(
        byte[] arsc, string oldValue, string newValue)
    {
        try
        {
            if (arsc.Length < 16) return (arsc, false, "arsc qua ngan");
            if (BinaryPrimitives.ReadUInt16LittleEndian(arsc) != RES_TABLE)
                return (arsc, false, "khong phai ResTable");

            int tableHeaderSize = BinaryPrimitives.ReadUInt16LittleEndian(arsc.AsSpan(2));
            uint tableSize = BinaryPrimitives.ReadUInt32LittleEndian(arsc.AsSpan(4));

            int poolOff = tableHeaderSize;
            if (BinaryPrimitives.ReadUInt16LittleEndian(arsc.AsSpan(poolOff)) != RES_STRING_POOL)
                return (arsc, false, "chunk dau khong phai string pool");

            int hdr = BinaryPrimitives.ReadUInt16LittleEndian(arsc.AsSpan(poolOff + 2));
            uint poolSize = BinaryPrimitives.ReadUInt32LittleEndian(arsc.AsSpan(poolOff + 4));
            uint stringCount = BinaryPrimitives.ReadUInt32LittleEndian(arsc.AsSpan(poolOff + 8));
            uint styleCount = BinaryPrimitives.ReadUInt32LittleEndian(arsc.AsSpan(poolOff + 12));
            uint flags = BinaryPrimitives.ReadUInt32LittleEndian(arsc.AsSpan(poolOff + 16));
            uint stringsStart = BinaryPrimitives.ReadUInt32LittleEndian(arsc.AsSpan(poolOff + 20));
            uint stylesStart = 0;
            if (hdr >= 28)
                stylesStart = BinaryPrimitives.ReadUInt32LittleEndian(arsc.AsSpan(poolOff + 24));

            bool utf8 = (flags & UTF8_FLAG) != 0;
            int offsetsBase = poolOff + hdr;
            int stringsBase = poolOff + (int)stringsStart;
            int stringsEnd = stylesStart > 0 ? poolOff + (int)stylesStart : poolOff + (int)poolSize;

            var strings = new List<string>((int)stringCount);
            for (int i = 0; i < stringCount; i++)
            {
                int off = BinaryPrimitives.ReadInt32LittleEndian(arsc.AsSpan(offsetsBase + i * 4));
                strings.Add(ReadString(arsc, stringsBase + off, utf8));
            }

            int hit = strings.FindIndex(s => s == oldValue);
            if (hit < 0) return (arsc, false, $"khong thay '{oldValue}' trong arsc");

            var replaced = new List<string>(strings);
            replaced[hit] = newValue;

            // dựng string data mới
            var dataMs = new MemoryStream();
            var newOffsets = new int[replaced.Count];
            for (int i = 0; i < replaced.Count; i++)
            {
                newOffsets[i] = (int)dataMs.Length;
                WriteString(dataMs, replaced[i], utf8);
            }
            while (dataMs.Length % 4 != 0) dataMs.WriteByte(0);
            var newData = dataMs.ToArray();

            // styles copy nguyên
            var styleOffsets = new byte[styleCount * 4];
            Array.Copy(arsc, offsetsBase + stringCount * 4, styleOffsets, 0, styleOffsets.Length);
            var styleData = Array.Empty<byte>();
            if (styleCount > 0 && stringsEnd > 0)
            {
                styleData = new byte[poolOff + (int)poolSize - stringsEnd];
                Array.Copy(arsc, stringsEnd, styleData, 0, styleData.Length);
            }

            int newOffsetsLen = replaced.Count * 4;
            int newStringsStart = hdr + newOffsetsLen + styleOffsets.Length;
            int newStylesStart = styleCount > 0 ? newStringsStart + newData.Length : 0;
            int newSize = newStringsStart + newData.Length + styleData.Length;
            while (newSize % 4 != 0) newSize++;

            var pool = new byte[newSize];
            BinaryPrimitives.WriteUInt16LittleEndian(pool.AsSpan(0), RES_STRING_POOL);
            BinaryPrimitives.WriteUInt16LittleEndian(pool.AsSpan(2), (ushort)hdr);
            BinaryPrimitives.WriteUInt32LittleEndian(pool.AsSpan(4), (uint)newSize);
            BinaryPrimitives.WriteUInt32LittleEndian(pool.AsSpan(8), (uint)replaced.Count);
            BinaryPrimitives.WriteUInt32LittleEndian(pool.AsSpan(12), styleCount);
            BinaryPrimitives.WriteUInt32LittleEndian(pool.AsSpan(16), flags);
            BinaryPrimitives.WriteUInt32LittleEndian(pool.AsSpan(20), (uint)newStringsStart);
            if (hdr >= 28)
                BinaryPrimitives.WriteUInt32LittleEndian(pool.AsSpan(24), (uint)newStylesStart);

            for (int i = 0; i < newOffsets.Length; i++)
                BinaryPrimitives.WriteInt32LittleEndian(pool.AsSpan(hdr + i * 4), newOffsets[i]);
            Array.Copy(styleOffsets, 0, pool, hdr + newOffsetsLen, styleOffsets.Length);
            Array.Copy(newData, 0, pool, newStringsStart, newData.Length);
            if (styleData.Length > 0)
                Array.Copy(styleData, 0, pool, newStylesStart, styleData.Length);

            int tailStart = poolOff + (int)poolSize;
            var result = new byte[arsc.Length - (int)poolSize + newSize];
            Array.Copy(arsc, 0, result, 0, poolOff);
            Array.Copy(pool, 0, result, poolOff, newSize);
            Array.Copy(arsc, tailStart, result, poolOff + newSize, arsc.Length - tailStart);

            BinaryPrimitives.WriteUInt32LittleEndian(result.AsSpan(4), tableSize - poolSize + (uint)newSize);
            return (result, true, null);
        }
        catch (Exception ex)
        {
            return (arsc, false, ex.GetType().Name + ": " + ex.Message);
        }
    }

    static string ReadString(byte[] buf, int pos, bool utf8)
    {
        if (utf8)
        {
            int len = buf[pos++];
            if ((len & 0x80) != 0) len = ((len & 0x7F) << 8) | buf[pos++];
            int bytes = buf[pos++];
            if ((bytes & 0x80) != 0) bytes = ((bytes & 0x7F) << 8) | buf[pos++];
            return Encoding.UTF8.GetString(buf, pos, bytes);
        }
        int n = BinaryPrimitives.ReadUInt16LittleEndian(buf.AsSpan(pos));
        pos += 2;
        if ((n & 0x8000) != 0)
        {
            int lo = n & 0x7FFF;
            int hi = BinaryPrimitives.ReadUInt16LittleEndian(buf.AsSpan(pos));
            pos += 2;
            n = (hi << 15) | lo;
        }
        return Encoding.Unicode.GetString(buf, pos, n * 2);
    }

    static void WriteString(MemoryStream ms, string s, bool utf8)
    {
        if (utf8)
        {
            var bytes = Encoding.UTF8.GetBytes(s);
            WriteLen8(ms, s.Length);
            WriteLen8(ms, bytes.Length);
            ms.Write(bytes);
            ms.WriteByte(0);
        }
        else
        {
            var bytes = Encoding.Unicode.GetBytes(s);
            WriteLen16(ms, s.Length);
            ms.Write(bytes);
            ms.WriteByte(0);
            ms.WriteByte(0);
        }
    }

    static void WriteLen8(MemoryStream ms, int v)
    {
        if (v > 0x7F)
        {
            ms.WriteByte((byte)(0x80 | (v >> 8)));
            ms.WriteByte((byte)(v & 0xFF));
        }
        else ms.WriteByte((byte)v);
    }

    static void WriteLen16(MemoryStream ms, int v)
    {
        // app_name luôn ngắn (< 0x7FFF) nên chỉ cần 1 u16
        var b = new byte[2];
        BinaryPrimitives.WriteUInt16LittleEndian(b, (ushort)(v & 0x7FFF));
        ms.Write(b);
    }
}
