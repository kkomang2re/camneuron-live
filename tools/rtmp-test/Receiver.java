import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

/** Minimal RTMP ingest for testing: accepts one publisher, writes FLV, prints a summary. */
public class Receiver {
    static DataInputStream in;
    static OutputStream out;
    static int inChunk = 128;
    static long bytesIn = 0;
    static Map<Integer, long[]> st = new HashMap<>(); // csid -> ts, delta, len, type, msid, ext
    static Map<Integer, ByteArrayOutputStream> partial = new HashMap<>();

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        String flvPath = a[1];
        int seconds = Integer.parseInt(a[2]);
        ServerSocket ss = new ServerSocket(port);
        System.out.println("listening " + port);
        Socket s = ss.accept();
        s.setSoTimeout(15000);
        System.out.println("client " + s.getRemoteSocketAddress());
        in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        out = new BufferedOutputStream(s.getOutputStream());
        int c0 = in.readUnsignedByte();
        byte[] c1 = new byte[1536]; in.readFully(c1);
        byte[] s1 = new byte[1536]; new Random().nextBytes(s1);
        out.write(3); out.write(s1); out.write(c1); out.flush();
        byte[] c2 = new byte[1536]; in.readFully(c2);
        System.out.println("handshake ok c0=" + c0 + " c2echo=" + Arrays.equals(c2, s1));

        DataOutputStream flv = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(flvPath)));
        flv.write(new byte[]{'F','L','V',1,5,0,0,0,9,0,0,0,0});
        long end = System.currentTimeMillis() + seconds * 1000L;
        int video = 0, audio = 0, keys = 0, seqV = 0, seqA = 0, badNal = 0;
        long lastV = -1, lastA = -1, firstV = -1; int nonMonoV = 0, nonMonoA = 0;
        long videoBytes = 0, audioBytes = 0;
        List<Long> keyTs = new ArrayList<>();
        while (System.currentTimeMillis() < end) {
            long[] m = new long[3]; byte[] p = read(m);
            int type = (int) m[0]; long ts = m[1];
            if (type == 1) { inChunk = ByteBuffer.wrap(p).getInt(); System.out.println("client chunk size " + inChunk); continue; }
            if (type == 20) {
                List<Object> v = amf(p);
                System.out.println("cmd " + v.get(0) + " " + (v.size() > 1 ? v.get(1) : ""));
                String n = (String) v.get(0); Object txn = v.get(1);
                if (n.equals("connect")) {
                    System.out.println("  connect obj " + v.get(2));
                    send(2, 5, 0, ByteBuffer.allocate(4).putInt(2500000).array());
                    send(2, 6, 0, new byte[]{0, 0x26, 0x25, (byte) 0xA0, 2});
                    send(3, 20, 0, enc("_result", txn, map("fmsVer", "FMS/3,0,1,123"), map("code", "NetConnection.Connect.Success")));
                } else if (n.equals("createStream")) {
                    send(3, 20, 0, enc("_result", txn, null, 1.0));
                } else if (n.equals("publish")) {
                    System.out.println("  publish name length=" + ((String) v.get(3)).length() + " type=" + v.get(4));
                    send(3, 20, 1, enc("onStatus", 0.0, null, map("level", "status", "code", "NetStream.Publish.Start")));
                }
                continue;
            }
            if (type == 18) { System.out.println("data " + amf(p)); }
            if (type == 3) continue;
            if (type == 8 || type == 9 || type == 18) {
                flv.writeByte(type); flv.write(new byte[]{(byte)(p.length>>16),(byte)(p.length>>8),(byte)p.length});
                flv.write(new byte[]{(byte)(ts>>16),(byte)(ts>>8),(byte)ts,(byte)(ts>>24)}); flv.write(new byte[]{0,0,0});
                flv.write(p); flv.writeInt(11 + p.length);
            }
            if (type == 9) {
                if (p[1] == 0) {
                    seqV++;
                    System.out.printf("AVC seq header at %d: profile %d compat %d level %d, sps %d bytes%n", ts, p[6] & 0xFF, p[7] & 0xFF, p[8] & 0xFF, ((p[11] & 0xFF) << 8) | (p[12] & 0xFF));
                    continue;
                }
                video++; videoBytes += p.length;
                if (firstV < 0) firstV = ts;
                if ((p[0] >> 4) == 1) { keys++; keyTs.add(ts); }
                if (ts < lastV) nonMonoV++;
                lastV = ts;
                int off = 5;
                while (off < p.length) {
                    if (off + 4 > p.length) { badNal++; break; }
                    int len = ByteBuffer.wrap(p, off, 4).getInt();
                    if (len <= 0 || off + 4 + len > p.length) { badNal++; break; }
                    off += 4 + len;
                }
            } else if (type == 8) {
                if (p[1] == 0) { seqA++; System.out.printf("AAC seq header at %d: %02x %02x%n", ts, p[2], p[3]); continue; }
                audio++; audioBytes += p.length;
                if (ts < lastA) nonMonoA++;
                lastA = ts;
            }
        }
        flv.close();
        double secs = (lastV - firstV) / 1000.0;
        System.out.printf("video frames %d (key %d, seq headers %d, bad NAL framing %d, non-monotonic %d), span %.1fs, fps %.1f, %.0f kbps%n",
            video, keys, seqV, badNal, nonMonoV, secs, video / Math.max(secs, 0.001), videoBytes * 8 / Math.max(secs, 0.001) / 1000);
        System.out.printf("audio frames %d (seq headers %d, non-monotonic %d), last ts %d, %.0f kbps, frames/s %.1f%n",
            audio, seqA, nonMonoA, lastA, audioBytes * 8 / Math.max(secs, 0.001) / 1000, audio / Math.max(secs, 0.001));
        System.out.println("keyframe ts: " + keyTs);
        System.out.println("last video ts " + lastV + " last audio ts " + lastA + " (A/V gap " + (lastV - lastA) + " ms)");
        s.close(); ss.close();
    }

    static Map<String, Object> map(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }

    static void send(int csid, int type, int msid, byte[] p) throws IOException {
        out.write(csid); out.write(0); out.write(0); out.write(0);
        out.write(p.length >> 16); out.write(p.length >> 8); out.write(p.length); out.write(type);
        out.write(msid); out.write(0); out.write(0); out.write(0);
        int off = 0;
        while (true) { int n = Math.min(128, p.length - off); out.write(p, off, n); off += n; if (off >= p.length) break; out.write(0xC0 | csid); }
        out.flush();
    }

    static int u8() throws IOException { bytesIn++; return in.readUnsignedByte(); }
    static int u24() throws IOException { return (u8() << 16) | (u8() << 8) | u8(); }
    static long u32() throws IOException { return ((long) u8() << 24) | (u8() << 16) | (u8() << 8) | u8(); }

    static byte[] read(long[] meta) throws IOException {
        while (true) {
            int b = u8(); int fmt = b >> 6; int csid = b & 0x3f;
            if (csid == 0) csid = 64 + u8(); else if (csid == 1) csid = 64 + u8() + (u8() << 8);
            long[] s = st.computeIfAbsent(csid, k -> new long[6]);
            ByteArrayOutputStream buf = partial.get(csid);
            boolean starting = buf == null;
            if (fmt == 0) { long t = u24(); s[2] = u24(); s[3] = u8(); s[4] = u8() | (u8() << 8) | (u8() << 16) | (u8() << 24); s[5] = t == 0xFFFFFF ? 1 : 0; s[0] = s[5] == 1 ? u32() : t; s[1] = 0; }
            else if (fmt == 1 || fmt == 2) { long d = u24(); if (fmt == 1) { s[2] = u24(); s[3] = u8(); } s[5] = d == 0xFFFFFF ? 1 : 0; s[1] = s[5] == 1 ? u32() : d; s[0] += s[1]; }
            else { if (s[5] == 1) u32(); if (starting) s[0] += s[1]; }
            if (starting) { buf = new ByteArrayOutputStream(); partial.put(csid, buf); }
            int n = (int) Math.min(inChunk, s[2] - buf.size());
            byte[] chunk = new byte[n]; in.readFully(chunk); bytesIn += n; buf.write(chunk);
            if (buf.size() >= s[2]) { partial.remove(csid); meta[0] = s[3]; meta[1] = s[0]; return buf.toByteArray(); }
        }
    }

    static List<Object> amf(byte[] p) { ByteBuffer b = ByteBuffer.wrap(p); List<Object> l = new ArrayList<>(); try { while (b.hasRemaining()) l.add(val(b)); } catch (Exception e) {} return l; }
    static Object val(ByteBuffer b) {
        int t = b.get() & 0xFF;
        switch (t) {
            case 0: return b.getDouble();
            case 1: return b.get() != 0;
            case 2: { byte[] s = new byte[b.getShort() & 0xFFFF]; b.get(s); return new String(s); }
            case 3: case 8: { if (t == 8) b.getInt(); Map<String, Object> m = new LinkedHashMap<>(); while (true) { byte[] k = new byte[b.getShort() & 0xFFFF]; b.get(k); if (k.length == 0 && b.get(b.position()) == 9) { b.get(); return m; } m.put(new String(k), val(b)); } }
            case 5: case 6: return null;
            default: throw new RuntimeException("type " + t);
        }
    }

    static byte[] enc(Object... vals) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (Object v : vals) w(o, v); return o.toByteArray(); }
    static void w(ByteArrayOutputStream o, Object v) throws IOException {
        if (v == null) o.write(5);
        else if (v instanceof Number) { o.write(0); o.write(ByteBuffer.allocate(8).putDouble(((Number) v).doubleValue()).array()); }
        else if (v instanceof String) { byte[] s = ((String) v).getBytes(); o.write(2); o.write(s.length >> 8); o.write(s.length); o.write(s); }
        else if (v instanceof Map) { o.write(3); for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) { byte[] k = ((String) e.getKey()).getBytes(); o.write(k.length >> 8); o.write(k.length); o.write(k); w(o, e.getValue()); } o.write(0); o.write(0); o.write(9); }
    }
}
