"ui nodejs";
// Run from a copied advanced native UI project containing the unchanged main.js
// and build/project-res.apk compiled for the exact installed host APK.
require('rhino').install();
const assert = require('assert');
const ui = require('ui');
const checks = [];
let opened;
const ready = new Promise(resolve => { opened = resolve; });
const setMainActivity = ui.setMainActivity;
ui.setMainActivity = function (Ctor) {
  const result = setMainActivity(Ctor);
  opened(result.instance);
  return result;
};
require('./main.js');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn) {
  for (let i = 0; i < 100; ++i) { const value = fn(); if (value) return value; await delay(100); }
  throw new Error('Native UI did not become ready');
}
(async () => {
  const instance = await ready;
  const session = $java.findClass('com.stardust.autojs.engine.node24.NodeUiLocalSession');
  const root = session.getContentView();
  assert.strictEqual(ui.currentActivity(), session.getActivity());
  assert.strictEqual(ui.getCurrentActivity(), session.getActivity());
  assert.strictEqual(ui.getWindow(), instance.getWindow());
  assert.strictEqual(ui.findViewById('grid'), root.binding.grid);
  assert.strictEqual(ui.findViewById(ui.R.id.grid), root.binding.grid);
  assert.strictEqual(ui.findViewById('_missing_native_view_'), null);
  checks.push('public Activity, Window and View lookup APIs return the real Java objects');
  const item = ui.inflate(ui.R.layout.grid_item);
  assert(item.binding.title);
  assert(item.binding.image);
  checks.push('public inflate creates a real Material item with project resource bindings');
  const grid = root.binding.grid;
  await until(() => grid.getAdapter());
  const first = await until(() => grid.findViewHolderForAdapterPosition(0));
  assert.strictEqual(first.item, instance.cardList[0], 'holder must keep the original JS data identity');
  first.itemView.performClick();
  assert.strictEqual(instance.cardList[0].checked, true);
  assert.strictEqual(first.itemView.isChecked(), true);
  checks.push('first card click updates original JS item and real MaterialCardView');
  root.binding.fab.performClick();
  grid.scrollToPosition(7);
  const last = await until(() => grid.findViewHolderForAdapterPosition(7));
  assert.strictEqual(last.item, instance.cardList[7]);
  last.itemView.performClick();
  assert.strictEqual(instance.cardList[7].checked, true);
  assert.strictEqual(instance.cardList[0].checked, true);
  grid.scrollToPosition(0);
  const recycled = await until(() => grid.findViewHolderForAdapterPosition(0));
  assert.strictEqual(recycled.itemView.isChecked(), true, 'selection must survive recycling');
  recycled.itemView.performClick();
  assert.strictEqual(instance.cardList[0].checked, false);
  assert.strictEqual(instance.cardList[7].checked, true);
  checks.push('recycled rows retain selection and toggle the correct original item');
  root.binding.navigation.setSelectedItemId(ui.R.id.navigation_log);
  await until(() => root.binding.viewPager.getCurrentItem() === 1);
  root.binding.viewPager.setCurrentItem(2);
  await until(() => root.binding.navigation.getSelectedItemId() === ui.R.id.navigation_settings);
  checks.push('bottom navigation and ViewPager callbacks synchronize in both directions');
  root.binding.console.setLogLevel('W');
  console.warn('NATIVE_UI_FILTER_WARNING');
  root.binding.console.setLogLevel('V');
  checks.push('original console View receives live Node logs and accepts filters');
  assert.strictEqual(ui.setContentView(item), item);
  assert.strictEqual(session.getContentView(), item);
  assert.strictEqual(ui.findViewById('title'), item.binding.title);
  checks.push('public setContentView replaces both the Android root and session lookup root');
  console.log('NODE_PRO_NATIVE_UI_RESULT ' + JSON.stringify({ok: true, checks}));
})().catch(error => {
  console.error(error.stack || error);
  process.exitCode = 1;
}).finally(() => ui.close());
