"rhino";
var result={process:String(android.app.Application.getProcessName()),checks:[]};
function check(name,value){result.checks.push({name:name,ok:!!value});}
try {
    var node=desc('打开菜单').findOne(4000);
    if(!node) throw Error('App menu node not found');
    result.nodeClass=String(node.getClass().getName());
    check('isolated_script_process',/:script$/.test(result.process));
    check('actual_remote_object',/RemoteUiObject$/.test(result.nodeClass));
    check('live_node_exists',node.exists());
    home();sleep(800);
    check('stale_node_does_not_exist',node.exists()===false);
}catch(e){result.checks.push({name:'probe',ok:false,error:String(e)});}
finally {app.launchPackage('com.jdkshen.aijspro');}
result.total=result.checks.length;result.passed=result.checks.filter(function(x){return x.ok;}).length;
console.log('REMOTE_EXISTS_RESULT='+JSON.stringify(result));
exit();
