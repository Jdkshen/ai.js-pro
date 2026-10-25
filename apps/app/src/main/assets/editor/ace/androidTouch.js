/* Native Android selection/menu hooks retained separately from upstream Ace. */
(function(){
    const MouseHandler=ace.require('ace/mouse/mouse_handler').MouseHandler;
    ace.config.defineOptions(MouseHandler.prototype,'mouseHandler',{
        magnifierEnabled:{initialValue:false}});
    ace.config.defineOptions(ace.Editor.prototype,'editor',{magnifierEnabled:'$mouseHandler'});
    function nativeBridge(){
        if(window.EditorBridge && typeof window.EditorBridge.startActionMode==='function')
            return window.EditorBridge;
        return typeof autojs!=='undefined'?autojs:null;
    }
    window.installAndroidEditorTouch=function(editor){
        let timer=null;
        // The retained Android touch handler uses the old renderer helper. Upstream
        // Ace 1.44 exposes textToScreenCoordinates instead; without this adapter its
        // long-press timer throws before selecting a word or opening the menu.
        if(typeof editor.renderer.getClientPosition!=='function'){
            editor.renderer.getClientPosition=function(position){
                const screen=editor.renderer.textToScreenCoordinates(position.row,position.column);
                return {x:screen.pageX-(window.pageXOffset||0),y:screen.pageY-(window.pageYOffset||0)};
            };
        }
        editor.actionMode='';
        function bounds(){
            const range=editor.selection.getRange(),rect=editor.renderer.scroller.getBoundingClientRect();
            const a=editor.renderer.textToScreenCoordinates(range.start.row,range.start.column);
            const b=editor.renderer.textToScreenCoordinates(range.end.row,range.end.column);
            return {left:0,right:1,top:(Math.min(a.pageY,b.pageY)-rect.top)/rect.height,
                bottom:(Math.max(a.pageY,b.pageY)-rect.top)/rect.height};
        }
        function start(selected,preserve){
            // A plain tap collapses the selection; do not open a paste/select-all
            // toolbar for that tap. Long press and handle drags pass selected=true.
            if(!selected)return;
            const native=nativeBridge();
            if(!native || typeof native.startActionMode!=='function')return;
            const box=bounds();
            editor.actionMode='selectionActionMode';
            try{
                native.startActionMode(box.left,box.top,box.right,box.bottom,true,preserve);
            }catch(error){
                editor.actionMode='';
            }
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
            if(!editor.actionMode)return;editor.actionMode='';
            const native=nativeBridge();
            if(native && typeof native.finishActionMode==='function'){
                try{native.finishActionMode();}catch(error){}
            }
        };
        function selection(){if(editor.actionMode==='selectionActionMode' && editor.selection.isEmpty())editor.finishActionMode();}
        editor.on('changeSelection',selection);
        editor.on('changeSession',editor.finishActionMode);
        editor.on('destroy',()=>{editor.finishActionMode();editor.off('changeSelection',selection);
            editor.off('changeSession',editor.finishActionMode);});
    };
})();
