"ui";
"rhino";
var checks = [], finished = false;
function check(name, ok) { checks.push({name:name,ok:!!ok}); if(!ok) throw new Error(name); }
function post(action, delay) { ui.post(function () { try { action(); } catch(e) { checks.push({name:'exception',ok:false,error:String(e)}); finish(); } }, delay || 0); }
function finish() {
    if(finished)return; finished=true;
    console.log('DOT_JUMP_RESULT='+JSON.stringify({checks:checks,passed:checks.filter(function(c){return c.ok;}).length,total:checks.length}));
    ui.finish();
}
function targets() { return [ui.loadingClassic,ui.loadingColor,ui.loadingAuto]; }
function running(value) { return targets().every(function(v){return v.isRunning()===value;}); }
function snapshot(view) {
    var bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
    view.draw(new android.graphics.Canvas(bitmap));
    return bitmap;
}
require('./main.js');
post(function(){
    check('three_native_views_measured',targets().every(function(v){return v instanceof android.view.View && v.getWidth()>0 && v.getHeight()>0;}));
    check('auto_started',running(true));
    var phase=ui.loadingColor.getPhase(), progress=ui.loadingColor.getProgress();
    post(function(){
        check('animation_advances',ui.loadingColor.getPhase()!==phase || ui.loadingColor.getProgress()!==progress);
        ui.pauseBtn.performClick();
        check('pause_button_stops_all',running(false) && String(ui.pauseBtn.getText())==='继续');
        phase=ui.loadingColor.getPhase(); progress=ui.loadingColor.getProgress();
        post(function(){
            check('paused_frame_stays_still',ui.loadingColor.getPhase()===phase && ui.loadingColor.getProgress()===progress);
            var before=snapshot(ui.loadingColor), after=null;
            try { ui.colorBtn.performClick(); after=snapshot(ui.loadingColor); check('color_button_changes_real_pixels',!before.sameAs(after)); }
            finally {before.recycle();if(after)after.recycle();}
            var previousHeight=ui.loadingAuto.getHeight();
            ui.sizeBtn.performClick();
            post(function(){
                check('height_button_remeasures_auto_view',ui.loadingAuto.getHeight()>previousHeight);
                ui.pauseBtn.performClick();
                check('continue_button_restarts_all',running(true) && String(ui.pauseBtn.getText())==='暂停');
                var resumed=ui.loadingColor.getProgress();
                post(function(){check('resumed_animation_advances',ui.loadingColor.getProgress()!==resumed);finish();},250);
            },180);
        },250);
    },300);
},250);
