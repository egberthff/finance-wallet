import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Prints the minimum LOAD-segment alignment of every 64-bit .so in an APK (Play requires >= 16 KB). */
public class ElfAlign {
    public static void main(String[] args) throws Exception {
        try (ZipFile zip = new ZipFile(args[0])) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".so")) continue;
                byte[] b;
                try (InputStream in = zip.getInputStream(e)) { b = in.readAllBytes(); }
                ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                boolean is64 = b[4] == 2;
                long min = Long.MAX_VALUE;
                if (is64) {
                    long phoff = buf.getLong(0x20);
                    int phentsize = buf.getShort(0x36) & 0xffff, phnum = buf.getShort(0x38) & 0xffff;
                    for (int i = 0; i < phnum; i++) {
                        int p = (int) phoff + i * phentsize;
                        if (buf.getInt(p) == 1) min = Math.min(min, buf.getLong(p + 0x30));
                    }
                } else {
                    int phoff = buf.getInt(0x1C);
                    int phentsize = buf.getShort(0x2A) & 0xffff, phnum = buf.getShort(0x2C) & 0xffff;
                    for (int i = 0; i < phnum; i++) {
                        int p = phoff + i * phentsize;
                        if (buf.getInt(p) == 1) min = Math.min(min, buf.getInt(p + 0x1C) & 0xffffffffL);
                    }
                }
                System.out.printf("%-45s %s LOAD align=%d (%s)%n", e.getName(), is64 ? "ELF64" : "ELF32", min,
                    min >= 16384 ? "16KB OK" : (is64 ? "NOT 16KB" : "32-bit, not required"));
            }
        }
    }
}
