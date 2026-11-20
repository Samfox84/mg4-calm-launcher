// Minimal polyfills for the head unit's old WebView (pre-globalThis).
(function(){ if (typeof globalThis === 'object') return;
  try { Object.defineProperty(Object.prototype,'__gt__',{get:function(){return this},configurable:true});
    __gt__.globalThis = __gt__; delete Object.prototype.__gt__;
  } catch(e){ if (typeof window !== 'undefined') window.globalThis = window; }
})();
if (!Object.fromEntries) Object.fromEntries=function(it){var o={},a=Array.from(it);for(var i=0;i<a.length;i++)o[a[i][0]]=a[i][1];return o;};
if (!Array.prototype.flat) Object.defineProperty(Array.prototype,'flat',{configurable:true,writable:true,value:function(d){d=d===undefined?1:d;var self=this;return d<1?Array.prototype.slice.call(self):Array.prototype.reduce.call(self,function(a,v){return a.concat(Array.isArray(v)&&d>0?Array.prototype.flat.call(v,d-1):[v]);},[]);}});
if (!Array.prototype.flatMap) Object.defineProperty(Array.prototype,'flatMap',{configurable:true,writable:true,value:function(f,t){return Array.prototype.map.call(this,f,t).flat();}});
