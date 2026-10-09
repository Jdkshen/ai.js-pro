// @engine quickjs
// Reusable experiment: SIFT locates the board; the existing glyph recognizer
// classifies all 90 intersections and checks ink color for shared glyphs.
// Ground-truth FENs are deliberately not inputs to this module.
"use strict";

function create(options) {
    var api=options.images, glyph=options.glyph, host=options.host;
    var calibration=options.calibration, templates=glyph.loadTemplates(host,options.templateDir);
    var matcher=null, closed=false, geometryMemo=null;
    try {
        matcher=api.createSiftMatcher(options.boardTemplate,{
            scale:0.5,templateScale:0.5,fast:true,track:true,trackPadding:24
        });
    } catch(error) {glyph.releaseTemplates(templates);throw error;}
    function gridFrom(location) {
        var tl=location.topLeft,tr=location.topRight,bl=location.bottomLeft,br=location.bottomRight;
        var width=calibration.width,height=calibration.height;
        var sx=(tr.x-tl.x)/width, sy=(bl.y-tl.y)/height;
        // The existing glyph recognizer accepts an axis-aligned lattice.
        // Reject rotated/perspective boards instead of silently using wrong ROIs.
        var tolerance=Math.max(3,Math.min(Math.abs(tr.x-tl.x),Math.abs(bl.y-tl.y))*0.008);
        if(!(sx>0 && sy>0) || Math.abs(tr.y-tl.y)>tolerance || Math.abs(bl.x-tl.x)>tolerance
            || Math.abs(br.x-tr.x)>tolerance || Math.abs(br.y-bl.y)>tolerance) return null;
        return {originX:tl.x+calibration.originX*sx,
            originY:tl.y+calibration.originY*sy,
            stepX:calibration.stepX*sx,stepY:calibration.stepY*sy};
    }
    function releaseAnchors(memo) {
        if(memo)for(var i=0;i<memo.anchors.length;i++)memo.anchors[i].image.recycle();
    }
    function remember(image,location,grid) {
        var anchors=[],sx=(location.topRight.x-location.topLeft.x)/calibration.width;
        var sy=(location.bottomLeft.y-location.topLeft.y)/calibration.height;
        try {
            for(var i=0;i<(calibration.anchors||[]).length;i++) {
                var a=calibration.anchors[i],x=Math.round(location.topLeft.x+a[0]*sx);
                var y=Math.round(location.topLeft.y+a[1]*sy);
                var w=Math.max(8,Math.round(a[2]*sx)),h=Math.max(8,Math.round(a[3]*sy));
                anchors.push({x:x,y:y,width:w,height:h,image:host.clip(image,x,y,w,h)});
            }
        } catch(error) {releaseAnchors({anchors:anchors});throw error;}
        releaseAnchors(geometryMemo);
        geometryMemo={location:location,grid:grid,anchors:anchors,age:0};
    }
    function sameGeometry(image) {
        if(!geometryMemo || !geometryMemo.anchors.length || geometryMemo.age>=30)return false;
        var size=host.sizeOf(image);
        for(var i=0;i<geometryMemo.anchors.length;i++) {
            var a=geometryMemo.anchors[i],crop=null;
            if(a.x<0||a.y<0||a.x+a.width>size.width||a.y+a.height>size.height)return false;
            try {
                crop=host.clip(image,a.x,a.y,a.width,a.height);
                var result=host.matchTemplate(crop,a.image,{threshold:0.97,max:1});
                if(!result || !result.matches || !result.matches.length
                    || result.matches[0].similarity<0.97)return false;
            } finally {if(crop)crop.recycle();}
        }
        return true;
    }
    return {
        templatePrepareMs:matcher.templatePrepareMs,
        read:function(image) {
            if(closed)throw new Error('Chess pipeline is closed');
            var start=host.nowMono(), geometryCacheHit=sameGeometry(image);
            var found=geometryCacheHit?geometryMemo.location:matcher.match(image),locatedAt=host.nowMono();
            if(!found)return {ok:false,reason:'board-not-found',locateMs:locatedAt-start,
                elapsedMs:locatedAt-start,chars:null};
            var grid=geometryCacheHit?geometryMemo.grid:gridFrom(found);
            if(!grid)return {ok:false,reason:'unsupported-board-perspective',location:found,
                locateMs:locatedAt-start,elapsedMs:host.nowMono()-start,chars:null};
            var prep=glyph.prepareFor(host,templates,glyph.scaleFor(grid));
            var recognized=glyph.recognize(host,image,grid,{
                templates:prep.templates,TW:prep.TW,ANCHOR_DY:prep.ANCHOR_DY,
                coarseTemplates:prep.coarse,collect:true
            });
            var kings=recognized.chars && recognized.chars.filter(function(c){return c==='K';}).length;
            var generals=recognized.chars && recognized.chars.filter(function(c){return c==='k';}).length;
            var usable=!recognized.error && recognized.unknown===0 && kings===1 && generals===1;
            // Only geometry is reused. Every frame above is classified again.
            // Unknown/animated frames never become new anchor references.
            if(geometryCacheHit)geometryMemo.age++;
            else if(usable)remember(image,found,grid);
            return {ok:!recognized.error,reason:recognized.error||null,location:found,grid:grid,
                usable:usable,geometryCacheHit:geometryCacheHit,
                locateMs:locatedAt-start,recognizeMs:host.nowMono()-locatedAt,
                elapsedMs:host.nowMono()-start,chars:recognized.chars,
                fen:recognized.chars?glyph.charsToFen(recognized.chars):null,
                unknown:recognized.unknown,inked:recognized.inked,
                profile:recognized.profile,rescues:recognized.rescues,notes:recognized.notes};
        },
        close:function() {
            if(closed)return;
            closed=true;
            try {matcher.recycle();} finally {
                try {glyph.releaseTemplates(templates);} finally {releaseAnchors(geometryMemo);geometryMemo=null;}
            }
        }
    };
}
module.exports={create:create};
