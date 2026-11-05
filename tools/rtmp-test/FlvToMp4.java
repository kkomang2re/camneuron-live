import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Test helper: copies the H.264 video of an FLV (from Receiver) into a plain MP4 so a normal player can show it.
 * Usage: java FlvToMp4.java in.flv out.mp4 [startMs] [endMs]
 * Starts at the first keyframe at or after startMs. Stops at endMs or at the next AVC sequence header.
 * Also prints per-second video/audio kbps from the FLV.
 */
public class FlvToMp4 {
    public static void main(String[] a) throws Exception {
        byte[] flv = Files.readAllBytes(Paths.get(a[0]));
        long startMs = a.length > 2 ? Long.parseLong(a[2]) : 0;
        long endMs = a.length > 3 ? Long.parseLong(a[3]) : Long.MAX_VALUE;
        ByteBuffer b = ByteBuffer.wrap(flv);
        b.position(13);
        byte[] avcC = null;
        List<byte[]> samples = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        List<Integer> keys = new ArrayList<>();
        boolean started = false;
        TreeMap<Long, long[]> perSecond = new TreeMap<>();
        int width = 1920, height = 1080;
        while (b.remaining() >= 15) {
            int type = b.get() & 0xFF;
            int size = ((b.get() & 0xFF) << 16) | ((b.get() & 0xFF) << 8) | (b.get() & 0xFF);
            long ts = ((b.get() & 0xFF) << 16) | ((b.get() & 0xFF) << 8) | (b.get() & 0xFF) | ((long) (b.get() & 0xFF) << 24);
            b.position(b.position() + 3);
            if (b.remaining() < size + 4) break;
            byte[] data = new byte[size];
            b.get(data);
            b.getInt();
            long sec = ts / 1000;
            long[] ps = perSecond.computeIfAbsent(sec, k -> new long[3]);
            if (type == 9) { ps[0] += size; ps[2]++; }
            if (type == 8) ps[1] += size;
            if (type != 9 || size < 5) continue;
            if (data[1] == 0) {
                if (avcC == null) {
                    avcC = Arrays.copyOfRange(data, 5, data.length);
                } else if (started) {
                    break; // new camera: different config
                } else {
                    avcC = Arrays.copyOfRange(data, 5, data.length);
                }
                continue;
            }
            boolean key = (data[0] >> 4) == 1;
            if (!started) {
                if (!key || ts < startMs) continue;
                started = true;
            }
            if (ts > endMs) break;
            samples.add(Arrays.copyOfRange(data, 5, data.length));
            times.add(ts);
            if (key) keys.add(samples.size());
        }
        for (Map.Entry<Long, long[]> e : perSecond.entrySet()) {
            System.out.printf("t=%3ds video %5d kbps (%d frames) audio %4d kbps%n", e.getKey(), e.getValue()[0] * 8 / 1000, e.getValue()[2], e.getValue()[1] * 8 / 1000);
        }
        if (avcC == null || samples.isEmpty()) { System.out.println("no video"); return; }
        long duration = times.get(times.size() - 1) - times.get(0) + 33;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(out);
        byte[] ftyp = box("ftyp", cat("isom".getBytes(), i32(512), "isomiso2avc1mp41".getBytes()));
        o.write(ftyp);
        long mdatSize = 8;
        for (byte[] s : samples) mdatSize += s.length;
        o.writeInt((int) mdatSize);
        o.write("mdat".getBytes());
        int firstOffset = ftyp.length + 8;
        for (byte[] s : samples) o.write(s);

        // stts: run-length deltas
        ByteArrayOutputStream stts = new ByteArrayOutputStream();
        int entries = 0;
        long prevDelta = -1; int run = 0;
        List<long[]> runs = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            long d = i + 1 < samples.size() ? times.get(i + 1) - times.get(i) : 33;
            if (d == prevDelta) run++; else { if (run > 0) runs.add(new long[]{run, prevDelta}); prevDelta = d; run = 1; }
        }
        runs.add(new long[]{run, prevDelta});
        stts.write(i32(0)); stts.write(i32(runs.size()));
        for (long[] r : runs) { stts.write(i32((int) r[0])); stts.write(i32((int) r[1])); }
        ByteArrayOutputStream stss = new ByteArrayOutputStream();
        stss.write(i32(0)); stss.write(i32(keys.size()));
        for (int k : keys) stss.write(i32(k));
        ByteArrayOutputStream stsz = new ByteArrayOutputStream();
        stsz.write(i32(0)); stsz.write(i32(0)); stsz.write(i32(samples.size()));
        for (byte[] s : samples) stsz.write(i32(s.length));
        byte[] stsc = cat(i32(0), i32(1), i32(1), i32(samples.size()), i32(1));
        byte[] stco = cat(i32(0), i32(1), i32(firstOffset));

        byte[] avc1 = box("avc1", cat(new byte[6], i16(1), new byte[16], i16(width), i16(height),
            i32(0x00480000), i32(0x00480000), i32(0), i16(1), new byte[32], i16(0x18), i16(-1), box("avcC", avcC)));
        byte[] stsd = box("stsd", cat(i32(0), i32(1), avc1));
        byte[] stbl = box("stbl", cat(stsd, box("stts", stts.toByteArray()), box("stss", stss.toByteArray()),
            box("stsc", stsc), box("stsz", stsz.toByteArray()), box("stco", stco)));
        byte[] dinf = box("dinf", box("dref", cat(i32(0), i32(1), box("url ", i32(1)))));
        byte[] minf = box("minf", cat(box("vmhd", cat(i32(1), new byte[8])), dinf, stbl));
        byte[] hdlr = box("hdlr", cat(i32(0), i32(0), "vide".getBytes(), new byte[12], "VideoHandler\0".getBytes()));
        byte[] mdhd = box("mdhd", cat(i32(0), i32(0), i32(0), i32(1000), i32((int) duration), i16(0x55C4), i16(0)));
        byte[] mdia = box("mdia", cat(mdhd, hdlr, minf));
        byte[] matrix = cat(i32(0x10000), i32(0), i32(0), i32(0), i32(0x10000), i32(0), i32(0), i32(0), i32(0x40000000));
        byte[] tkhd = box("tkhd", cat(i32(3), i32(0), i32(0), i32(1), i32(0), i32((int) duration), new byte[8], i16(0), i16(0), i16(0), i16(0), matrix, i32(width << 16), i32(height << 16)));
        byte[] trak = box("trak", cat(tkhd, mdia));
        byte[] mvhd = box("mvhd", cat(i32(0), i32(0), i32(0), i32(1000), i32((int) duration), i32(0x10000), i16(0x100), new byte[10], matrix, new byte[24], i32(2)));
        o.write(box("moov", cat(mvhd, trak)));
        Files.write(Paths.get(a[1]), out.toByteArray());
        System.out.printf("wrote %d frames (%d keyframes) from %d ms to %d ms%n", samples.size(), keys.size(), times.get(0), times.get(times.size() - 1));
    }

    static byte[] i32(int v) { return ByteBuffer.allocate(4).putInt(v).array(); }
    static byte[] i16(int v) { return ByteBuffer.allocate(2).putShort((short) v).array(); }
    static byte[] cat(byte[]... parts) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] p : parts) o.write(p); return o.toByteArray(); }
    static byte[] box(String type, byte[] body) throws IOException { return cat(i32(body.length + 8), type.getBytes(), body); }
}
