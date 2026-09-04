/*
 * Reconstructed from the archived Debug APK (CFR 0.152 output of tiny-sign-0.9.jar,
 * identical to the original pxb.android.tinysign.TinySign implementation).
 */

package com.jdkshen.aijspro.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.security.DigestOutputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class TinySign {
    private static byte[] dBase64(String data) throws UnsupportedEncodingException {
        return Base64.decodeBase64((byte[])data.getBytes("UTF-8"));
    }

    private static void doDir(String prefix, File dir, ZipOutputStream zos, DigestOutputStream dos, Manifest m) throws IOException {
        zos.putNextEntry(new ZipEntry(prefix));
        zos.closeEntry();
        for (File f : dir.listFiles()) {
            if (f.isFile()) {
                TinySign.doFile(prefix + f.getName(), f, zos, dos, m);
                continue;
            }
            TinySign.doDir(prefix + f.getName() + "/", f, zos, dos, m);
        }
    }

    private static void doFile(String name, File f, ZipOutputStream zos, DigestOutputStream dos, Manifest m) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        FileInputStream fis = FileUtils.openInputStream((File)f);
        IOUtils.copy((InputStream)fis, (OutputStream)dos);
        IOUtils.closeQuietly((InputStream)fis);
        byte[] digets = dos.getMessageDigest().digest();
        zos.closeEntry();
        Attributes attr = new Attributes();
        attr.putValue("SHA1-Digest", TinySign.eBase64(digets));
        m.getEntries().put(name, attr);
    }

    private static String eBase64(byte[] data) {
        return new String(Base64.encodeBase64((byte[])data));
    }

    private static Manifest generateSF(Manifest manifest) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        MessageDigest md = MessageDigest.getInstance("SHA1");
        PrintStream print = new PrintStream(new DigestOutputStream(new OutputStream(){

            @Override
            public void write(byte[] arg0) throws IOException {
            }

            @Override
            public void write(byte[] arg0, int arg1, int arg2) throws IOException {
            }

            @Override
            public void write(int arg0) throws IOException {
            }
        }, md), true, "UTF-8");
        Manifest sf = new Manifest();
        Map<String, Attributes> entries = manifest.getEntries();
        for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
            print.print("Name: " + entry.getKey() + "\r\n");
            for (Map.Entry<Object, Object> att : entry.getValue().entrySet()) {
                print.print(att.getKey() + ": " + att.getValue() + "\r\n");
            }
            print.print("\r\n");
            print.flush();
            Attributes sfAttr = new Attributes();
            sfAttr.putValue("SHA1-Digest", TinySign.eBase64(md.digest()));
            sf.getEntries().put(entry.getKey(), sfAttr);
        }
        return sf;
    }

    private static Signature instanceSignature() throws Exception {
        byte[] data = TinySign.dBase64("MIIBVgIBADANBgkqhkiG9w0BAQEFAASCAUAwggE8AgEAAkEAoiZSqWnFDHA5sXKoDiUUO9JuL7cm/2dCck5MKumVvv+WfSg0jsovnywsFN0pifmdRSLmOdUkh0d0J+tOnSgtsQIDAQABAkEAihag5u3Qhds9BsViIUmqhZebhr8vUuqZR8cuTo1GnbSoOHIPbAgD3J8TDbC/CVqae8NrgwLp325Pem1Tuof/0QIhAN1hqft1K307bsljgw3iYKopGVZBHRXsjRnNL4edV9QrAiEAu4F+XtS1wohGLz5QtfuMFsQNo4l31mCjt6WpBDmSi5MCIQCB++YijxmJ3mueM5+vd0vqnVcTHghF5y6yB5fwuKHpIQIgInnS1Hjj2prX3MPmby+LOHxfzZvvDtnCAHhTNVWonkUCIQCvV8l+SpL6Vh1nQ/2EKFJo2dbZB3wKG/BEYsFkPFbn9w==");
        KeyFactory rSAKeyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = rSAKeyFactory.generatePrivate(new PKCS8EncodedKeySpec(data));
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(privateKey);
        return signature;
    }

    public static void sign(File dir, OutputStream out) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(out);
        zos.putNextEntry(new ZipEntry("META-INF/"));
        zos.closeEntry();
        Manifest manifest = new Manifest();
        String sha1Manifest = TinySign.writeMF(dir, manifest, zos);
        Manifest sf = TinySign.generateSF(manifest);
        byte[] sign = TinySign.writeSF(zos, sf, sha1Manifest);
        TinySign.writeRSA(zos, sign);
        IOUtils.closeQuietly((OutputStream)zos);
    }

    private static String writeMF(File dir, Manifest manifest, ZipOutputStream zos) throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance("SHA1");
        DigestOutputStream dos = new DigestOutputStream(zos, md);
        TinySign.zipAndSha1(dir, zos, dos, manifest);
        Attributes main = manifest.getMainAttributes();
        main.putValue("Manifest-Version", "1.0");
        main.putValue("Created-By", "tiny-sign-" + TinySign.class.getPackage().getImplementationVersion());
        zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        manifest.write(dos);
        zos.closeEntry();
        return TinySign.eBase64(md.digest());
    }

    private static void writeRSA(ZipOutputStream zos, byte[] sign) throws IOException {
        zos.putNextEntry(new ZipEntry("META-INF/CERT.RSA"));
        zos.write(TinySign.dBase64("MIIB5gYJKoZIhvcNAQcCoIIB1zCCAdMCAQExCzAJBgUrDgMCGgUAMAsGCSqGSIb3DQEHAaCCATYwggEyMIHdoAMCAQICBCunMokwDQYJKoZIhvcNAQELBQAwDzENMAsGA1UEAxMEVGVzdDAeFw0xMjA0MjIwODQ1NDdaFw0xMzA0MjIwODQ1NDdaMA8xDTALBgNVBAMTBFRlc3QwXDANBgkqhkiG9w0BAQEFAANLADBIAkEAoiZSqWnFDHA5sXKoDiUUO9JuL7cm/2dCck5MKumVvv+WfSg0jsovnywsFN0pifmdRSLmOdUkh0d0J+tOnSgtsQIDAQABoyEwHzAdBgNVHQ4EFgQUVL2yOinUwpARE1tOPxc1bf4WrTgwDQYJKoZIhvcNAQELBQADQQAnj/eZwhqwb2tgSYNvgRo5bBNNCpJbQ4alEeP/MLSIWf2nZpAix8T3oS9X2affQtAgctPATcKQaiH2B4L7FKlVMXoweAIBATAXMA8xDTALBgNVBAMTBFRlc3QCBCunMokwCQYFKw4DAhoFADANBgkqhkiG9w0BAQEFAARA"));
        zos.write(sign);
        zos.closeEntry();
    }

    private static byte[] writeSF(ZipOutputStream zos, Manifest sf, String sha1Manifest) throws Exception {
        Signature signature = TinySign.instanceSignature();
        zos.putNextEntry(new ZipEntry("META-INF/CERT.SF"));
        SignatureOutputStream out = new SignatureOutputStream(zos, signature);
        out.write("Signature-Version: 1.0\r\n".getBytes("UTF-8"));
        out.write(("Created-By: tiny-sign-" + TinySign.class.getPackage().getImplementationVersion() + "\r\n").getBytes("UTF-8"));
        out.write("SHA1-Digest-Manifest: ".getBytes("UTF-8"));
        out.write(sha1Manifest.getBytes("UTF-8"));
        out.write(13);
        out.write(10);
        sf.write(out);
        zos.closeEntry();
        return signature.sign();
    }

    private static void zipAndSha1(File dir, ZipOutputStream zos, DigestOutputStream dos, Manifest m) throws NoSuchAlgorithmException, IOException {
        for (File f : dir.listFiles()) {
            if (f.getName().startsWith("META-INF")) continue;
            if (f.isFile()) {
                TinySign.doFile(f.getName(), f, zos, dos, m);
                continue;
            }
            TinySign.doDir(f.getName() + "/", f, zos, dos, m);
        }
    }

    private static class SignatureOutputStream
    extends FilterOutputStream {
        private Signature mSignature;

        public SignatureOutputStream(OutputStream out, Signature sig) {
            super(out);
            this.mSignature = sig;
        }

        @Override
        public void write(byte[] buffer) throws IOException {
            try {
                this.mSignature.update(buffer);
            }
            catch (SignatureException e) {
                throw new IOException("SignatureException: " + e);
            }
            this.out.write(buffer);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                this.mSignature.update(b, off, len);
            }
            catch (SignatureException e) {
                throw new IOException("SignatureException: " + e);
            }
            this.out.write(b, off, len);
        }

        @Override
        public void write(int b) throws IOException {
            try {
                this.mSignature.update((byte)b);
            }
            catch (SignatureException e) {
                throw new IOException("SignatureException: " + e);
            }
            this.out.write(b);
        }
    }
}

