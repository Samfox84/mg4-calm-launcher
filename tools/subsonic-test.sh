#!/bin/sh
# subsonic-test.sh — unit tests for the make-or-break Subsonic logic (auth token, URL, search).
# Pure JVM, no Android, no network. Wired into build.sh.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

cat > "$OUT/SubsonicParseTest.java" <<'JAVA'
import com.mg4.launcher.svc.SubsonicParse;

public class SubsonicParseTest {
    static int fails = 0;
    static void eq(String what, String got, String want) {
        if (got == null ? want != null : !got.equals(want)) { System.out.println("  FAIL " + what + ": got[" + got + "] want[" + want + "]"); fails++; }
        else System.out.println("  ok   " + what);
    }
    static void tru(String what, boolean b) {
        if (!b) { System.out.println("  FAIL " + what); fails++; } else System.out.println("  ok   " + what);
    }
    public static void main(String[] a) {
        // Official Subsonic API doc vector: password "sesame", salt "c19b2d" -> token 26719a1196d2a940705a59634eb18eab
        eq("auth token (doc vector)", SubsonicParse.authToken("sesame", "c19b2d"), "26719a1196d2a940705a59634eb18eab");
        eq("md5 empty", SubsonicParse.md5(""), "d41d8cd98f00b204e9800998ecf8427e");

        // URL normalisation
        eq("bare host:port", SubsonicParse.normBase("192.168.1.199:4533"), "http://192.168.1.199:4533");
        eq("trailing slash", SubsonicParse.normBase("https://music.example.com/"), "https://music.example.com");
        eq("strips /rest", SubsonicParse.normBase("http://nas.local/rest"), "http://nas.local");
        eq("strips /rest/", SubsonicParse.normBase("http://nas.local/rest/"), "http://nas.local");
        eq("keeps https", SubsonicParse.normBase("https://nav.me"), "https://nav.me");
        eq("empty", SubsonicParse.normBase(""), "");
        eq("null", SubsonicParse.normBase(null), "");

        // Search matching (case-insensitive across title/artist/album)
        tru("match title", SubsonicParse.matches("Paranoid Android", "Radiohead", "OK Computer", "android"));
        tru("match artist", SubsonicParse.matches("Karma Police", "Radiohead", "OK Computer", "radio"));
        tru("match album", SubsonicParse.matches("No Surprises", "Radiohead", "OK Computer", "computer"));
        tru("no match", !SubsonicParse.matches("Song", "Artist", "Album", "zzz"));
        tru("empty query no match", !SubsonicParse.matches("Song", "Artist", "Album", ""));
        tru("null fields safe", !SubsonicParse.matches(null, null, null, "x"));

        if (fails > 0) { System.out.println("SUBSONIC TESTS FAILED: " + fails); System.exit(1); }
        System.out.println("Subsonic logic tests: all passed");
    }
}
JAVA

javac -d "$OUT" "$ROOT/app/java/com/mg4/launcher/svc/SubsonicParse.java" "$OUT/SubsonicParseTest.java"
java -cp "$OUT" SubsonicParseTest
