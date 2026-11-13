using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace ZekoRat.Server;

/// <summary>
/// APK Signature Scheme v2 (id 0x7109871a) thuần .NET — không cần apksigner/JDK.
///
/// Thứ tự đúng theo spec/apksig:
///   EOCD của APK cuối phải trỏ tới Central Directory (nằm SAU signing block),
///   block được tìm bằng 24 byte ngay trước CD (size + magic).
///   → blockSize phải biết trước khi tính digest, nên dựng block 1 lần với chữ ký
///     độ dài cố định (RSA keySize/8) để lấy kích thước.
/// </summary>
public static class ApkSign
{
    const uint V2Id = 0x7109871a;
    const uint AlgRsaPkcs1Sha256 = 0x0103;
    static readonly byte[] Magic = "APK Sig Block 42"u8.ToArray();

    public sealed record SignResult(byte[] Apk, bool Ok, string? Error = null);

    public static SignResult SignV2(byte[] apk, X509Certificate2 cert)
    {
        try
        {
            var rsa = cert.GetRSAPrivateKey();
            if (rsa is null) return new SignResult(apk, false, "cert khong co private key");
            int sigLen = rsa.KeySize / 8;

            int eocd = FindEocd(apk);
            if (eocd < 0) return new SignResult(apk, false, "khong tim thay EOCD");
            uint cdSize = BinaryPrimitives.ReadUInt32LittleEndian(apk.AsSpan(eocd + 12));
            uint cdOffset = BinaryPrimitives.ReadUInt32LittleEndian(apk.AsSpan(eocd + 16));
            if ((long)cdOffset + cdSize > apk.Length) return new SignResult(apk, false, "EOCD loi");

            int entriesEnd = (int)cdOffset;
            var certs = cert.RawData;
            var pubKey = cert.PublicKey.ExportSubjectPublicKeyInfo();

            // 1. block size với chữ ký giả cùng độ dài
            var dummyDigest = new byte[32];
            var dummySig = new byte[sigLen];
            var probeBlock = BuildBlock(dummyDigest, dummySig, certs, pubKey);
            int blockSize = probeBlock.Length;

            // 2. EOCD cuối: CD offset = vị trí CD thật (sau block)
            // EOCD ghi ra file: cd_offset trỏ tới CD thật (sau block)
            var eocdFinal = apk.AsSpan(eocd).ToArray();
            BinaryPrimitives.WriteUInt32LittleEndian(
                eocdFinal.AsSpan(16), (uint)(entriesEnd + blockSize));

            // EOCD dùng để tính digest: cd_offset trỏ tới ĐẦU BLOCK (đúng như apksig)
            var eocdForDigest = apk.AsSpan(eocd).ToArray();
            BinaryPrimitives.WriteUInt32LittleEndian(
                eocdForDigest.AsSpan(16), (uint)entriesEnd);

            var digest = ComputeDigest(apk, entriesEnd, (int)cdOffset, (int)cdSize, eocdForDigest);

            // 4. ký: signed_data (đã có length prefix)
            var signedData = BuildSignedData(digest, certs);
            // ký trên chính bytes signed-data (KHÔNG gồm 4 byte length prefix)
            var sig = rsa.SignData(signedData, HashAlgorithmName.SHA256,
                RSASignaturePadding.Pkcs1);
            if (sig.Length != sigLen) return new SignResult(apk, false, "signature length la");

            // 5. block thật + ghép
            var block = BuildBlock(digest, sig, certs, pubKey);
            if (block.Length != blockSize) return new SignResult(apk, false, "block size lech");

            using var outMs = new MemoryStream(apk.Length + blockSize + 16);
            outMs.Write(apk, 0, entriesEnd);
            outMs.Write(block, 0, block.Length);
            outMs.Write(apk, entriesEnd, eocd - entriesEnd);   // CD
            outMs.Write(eocdFinal, 0, eocdFinal.Length);       // EOCD sửa
            return new SignResult(outMs.ToArray(), true);
        }
        catch (Exception ex)
        {
            return new SignResult(apk, false, ex.GetType().Name + ": " + ex.Message);
        }
    }

    static byte[] BuildSignedData(byte[] digest, byte[] certs)
    {
        var digestRec = Concat(U32(AlgRsaPkcs1Sha256), LenPrefixed(digest));
        var digestsField = LenPrefixed(LenPrefixed(digestRec));
        var certsField = LenPrefixed(LenPrefixed(certs));
        var attrsField = LenPrefixed(Array.Empty<byte>());
        return Concat(digestsField, certsField, attrsField);
    }

    static byte[] BuildBlock(byte[] digest, byte[] sig, byte[] certs, byte[] pubKey)
    {
        var signedData = BuildSignedData(digest, certs);
        var sigRec = Concat(U32(AlgRsaPkcs1Sha256), LenPrefixed(sig));
        var signaturesField = LenPrefixed(LenPrefixed(sigRec));
        var publicKeyField = LenPrefixed(pubKey);

        var signer = Concat(LenPrefixed(signedData), signaturesField, publicKeyField);
        var signers = LenPrefixed(LenPrefixed(signer));     // uint32 len + seq of len-prefixed signers

        var pair = new byte[8 + 4 + signers.Length];
        BinaryPrimitives.WriteInt64LittleEndian(pair, 4 + signers.Length);
        BinaryPrimitives.WriteUInt32LittleEndian(pair.AsSpan(8), V2Id);
        Buffer.BlockCopy(signers, 0, pair, 12, signers.Length);

        long size = pair.Length + 8 + 16;
        var block = new byte[8 + size];
        BinaryPrimitives.WriteInt64LittleEndian(block, size);
        Buffer.BlockCopy(pair, 0, block, 8, pair.Length);
        BinaryPrimitives.WriteInt64LittleEndian(block.AsSpan(8 + pair.Length), size);
        Buffer.BlockCopy(Magic, 0, block, 8 + pair.Length + 8, 16);
        return block;
    }

    static byte[] ComputeDigest(byte[] apk, int entriesEnd, int cdOffset, int cdSize, byte[] eocd)
    {
        var chunks = new List<byte[]>();
        AddChunks(chunks, apk.AsSpan(0, entriesEnd));
        AddChunks(chunks, apk.AsSpan(cdOffset, cdSize));
        AddChunks(chunks, eocd);

        var all = new byte[chunks.Count * 32];
        for (int i = 0; i < chunks.Count; i++) Buffer.BlockCopy(chunks[i], 0, all, i * 32, 32);

        var head = new byte[5];
        head[0] = 0x5a;
        BinaryPrimitives.WriteUInt32LittleEndian(head.AsSpan(1), (uint)chunks.Count);
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        sha.AppendData(head);
        sha.AppendData(all);
        return sha.GetHashAndReset();
    }

    static void AddChunks(List<byte[]> acc, ReadOnlySpan<byte> section)
    {
        const int Chunk = 1024 * 1024;
        int off = 0;
        if (section.Length == 0)
        {
            var h0 = new byte[5];
            h0[0] = 0xa5;
            using var s0 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            s0.AppendData(h0);
            acc.Add(s0.GetHashAndReset());
            return;
        }
        while (off < section.Length)
        {
            int len = Math.Min(Chunk, section.Length - off);
            var head = new byte[5];
            head[0] = 0xa5;
            BinaryPrimitives.WriteUInt32LittleEndian(head.AsSpan(1), (uint)len);
            using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            sha.AppendData(head);
            sha.AppendData(section.Slice(off, len));
            acc.Add(sha.GetHashAndReset());
            off += len;
        }
    }

    static int FindEocd(byte[] apk)
    {
        for (int i = apk.Length - 22; i >= 0 && i > apk.Length - 22 - 65535; i--)
            if (apk[i] == 0x50 && apk[i + 1] == 0x4b && apk[i + 2] == 0x05 && apk[i + 3] == 0x06)
                return i;
        return -1;
    }

    static byte[] U32(uint v)
    {
        var b = new byte[4];
        BinaryPrimitives.WriteUInt32LittleEndian(b, v);
        return b;
    }

    static byte[] LenPrefixed(byte[] data)
    {
        var b = new byte[4 + data.Length];
        BinaryPrimitives.WriteUInt32LittleEndian(b, (uint)data.Length);
        Buffer.BlockCopy(data, 0, b, 4, data.Length);
        return b;
    }

    static byte[] Concat(params byte[][] parts)
    {
        int total = parts.Sum(p => p.Length);
        var b = new byte[total];
        int off = 0;
        foreach (var p in parts) { Buffer.BlockCopy(p, 0, b, off, p.Length); off += p.Length; }
        return b;
    }
}
