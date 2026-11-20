#!/bin/sh
# Smoke-test the map page's inline JavaScript under Node with browser stubs.
# Catches runtime JS errors (ReferenceError/TypeError at top level or in init) that javac/lint cannot see.
# Usage: tools/mapjscheck.sh app/assets/map/map-vector.html
command -v node >/dev/null 2>&1 || { echo "   (node assente: salto il controllo JS mappa)"; exit 0; }
STUBS=$(mktemp); CODE=$(mktemp)
cat > "$STUBS" <<'JS'
globalThis.console=console; const noop=function(){};
const mapObj={setView:()=>mapObj,on:noop,panTo:noop,setZoom:noop,getZoom:()=>16,project:()=>({x:0,y:0}),invalidateSize:noop,addLayer:noop};
globalThis.window=globalThis;
globalThis.location={search:"?dim=1",origin:"http://127.0.0.1:1234"};
globalThis.document={getElementById:()=>({style:{},addEventListener:noop,textContent:""}),addEventListener:noop};
globalThis.AndroidMap={status:noop};
globalThis.L={map:()=>mapObj,control:{zoom:()=>({addTo:noop})},marker:()=>({addTo:()=>({}),setLatLng:noop,getLatLng:()=>({})}),divIcon:()=>({}),tileLayer:()=>({on:noop,addTo:noop})};
globalThis.protomapsL={leafletLayer:()=>({on:noop,addTo:noop,queryTileFeaturesDebug:()=>new Map()})};
globalThis.addEventListener=noop;
globalThis.setTimeout=(f)=>{try{typeof f==='function'&&f();}catch(e){console.error('map: fatal',e.message);}};
JS
python3 - "$1" > "$CODE" <<'PY'
import re,sys
h=open(sys.argv[1],encoding="utf-8").read()
sys.stdout.write(re.findall(r"<script>(.*?)</script>",h,re.S)[-1])
PY
node -e "require('$STUBS'); const code=require('fs').readFileSync('$CODE','utf8'); let bad=false; const oe=console.error; console.error=function(){const m=String(arguments[0]); if(m.indexOf('map: fatal')>=0||m.indexOf('Error')>=0)bad=true; oe.apply(console,arguments);}; try{(0,eval)(code);}catch(e){console.error('TOP-LEVEL:',e.message);process.exit(1);} process.exit(bad?1:0);"
RC=$?; rm -f "$STUBS" "$CODE"; exit $RC
