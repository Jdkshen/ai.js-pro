// @engine rhino
var N = Packages.com.stardust.view.accessibility.NodeInfo;
var G = Packages.com.stardust.autojs.codegeneration.CodeGenerator;
var results = [];
function node(cls, id, text, depth) {
  var n = N.createSnapshot(); n.setClassName(cls); n.setId(id);
  n.setText(text || ''); n.setDepth(depth || 0); return n;
}
function attach(parent, child) { parent.addSnapshotChild(child); return child; }
function check(name, fn) {
  try { var detail = fn(); results.push({name:name, pass:true, detail:String(detail || '')}); }
  catch (e) { results.push({name:name, pass:false, detail:String(e)}); }
}
function assert(value, message) { if (!value) throw new Error(message); }
function code(generator) {
  var value = generator.generateCode(); assert(value != null, 'null code');
  var text = String(value);
  new Packages.org.mozilla.javascript.Parser().parse(text, 'generated.js', 1);
  return text;
}
var root = node('Root', 'root', '', 0);
var target = attach(root, node('Button', 'button', 'Hello', 1));
check('findOne', function() { var s = code(new G(root,target)); assert(s.indexOf('.findOne()') >= 0,s); return s; });
check('setText escapes input', function() {
  var g = new G(root,target); g.setAction(2097152); g.setActionText('a"b\\c\n中文');
  var s = code(g); assert(s.indexOf('setText(') >= 0,s);
  var expression = s.slice(s.indexOf('setText(')+8,-1);
  assert(eval(expression) === 'a"b\\c\n中文',s); return s;
});
check('waitFor has one terminal and no action', function() {
  var g = new G(root,target); g.setSearchMode(2); g.setAction(16);
  var s = code(g); assert(s.indexOf('.waitFor()') >= 0 && s.indexOf('.findOne()') < 0 && s.indexOf('.click()') < 0,s); return s;
});
check('exists', function() { var g = new G(root,target); g.setSearchMode(3); var s=code(g); assert(s.indexOf('.exists()')>=0,s); return s; });
check('untilFind action', function() { var g = new G(root,target); g.setSearchMode(0); g.setAction(16); var s=code(g); assert(s.indexOf('.untilFind().click()')>=0,s); return s; });
var list = attach(root,node('List','list','',1)); list.setRowCount(2);
var item = attach(list,node('Item','item','',2));
var label = attach(item,node('Text','label','content',3));
check('list without action stays valid', function() { var s=code(new G(root,label)); assert(s.indexOf('target.;')<0,s); return s; });
check('list setText guards disappeared item', function() { var g = new G(root,label); g.setAction(2097152); var s=code(g); assert(s.indexOf('if (target) target.setText')>=0,s); return s; });
check('selected list item itself', function() {
  var read = new Packages.com.stardust.autojs.codegeneration.ReadOnlyUiObject(item);
  var selector = new Packages.com.stardust.automator.UiGlobalSelector().id('item');
  assert(read.findOne(selector) != null,'item root is not matched');
  return code(new G(root,item));
});
var a = attach(root,node('Panel','panel-a','',1));
var first = attach(a,node('Duplicate',null,'same',2));
var b = attach(root,node('Panel','panel-b','',1));
attach(b,node('Duplicate',null,'same',2));
check('first child parent fallback', function() { var s=code(new G(root,first)); assert(s.indexOf('.child(0)')>=0,s); return s; });
check('disabled text and desc persist in parent fallback', function() {
  var r = node('com.example.OtherRoot',null,'',0);
  var p = attach(r,node('com.example.SameParent',null,'parent-a',1)); p.setDesc('desc-a');
  var t = attach(p,node('com.example.SameChild',null,'same',2));
  var q = attach(r,node('com.example.SameParent',null,'parent-b',1)); q.setDesc('desc-b');
  attach(q,node('com.example.SameChild',null,'same',2));
  var g = new G(r,t); g.setUsingId(false); g.setUsingText(false); g.setUsingDesc(false);
  var s=code(g); assert(s.indexOf('.child(0)')>=0 && s.indexOf('text(')<0 && s.indexOf('desc(')<0,s); return s;
});
check('ambiguous waitFor does not navigate through void', function() {
  var g = new G(root,first); g.setSearchMode(2);
  assert(g.generateCode() == null,'invalid waitFor navigation generated');
});
check('ambiguous list item does not pass UiObject as selector', function() {
  var r = node('Root',null,'',0);
  var l = attach(r,node('List','other-list','',1)); l.setRowCount(1);
  var i = attach(l,node('Item','other-item','',2));
  var t = attach(i,node('Ambiguous',null,'same',3));
  attach(i,node('Ambiguous',null,'same',3));
  assert(new G(r,t).generateCode() == null,'invalid findOne argument generated');
});
files.write('/sdcard/Download/code-generation-regression-result.json',JSON.stringify(results,null,2));
console.log(JSON.stringify(results));
