/* Native Android selection/menu hooks retained separately from upstream Ace. */
(function(){
    const MouseHandler=ace.require('ace/mouse/mouse_handler').MouseHandler;
    ace.config.defineOptions(MouseHandler.prototype,'mouseHandler',{
        magnifierEnabled:{initialValue:false}});
    ace.config.defineOptions(ace.Editor.prototype,'editor',{magnifierEnabled:'$mouseHandler'});
    window.installAndroidEditorTouch=function(editor){
        let timer=null;
        const native=typeof autojs!=='undefined'?autojs:null;
        editor.actionMode='';
        function bounds(){
            const range=editor.selection.getRange(),rect=editor.renderer.scroller.getBoundingClientRect();
            const a=editor.renderer.textToScreenCoordinates(range.start.row,range.start.column);
            const b=editor.renderer.textToScreenCoordinates(range.end.row,range.end.column);
            return {left:0,right:1,top:(Math.min(a.pageY,b.pageY)-rect.top)/rect.height,
                bottom:(Math.max(a.pageY,b.pageY)-rect.top)/rect.height};
        }
        function start(selected,preserve){
            if(!native)return;
            const box=bounds();editor.actionMode=selected?'selectionActionMode':'noSelectionActionMode';
            native.startActionMode(box.left,box.top,box.right,box.bottom,selected,preserve);
        }
        editor.startActionMode=function(){clearTimeout(timer);timer=null;start(!editor.selection.isEmpty(),false);};
        editor.startActionModeIfSelectionInView=function(preserve){
            clearTimeout(timer);timer=setTimeout(()=>{
                timer=null;if(editor.selection.isEmpty())return;
                const box=bounds();if(box.bottom>=0 && box.top<=1)start(true,preserve);
            },200);
        };
        editor.finishActionMode=function(){
            clearTimeout(timer);timer=null;
            if(!editor.actionMode)return;editor.actionMode='';if(native)native.finishActionMode();
        };
        function selection(){if(editor.actionMode==='selectionActionMode' && editor.selection.isEmpty())editor.finishActionMode();}
        function change(){if(editor.actionMode==='noSelectionActionMode')editor.finishActionMode();}
        editor.on('changeSelection',selection);editor.on('change',change);
        editor.on('changeSession',editor.finishActionMode);
        editor.on('destroy',()=>{editor.finishActionMode();editor.off('changeSelection',selection);
            editor.off('change',change);editor.off('changeSession',editor.finishActionMode);});
    };
})();
