// @engine quickjs
"use strict";
// Fault injection checks ownership, including failures after earlier allocations succeeded.
const assert = require("assert");
const glyph = require("../src/vision/glyph-recognizer.js");
function harness(failAt) {
    const live = new Set();
    let calls = 0;
    function allocate() {
        if (++calls === failAt) throw new Error("injected allocation failure");
        const image = { recycle() { live.delete(image); } };
        live.add(image);
        return image;
    }
    return { live, failNext(n) { failAt=calls+n; }, host: {read:allocate, grayscale:allocate, resize:allocate, clip:allocate,
        threshold:allocate, nowMono(){return 1;},sizeOf(){return {width:1080,height:2400};},
        countColor(){return 0;}} };
}
for (let failure=1;failure<=22;failure++) {
    const h=harness();
    const originals=glyph.loadTemplates(h.host,"templates");
    const old=glyph.prepareFor(h.host,originals,1);
    const before=h.live.size;
    h.failNext(failure);
    assert.throws(()=>glyph.prepareFor(h.host,originals,0.8));
    assert.strictEqual(h.live.size,before,"failed cache replacement leaked at "+failure);
    assert.strictEqual(glyph.prepareFor(h.host,originals,1).coarse,old.coarse,"old cache lost");
    glyph.releaseTemplates(originals);
    assert.strictEqual(h.live.size,0);
}
for (let failure=1;failure<=3;failure++) {
    const h=harness();
    let counts=0;
    h.host.countColor=()=>{ if(++counts===failure)throw new Error("count failure");return 1000; };
    assert.throws(()=>glyph.recognize(h.host,{}, {originX:100,originY:100,stepX:100,stepY:100},
        {templates:[{name:"将",image:{}}],coarseTemplates:[{}]}));
    assert.strictEqual(h.live.size,0,"count/face failure leaked at "+failure);
}
{
    const h=harness(5); // gray, half, clip, threshold, then face threshold
    h.host.countColor=()=>1000;
    assert.throws(()=>glyph.recognize(h.host,{}, {originX:100,originY:100,stepX:100,stepY:100},
        {templates:[{name:"将",image:{}}],coarseTemplates:[{}]}));
    assert.strictEqual(h.live.size,0,"face threshold allocation leaked");
}
for (let failure=1; failure<=22; failure++) {
    const h=harness(failure);
    assert.throws(()=>glyph.loadTemplates(h.host,"templates"));
    assert.strictEqual(h.live.size,0,"partial template load leaked at "+failure);
}
{
    const h=harness();
    const originals=glyph.loadTemplates(h.host,"templates");
    for (let i=0;i<80;i++) {
        const result=glyph.prepareFor(h.host,originals,i%2 ? 1 : 0.8);
        assert(h.live.size <= originals.length*3,"template resize cache accumulated");
        assert.strictEqual(glyph.prepareFor(h.host,originals,i%2 ? 1 : 0.8).templates,result.templates);
    }
    glyph.releaseTemplates(originals);
    assert.strictEqual(h.live.size,0,"template shutdown leaked");
}
for(let failure=1;failure<=5;failure++) {
    const h=harness(failure);
    assert.throws(()=>glyph.recognize(h.host,{}, {originX:100,originY:100,stepX:100,stepY:100},
        {templates:[{name:"将",image:{}}],coarseTemplates:[{}]}));
    assert.strictEqual(h.live.size,0,"frame/crop failure leaked at "+failure);
}
{
    const h=harness();
    h.host.matchTemplate=()=>({matches:[]});
    const originals=glyph.loadTemplates(h.host,"templates");
    const retained={};
    for (let i=0;i<80;i++) {
        const scale=i%2 ? 1 : 0.8;
        const prepared=glyph.prepareFor(h.host,originals,scale);
        glyph.rescueMatch(h.host,{},500,500,4,4,prepared.templates,prepared,{width:1080,height:2400});
        if (i>2) assert.strictEqual(h.live.size,retained[scale],"rescue scale cache accumulated");
        retained[scale]=h.live.size;
    }
    glyph.releaseTemplates(originals);
    assert.strictEqual(h.live.size,0,"rescue copies survived template release");
}
{
    const h=harness();
    for(let i=0;i<100;i++) {
        glyph.recognize(h.host,{}, {originX:100,originY:100,stepX:100,stepY:100},
            {templates:[{name:"将",image:{}}],coarseTemplates:[{}]});
        assert.strictEqual(h.live.size,0,"normal scan retained temporary frames");
    }
}
console.log("IMAGE LIFETIME OK: template failures, 80 cache replacements, allocation failures, 100 scans");
