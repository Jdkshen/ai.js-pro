// @engine quickjs
// Device fixture: /sdcard/Download/ai-sift-chess/{fixture.json,skin*.png,templates/}.
// Templates/labels come from docs/reports/chess-skin-eval; this tests generic SIFT,
// not the chess sample's separate glyph classifier. Copy with @engine rhino for Rhino.
var engine = typeof images.toMat === 'function' ? 'quickjs' : 'rhino';
var fixture = JSON.parse(files.read('./fixture.json'));
var frames = [], rows = [], cache = [], errors = [], failure = null;
var profiles = fixture.profiles || [{name:'normal', options:{scale:0.5}},
    {name:'fast', options:{scale:0.5,fast:true}}];
function median(values) {
    var sorted = values.slice().sort(function(a,b){return a-b;});
    return sorted[Math.floor(sorted.length/2)];
}
function judge(result, target, frame) {
    if (!result) return {status:'miss'};
    if (target.code === 'board') {
        var distance = Math.sqrt(Math.pow(result.centerX-540,2)+Math.pow(result.centerY-1188,2));
        var expected=[[12,600],[1068,600],[1068,1776],[12,1776]], maxCornerError=0;
        for(var c=0;c<4;c++) {
            var corner=result.corners && result.corners[c];
            if(!corner) {maxCornerError=Infinity;break;}
            maxCornerError=Math.max(maxCornerError,Math.sqrt(Math.pow(corner.x-expected[c][0],2)
                +Math.pow(corner.y-expected[c][1],2)));
        }
        return {status:distance<=15 && maxCornerError<=15?'correct':'wrong',
            distance:distance, maxCornerError:maxCornerError};
    }
    var nearest = null, distance = Infinity;
    for (var i=0;i<frame.pieces.length;i++) {
        var piece = frame.pieces[i];
        var d = Math.sqrt(Math.pow(result.centerX-piece.x,2)+Math.pow(result.centerY-piece.y,2));
        if (d<distance) { distance=d; nearest=piece; }
    }
    return {status:distance<=20 && nearest.code===target.code?'correct':
        distance<=20 && nearest.code==='?'?'unknown':'wrong',
        distance:distance, actual:nearest?nearest.code:null,
        row:nearest?nearest.row:null, col:nearest?nearest.col:null};
}
function sample(matcher, image, options, target, frame) {
    var start = Date.now(), result = matcher.match(image, options);
    return {wallMs:Date.now()-start, verdict:judge(result,target,frame), result:result};
}
function save() {
    var out = files.path('./output/'+(fixture.outputPrefix||'chess-')+engine+'.json'); files.ensureDir(out);
    files.write(out,JSON.stringify({engine:engine,fixture:fixture,rows:rows,cache:cache,
        errors:errors,failure:failure,time:Date.now()},null,2));
}
try {
    for (var f=0;f<fixture.frames.length;f++) frames.push(images.read('./'+fixture.frames[f].file));
    var targets = fixture.targets;
    for (var p=0;p<profiles.length;p++) {
        var profile = profiles[p];
        for (var t=0;t<targets.length;t++) {
            var target = targets[t], matcher = null;
            try {
                var options = Object.assign({},profile.options);
                if (target.code==='board') options.templateScale=0.5;
                matcher = images.createSiftMatcher('./'+target.file,options);
                for (f=0;f<frames.length;f++) {
                    var measured = sample(matcher,frames[f],{},target,fixture.frames[f]);
                    rows.push({profile:profile.name,target:target.code,frame:fixture.frames[f].file,
                        templatePrepareMs:matcher.templatePrepareMs,templateFeatures:matcher.templateFeatureCount,
                        wallMs:measured.wallMs,verdict:measured.verdict,result:measured.result});
                    if (f===0 && (target.code==='board'||target.code==='k'||target.code==='K')) {
                        var repeats=[], hits=0, verdicts=[];
                        for (var n=0;n<5;n++) {
                            var warm=sample(matcher,frames[f],{},target,fixture.frames[f]);
                            repeats.push(warm.wallMs); verdicts.push(warm.verdict.status);
                            if(warm.result && warm.result.featureCacheHit && warm.result.indexCacheHit) hits++;
                        }
                        cache.push({profile:profile.name,target:target.code,firstMs:measured.wallMs,
                            samples:repeats,medianMs:median(repeats),cacheHits:hits,verdicts:verdicts});
                    }
                }
            } catch(e) {errors.push({profile:profile.name,target:target.code,error:String(e),stack:String(e.stack||'')});}
            finally {if(matcher)matcher.recycle();}
            console.log('SIFT_CHESS_PROGRESS '+engine+' '+profile.name+' '+target.code+' rows='+rows.length);
            save();
        }
    }
    var summary={};
    for(var j=0;j<rows.length;j++) {
        var row=rows[j], key=row.profile+'/'+(row.target==='board'?'board':'piece');
        if(!summary[key])summary[key]={correct:0,miss:0,wrong:0,unknown:0,times:[]};
        summary[key][row.verdict.status]++; summary[key].times.push(row.wallMs);
    }
    for(var key in summary)summary[key].medianMs=median(summary[key].times);
    console.log('SIFT_CHESS_SUMMARY '+engine+' '+JSON.stringify(summary));
} catch(e) {failure=String(e)+'\n'+String(e.stack||'');console.error(failure);}
finally {for(var i=frames.length-1;i>=0;i--)if(frames[i])frames[i].recycle();save();}
console.log('SIFT_CHESS_DONE '+engine+' errors='+errors.length+' failure='+failure);
if(failure)throw new Error(failure);
