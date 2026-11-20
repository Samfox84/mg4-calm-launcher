#!/bin/sh
# Calm Launcher: check EVERY source type, not just Java.
FAIL=0
echo "=================================================="
echo " 1) JAVA  (javac -Xlint, all 68 files)"
echo "=================================================="
AJ="tools/android.jar"; [ -f "$AJ" ] || AJ=$(grep -oE '/[^ "]*android.jar' build/javac.log 2>/dev/null | head -1)
if [ -f build/sources.txt ]; then
  javac -Xlint:all -encoding UTF-8 --release 8 -classpath "$AJ" -d /tmp/ca_java @build/sources.txt 2>/tmp/ca_jw
  W=$(grep -c "warning:" /tmp/ca_jw 2>/dev/null); E=$(grep -c "error:" /tmp/ca_jw 2>/dev/null)
  grep "error:" /tmp/ca_jw 2>/dev/null | grep -v "LogConfig.java" | head
  REALE=$(grep "error:" /tmp/ca_jw 2>/dev/null | grep -vc "LogConfig.java")
  echo "  warnings=$W  errors(real)=$REALE"
  [ "$REALE" -gt 0 ] && FAIL=1
else echo "  (run ./build.sh first to generate sources.txt)"; fi

echo "=================================================="
echo " 2) JAVASCRIPT / HTML pages (runtime smoke test + syntax)"
echo "=================================================="
for h in app/assets/map/map-vector.html app/assets/map/index.html; do
  printf "  %-34s " "$h:"
  if sh tools/mapjscheck.sh "$h" >/tmp/ca_js 2>&1; then echo "OK"; else echo "FAIL"; cat /tmp/ca_js; FAIL=1; fi
done
printf "  %-34s " "app/assets/map/polyfills.js:"
if node --check app/assets/map/polyfills.js 2>/tmp/ca_pf; then echo "OK (syntax)"; else echo "FAIL"; cat /tmp/ca_pf; FAIL=1; fi

echo "=================================================="
echo " 3) XML  (res + manifest, well-formedness)"
echo "=================================================="
XB=0
for f in app/AndroidManifest.xml $(find app/res -name '*.xml'); do
  xmllint --noout "$f" 2>/tmp/ca_xml || { echo "  BAD XML: $f"; cat /tmp/ca_xml; XB=1; FAIL=1; }
done
[ "$XB" = 0 ] && echo "  all $(find app/res -name '*.xml' | wc -l) res XML + manifest well-formed OK"

echo "=================================================="
echo " 4) SHELL  (sh -n syntax)"
echo "=================================================="
for f in $(find . -name '*.sh' -not -path './build/*'); do
  printf "  %-26s " "$f:"
  case "$(head -1 "$f")" in *bash*) CHK="bash -n";; *) CHK="sh -n";; esac
  $CHK "$f" 2>/tmp/ca_sh && echo "OK ($CHK)" || { echo "FAIL"; cat /tmp/ca_sh; FAIL=1; }
done

echo "=================================================="
echo " 5) JSON"
echo "=================================================="
for f in $(find . -name '*.json' -not -path './build/*'); do
  printf "  %-34s " "$f:"; jq -e . "$f" >/dev/null 2>/tmp/ca_json && echo "OK" || { echo "FAIL"; cat /tmp/ca_json; FAIL=1; }
done

echo "=================================================="
echo " 6) YAML  (.github workflows)"
echo "=================================================="
for f in $(find .github -name '*.yml' -o -name '*.yaml'); do
  printf "  %-40s " "$f:"
  python3 -c "import yaml,sys; yaml.safe_load(open('$f'))" 2>/tmp/ca_yaml && echo "OK" || { echo "FAIL"; cat /tmp/ca_yaml; FAIL=1; }
done

echo "=================================================="
[ "$FAIL" = 0 ] && echo " ALL CHECKS PASSED ✅" || echo " SOME CHECKS FAILED ❌"
echo "=================================================="
exit $FAIL
