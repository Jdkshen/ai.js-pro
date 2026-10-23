"ui"; "nodejs";
const ui = require('ui');
class LoggingProbe extends ui.Activity {
    get layoutXml(){return '<frame w="*" h="*"><text text="Node UI logging probe"/></frame>';}
    onContentViewSet(){
        console.error('UI_ERROR_VISIBLE_MARKER');
        console.warn('UI_WARN_VISIBLE_MARKER');
        setTimeout(()=>this.finish(),250);
        throw Error('UI_EXPECTED_CALLBACK_ERROR');
    }
}
ui.setMainActivity(LoggingProbe);
