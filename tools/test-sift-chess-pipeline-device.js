// @engine quickjs
// Generated fixture and copied glyph module live beside this device script.
var engine=typeof images.toMat==='function'?'quickjs':'rhino';
// Exercise the actual edited Rhino asset factory on the device before packaging.
if(engine==='rhino')images=require('./images-patched.js')(runtime,(function(){return this;})());
var fixture=JSON.parse(files.read('./fixture-pipeline.json'));
var glyph=require(engine==='rhino'?'./glyph-recognizer-rhino.js':'./glyph-recognizer.js');
var pipelineModule=require('./sift-chess-pipeline.js');
var rows=[],failure=null,pipeline=null,repeat=[],apiChecks=[];
function sizeOf(image) {
    return {width:typeof image.getWidth==='function'?Number(image.getWidth()):image.width,
        height:typeof image.getHeight==='function'?Number(image.getHeight()):image.height};
}
function clipImage(image,x,y,w,h) {
    if(engine!=='rhino')return images.clip(image,x,y,w,h);
    // Keep a grayscale crop single-channel; avoid Bitmap -> RGBA -> gray round trips.
    var roi=new com.stardust.autojs.core.opencv.Mat(image.getMat(),new org.opencv.core.Rect(x,y,w,h));
    try {return com.stardust.autojs.core.image.ImageWrapper.ofMat(roi.clone());}
    finally {roi.release();}
}
function matchOne(image,template,options) {
    if(engine!=='rhino')return images.matchTemplate(image,template,options);
    // The glyph recognizer already owns its pyramid. Its tiny windows need one
    // exact NCC maximum, not the legacy API's second pyramid/early acceptance.
    var src=image.getMat(),tpl=template.getMat(),roi=null,result=new org.opencv.core.Mat();
    var r=options.region,offsetX=r?r[0]:0,offsetY=r?r[1]:0;
    try {
        if(r) {roi=new org.opencv.core.Mat(src,new org.opencv.core.Rect(r[0],r[1],r[2],r[3]));src=roi;}
        org.opencv.imgproc.Imgproc.matchTemplate(src,tpl,result,org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
        var extrema=org.opencv.core.Core.minMaxLoc(result);
        if(extrema.maxVal<(options.threshold===undefined?0.9:options.threshold))return {matches:[]};
        return {matches:[{point:{x:Number(extrema.maxLoc.x)+offsetX,y:Number(extrema.maxLoc.y)+offsetY},
            similarity:Number(extrema.maxVal)}]};
    } finally {result.release();if(roi)roi.release();}
}
var host={sizeOf:sizeOf,grayscale:function(i){return images.grayscale(i);},
    clip:clipImage,
    threshold:function(i,t,m){return images.threshold(i,t,m);},
    countColor:function(i,c,o){
        var r=o.region,opts={region:[r.x,r.y,r.width,r.height],threshold:o.threshold};
        if(typeof images.countColor==='function')return images.countColor(i,c,opts);
        // The glyph gates count black in binary, single-channel images only.
        // Rhino's RGB point finder is unsuitable here; count the binary Mat
        // directly without allocating one Java/JS object per matching pixel.
        if(engine==='rhino' && c==='#000000') {
            var roi=new org.opencv.core.Mat(i.getMat(),
                new org.opencv.core.Rect(r.x,r.y,r.width,r.height));
            var channel=null;
            try {
                // Legacy clip() goes through Bitmap, expanding grayscale to
                // equal RGB + alpha. The binary RGB channels remain identical.
                if(roi.channels()!==1) {
                    channel=new org.opencv.core.Mat();
                    org.opencv.core.Core.extractChannel(roi,channel,0);
                }
                return r.width*r.height-org.opencv.core.Core.countNonZero(channel||roi);
            } finally {if(channel)channel.release();roi.release();}
        }
        throw new Error('Unsupported glyph gate image/color');
    },matchTemplate:matchOne,
    pixel:function(i,x,y){return images.pixel(i,Math.round(x),Math.round(y));},
    red:function(p){return colors.red(p);},green:function(p){return colors.green(p);},
    blue:function(p){return colors.blue(p);},resize:function(i,s){return images.resize(i,s);},
    read:function(p){return images.read(p);},nowMono:function(){return Date.now();}};
function expectedChars(fen) {
    var chars=[],parts=fen.split('/');
    for(var r=0;r<parts.length;r++)for(var c=0;c<parts[r].length;c++) {
        var ch=parts[r][c];
        if(ch>='1'&&ch<='9')for(var j=0;j<Number(ch);j++)chars.push('.');
        else chars.push(ch);
    }
    return chars;
}
function grade(result,frame) {
    if(frame.negative)return {correct:!result.ok,negative:true};
    var expected=expectedChars(frame.fen),wrong=[],correct=0,pieceCorrect=0,pieceTotal=0,colorWrong=0;
    for(var i=0;i<90;i++) {
        if(expected[i]==='?')continue;
        var got=result.chars?result.chars[i]:'?';
        if(expected[i]!=='.')pieceTotal++;
        if(got===expected[i]) {correct++;if(expected[i]!=='.')pieceCorrect++;}
        else {
            if(got!=='.' && got!=='?' && expected[i]!=='.'
                && got.toLowerCase()===expected[i].toLowerCase())colorWrong++;
            wrong.push({row:Math.floor(i/9),col:i%9,expected:expected[i],actual:got});
        }
    }
    return {correct:correct,total:90-expected.filter(function(c){return c==='?';}).length,
        pieceCorrect:pieceCorrect,pieceTotal:pieceTotal,colorWrong:colorWrong,wrong:wrong};
}
function save(complete) {
    var out=files.path('./output/chess-pipeline-'+engine+'.json');files.ensureDir(out);
    files.write(out,JSON.stringify({engine:engine,complete:!!complete,rows:rows,repeat:repeat,
        apiChecks:apiChecks,rhinoImagesAssetSha256:fixture.rhinoImagesAssetSha256,
        templatePrepareMs:pipeline?pipeline.templatePrepareMs:null,failure:failure,time:Date.now()},null,2));
}
try {
    var rgb=images.read('./grayscale-check.png'),gray=null,grayCopy=null;
    try {
        gray=images.grayscale(rgb);var expected=[76,150,29,255],measured=[];
        for(var x=0;x<4;x++) {
            var value=colors.red(images.pixel(gray,x,0));measured.push(value);
            if(Math.abs(value-expected[x])>1)throw new Error('Incorrect RGB grayscale at '+x+': '+value);
        }
        apiChecks.push({name:'RGB grayscale weights',pass:true,values:measured});
        grayCopy=images.grayscale(gray);gray.recycle();gray=null;
        if(Math.abs(colors.red(images.pixel(grayCopy,0,0))-76)>1)throw new Error('Grayscale copy shares recycled input');
        if(colors.red(images.pixel(rgb,0,0))!==255)throw new Error('Grayscale mutated color input');
        apiChecks.push({name:'grayscale copy owns pixels; color input survives',pass:true});
    } finally {if(grayCopy)grayCopy.recycle();if(gray)gray.recycle();if(rgb)rgb.recycle();}
    pipeline=pipelineModule.create({images:images,host:host,glyph:glyph,
        boardTemplate:files.path('./templates/board.png'),templateDir:files.path('./glyph-tpl'),
        calibration:fixture.calibration});
    for(var f=0;f<fixture.frames.length;f++) {
        var image=images.read('./'+fixture.frames[f].file);
        try {
            var result=pipeline.read(image),score=grade(result,fixture.frames[f]);
            rows.push({frame:fixture.frames[f].file,group:fixture.frames[f].group||'original',
                result:result,score:score});
            if(f===0)for(var n=0;n<3;n++) {
                var repeated=pipeline.read(image);
                repeat.push({elapsedMs:repeated.elapsedMs,locateMs:repeated.locateMs,
                    recognizeMs:repeated.recognizeMs,geometryCacheHit:repeated.geometryCacheHit,
                    score:grade(repeated,fixture.frames[f])});
            }
        } finally {if(image)image.recycle();}
        save(false);
        console.log('SIFT_CHESS_PIPELINE '+engine+' '+rows[rows.length-1].frame+' '
            +JSON.stringify(rows[rows.length-1].score)+' totalMs='+result.elapsedMs);
    }
} catch(error) {failure=String(error)+'\n'+String(error.stack||'');console.error(failure);}
finally {if(pipeline)pipeline.close();save(true);}
console.log('SIFT_CHESS_PIPELINE_DONE '+engine+' failure='+failure);
if(failure)throw new Error(failure);
