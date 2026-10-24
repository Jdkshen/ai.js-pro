#!/usr/bin/env python3
"""Lightweight JS-VM test for the Node M3 sample's deferred setup.

This does not use Gradle, a device, or ADB. It loads the real sample main.js in a
Node vm with a mocked ui/Java host surface and drives the lifecycle that the
sample depends on:

* first-frame deferral and per-contentView idempotence;
* setup order (visible grid before offscreen drawer, console lazy on page 1);
* no duplicate bottom-navigation/drawer listener registration;
* stale first-frame callbacks after Activity recreation and after close;
* grid data/images, console log-level listener and fab behavior still work.
"""

from __future__ import annotations

import json
import pathlib
import shutil
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SAMPLE = (
    ROOT
    / "apps/app/src/main/assets/sample/Node 引擎/Node.js/Node.js中使用UI"
    / "使用Android原生界面/main.js"
)

HARNESS = r"""'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const samplePath = process.argv[2];
if (!samplePath) throw new Error('sample path required');
const source = fs.readFileSync(samplePath, 'utf8');

const checks = [];
const counters = {
  afterFirstFrame: 0,
  defineClass: 0,
  loadResources: 0,
  setMainActivity: 0,
  inflateBatch: 0,
  imageLoads: 0,
  snackbar: 0,
  dialogs: 0,
  applyTheme: 0,
  supportActionBar: 0,
  drawerToggle: 0,
  drawerToggleSync: 0,
};
const uiPending = [];
const appOpened = [];
const consoleLogs = [];
const consoleErrors = [];
const imageUrls = [];
let MainActivityCtor = null;

function format(value) {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch (error) { return String(value); }
}
const sandboxConsole = {
  log(...args) { consoleLogs.push(args.map(format).join(' ')); },
  info(...args) { consoleLogs.push('[info] ' + args.map(format).join(' ')); },
  warn(...args) { consoleLogs.push('[warn] ' + args.map(format).join(' ')); },
  error(...args) {
    consoleErrors.push(args.map(value => value && value.stack ? value.stack : format(value)).join(' '));
  },
};

const R = {
  layout: {activity_main: 1001, grid_item: 1002},
  style: {MainTheme: 1003},
  menu: {menu_main: 1004, menu_drawer: 1005, menu_navigation: 1006},
  id: {
    drawer: 'drawer', toolbar: 'toolbar', viewPager: 'viewPager', fab: 'fab',
    navigation: 'navigation', drawerNavigation: 'drawerNavigation',
    console: 'console', toggleButton: 'toggleButton', grid: 'grid',
    item_email: 'item_email', item_docs: 'item_docs', item_forum: 'item_forum',
    item_m3: 'item_m3', buttonLog: 'buttonLog', buttonWarning: 'buttonWarning',
    buttonError: 'buttonError', navigation_dashboard: 'navigation_dashboard',
    navigation_log: 'navigation_log', navigation_settings: 'navigation_settings',
    action_abouts: 'action_abouts',
  },
};
const bottomNavigationIds = [
  R.id.navigation_dashboard, R.id.navigation_log, R.id.navigation_settings,
];

class OnPageChangeListener {
  constructor(callbacks) { Object.assign(this, callbacks); }
}
class DrawerListener {
  constructor(callbacks) { Object.assign(this, callbacks); }
}
class ActionBarDrawerToggle {
  constructor(activity, drawer, toolbar, openDescription, closeDescription) {
    counters.drawerToggle++;
    this.drawer = drawer;
    this.toolbar = toolbar;
  }
  syncState() {
    counters.drawerToggleSync++;
    this.toolbar.setNavigationIcon('toggle-hamburger');
  }
}
class RecyclerViewViewHolder {
  constructor(itemView) { this.itemView = itemView; }
}
class RecyclerViewAdapter {}
class GridLayoutManager {
  constructor(context, spanCount) {
    this.spanCount = spanCount;
    counters.gridLayoutManager = (counters.gridLayoutManager || 0) + 1;
  }
}
class SnackbarBuilder {
  constructor(view, text, duration) {
    this.view = view;
    this.text = text;
    this.duration = duration;
    this.records = view.records;
  }
  setAnchorView(id) { this.anchorId = id; return this; }
  setAction(label, callback) {
    this.actionLabel = label;
    this.records.snackbarAction = callback;
    return this;
  }
  show() {
    counters.snackbar++;
    this.records.snackbarShown = (this.records.snackbarShown || 0) + 1;
    return this;
  }
}
class MaterialAlertDialogBuilder {
  constructor(activity) { this.activity = activity; }
  setTitle(value) { this.title = value; return this; }
  setMessage(value) { this.message = value; return this; }
  setPositiveButton(label, callback) { this.positive = label; return this; }
  show() {
    counters.dialogs++;
    this.activity.__lastDialog = {title: this.title, message: this.message, positive: this.positive};
    return this;
  }
}

function makeItemView(index) {
  const view = {
    binding: {
      title: {setText(text) { view.__title = String(text); }},
      image: {__imageIndex: index},
    },
    checked: false,
    setOnClickListener(callback) { view.__click = callback; },
    isChecked() { return view.checked; },
    setChecked(value) { view.checked = value === true; },
    click() { if (view.__click) view.__click(); },
  };
  return view;
}

class BaseActivity {
  constructor() { this.__finishing = false; }
  onCreate() { this.__finishing = false; }
  onDestroy() { this.__finishing = true; }
  isFinishing() { return this.__finishing; }
  getWindow() { return {getDecorView() { return {}; }}; }
  getTheme() { return {applyStyle() { counters.applyTheme++; }}; }
  setSupportActionBar(toolbar) {
    counters.supportActionBar++;
    if (toolbar && toolbar.__records) toolbar.__records.order.push('drawer');
  }
  getMenuInflater() { return {inflate() { counters.menuInflate = (counters.menuInflate || 0) + 1; }}; }
  onOptionsItemSelected() { return false; }
  onBackPressed() {}
  finish() { this.__finishCount = (this.__finishCount || 0) + 1; this.__finishing = true; return this; }
}

const ui = {
  R,
  Activity: BaseActivity,
  imageLoader: {
    loadImageInto(image, url) {
      counters.imageLoads++;
      imageUrls.push(String(url));
      return Promise.resolve();
    },
  },
  loadAndroidResources() {
    counters.loadResources++;
    return Promise.resolve({compiled: true});
  },
  setMainActivity(ctor) {
    counters.setMainActivity++;
    MainActivityCtor = ctor;
  },
  afterFirstFrame(view, callback) {
    counters.afterFirstFrame++;
    uiPending.push({view, callback});
  },
  inflateBatch(layoutId, count, parent) {
    counters.inflateBatch++;
    return Array.from({length: count}, (_, index) => makeItemView(index));
  },
  close() { counters.uiClose = (counters.uiClose || 0) + 1; },
};

const app = {
  openUrl(url) { appOpened.push(String(url)); },
};

function makeBottomNavigation(records) {
  return {
    setOnItemSelectedListener(callback) {
      records.navListeners = (records.navListeners || 0) + 1;
      records.navListener = callback;
    },
    setSelectedItemId(id) {
      if (records.selectedItemId === id) return;
      records.selectedItemId = id;
      if (records.navListener) records.navListener({getItemId: () => id});
    },
    click(id) {
      records.navClicks = (records.navClicks || 0) + 1;
      return records.navListener ? records.navListener({getItemId: () => id}) : false;
    },
  };
}
function makeDrawerNavigation(records) {
  const menu = {
    findItem(id) {
      records.findItemCalls = (records.findItemCalls || 0) + 1;
      return {setTitle(title) { records.emailTitle = title; records.emailTitleSets = (records.emailTitleSets || 0) + 1; }};
    },
  };
  return {
    getMenu() { return menu; },
    setNavigationItemSelectedListener(callback) {
      records.drawerNavListeners = (records.drawerNavListeners || 0) + 1;
      records.drawerNavListener = callback;
    },
    click(id) {
      return records.drawerNavListener ? records.drawerNavListener({getItemId: () => id}) : false;
    },
  };
}
function makeDrawer(records) {
  records.drawerListeners = [];
  return {
    open: false,
    addDrawerListener(listener) { records.drawerListeners.push(listener); },
    isDrawerOpen() { return this.open; },
    isDrawerVisible() { return this.open; },
    closeDrawers() { this.open = false; records.closedDrawers = (records.closedDrawers || 0) + 1; },
    openDrawer() {
      this.open = true;
      records.openDrawerCalls = (records.openDrawerCalls || 0) + 1;
      this.slide(1);
    },
    slide(offset) {
      records.drawerListeners.forEach(listener => {
        if (typeof listener.onDrawerSlide === 'function') listener.onDrawerSlide(this, offset);
      });
    },
  };
}
function makeToolbar(records) {
  const toolbar = {
    setNavigationIcon(icon) { records.navIcon = icon; },
    setNavigationOnClickListener(callback) {
      records.toolbarNavListeners = (records.toolbarNavListeners || 0) + 1;
      records.toolbarNavClick = callback;
    },
    getTitle() { return 'M3 UI Demo'; },
  };
  toolbar.__records = records;
  return toolbar;
}
function makeViewPager(records) {
  return {
    currentItem: 0,
    initAdapterFromChildren() {
      records.initAdapterCalls = (records.initAdapterCalls || 0) + 1;
      records.order.push('pager');
    },
    addOnPageChangeListener(listener) { records.pageListeners.push(listener); },
    getCurrentItem() { return this.currentItem; },
    setCurrentItem(index) {
      this.currentItem = index;
      records.pageListeners.forEach(listener => {
        if (typeof listener.onPageSelected === 'function') listener.onPageSelected(index);
      });
    },
  };
}
function makeGrid(records) {
  return {
    setLayoutManager(layoutManager) { records.layoutManager = layoutManager; },
    setAdapter(adapter) {
      records.adapter = adapter;
      records.adapterSet = (records.adapterSet || 0) + 1;
      records.order.push('grid');
    },
    getContext() { return {}; },
  };
}

function makeContentView(label) {
  const records = {
    label,
    access: [],
    order: [],
    pageListeners: [],
    fabListeners: 0,
    fabClicks: 0,
    consoleToggleListeners: 0,
    logLevels: [],
    navListeners: 0,
    drawerNavListeners: 0,
  };
  const view = {label, records, binding: {}};
  const children = {
    fab: {
      setOnClickListener(callback) { records.fabListeners++; this.listener = callback; },
      click() { records.fabClicks++; if (this.listener) this.listener(); },
    },
    console: {
      getContentView() {
        return {setNestedScrollingEnabled(value) { records.consoleNested = value; }};
      },
      setLogLevel(level) { records.logLevels.push(String(level)); },
    },
    toggleButton: {
      addOnButtonCheckedListener(callback) {
        records.consoleToggleListeners++;
        this.listener = callback;
      },
      click(id) { if (this.listener) this.listener(this, id, true); },
    },
    viewPager: makeViewPager(records),
    navigation: makeBottomNavigation(records),
    drawerNavigation: makeDrawerNavigation(records),
    toolbar: makeToolbar(records),
    drawer: makeDrawer(records),
    grid: makeGrid(records),
  };
  Object.keys(children).forEach(name => {
    // Test/host handles are direct; the sample still goes through view.binding.<name>
    // so eager/lazy binding access remains observable.
    view[name] = children[name];
    Object.defineProperty(view.binding, name, {
      enumerable: true,
      get() {
        records.access.push(name);
        return children[name];
      },
    });
  });
  return view;
}

function wireHost(view) {
  // Mirrors NodeUiHost.wireNavigation: bottom navigation <-> pager, toolbar -> drawer.
  view.navigation.setOnItemSelectedListener(item => {
    const index = bottomNavigationIds.indexOf(item.getItemId());
    view.viewPager.setCurrentItem(index);
    return true;
  });
  view.viewPager.addOnPageChangeListener({
    onPageSelected(position) { view.navigation.setSelectedItemId(bottomNavigationIds[position]); },
  });
  view.toolbar.setNavigationIcon('host-sort');
  view.toolbar.setNavigationOnClickListener(() => view.drawer.openDrawer());
}

async function flushAfterFirstFrame() {
  const pending = uiPending.splice(0);
  pending.forEach(item => item.callback());
  await new Promise(resolve => setTimeout(resolve, 40));
}
async function waitFor(predicate, label, timeout = 2000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 5));
  }
  throw new Error('timeout: ' + label);
}

function assertSetup(records, label) {
  assert.strictEqual(records.initAdapterCalls, 1, label + ': pager setup once');
  assert.strictEqual(records.adapterSet, 1, label + ': grid adapter once');
  assert.strictEqual(records.adapter.data.length, 8, label + ': all 8 cards');
  assert.strictEqual(records.pageListeners.length, 2, label + ': host + sample page listeners');
  assert.strictEqual(records.navListeners, 2, label + ': sample explicitly replaces the host nav listener');
  assert.deepStrictEqual(records.order, ['pager', 'grid', 'drawer'], label + ': visible-first order');
  assert.strictEqual(records.drawerListeners.length, 2, label + ': toggle + lazy menu listener');
  assert.strictEqual(records.emailTitle, undefined, label + ': drawer menu not eager');
  assert.strictEqual(records.drawerNavListeners, 0, label + ': drawer click listener not eager');
  assert.strictEqual(records.access.includes('console'), false, label + ': console binding lazy');
  assert.strictEqual(records.access.includes('toggleButton'), false, label + ': console toggle lazy');
}

const android = {
  view: {
    View: {OnClickListener: class { constructor(callbacks) { Object.assign(this, callbacks); } }},
    LayoutInflater: {
      from() {
        return {inflate() { return makeItemView(99); }};
      },
    },
    Gravity: {START: 8388611},
  },
};
const androidx = {
  core: {view: {WindowCompat: {setDecorFitsSystemWindows() { counters.windowCompat = (counters.windowCompat || 0) + 1; }}}},
  recyclerview: {
    widget: {
      RecyclerView: {ViewHolder: RecyclerViewViewHolder, Adapter: RecyclerViewAdapter},
      GridLayoutManager,
    },
  },
  viewpager: {widget: {ViewPager: {OnPageChangeListener}}},
  appcompat: {app: {ActionBarDrawerToggle}},
  drawerlayout: {widget: {DrawerLayout: {DrawerListener}}},
};
const com = {
  google: {
    android: {
      material: {
        snackbar: {Snackbar: {make: (view, text, duration) => new SnackbarBuilder(view, text, duration)}},
        dialog: {MaterialAlertDialogBuilder},
      },
    },
  },
};
const $autojs = {
  java: {
    defineClass(klass) {
      counters.defineClass++;
      return Promise.resolve(klass);
    },
  },
};

const sandbox = {
  require(name) {
    if (name === 'rhino') return {install() {}};
    if (name === 'ui') return ui;
    if (name === 'app') return app;
    throw new Error('unexpected require: ' + name);
  },
  console: sandboxConsole,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  $autojs,
  android,
  androidx,
  com,
};
vm.runInNewContext(source, sandbox, {filename: samplePath});

(async () => {
  try {
    await waitFor(() => MainActivityCtor, 'ui.setMainActivity');
    assert.strictEqual(typeof MainActivityCtor, 'function');
    checks.push('main loaded sample and called setMainActivity');

    // 1. Normal launch: schedule once, grid before drawer, console/drawer menu lazy.
    const activity = new MainActivityCtor();
    activity.onCreate(null);
    const view = makeContentView('normal');
    wireHost(view);
    // Resource layouts on the real local Activity may have no working native
    // bottom-navigation listener. The sample must provide its own binding.
    view.records.navListener = null;
    activity.onContentViewSet(view);
    assert.strictEqual(uiPending.length, 1, 'one afterFirstFrame callback');
    assert.strictEqual(view.records.access.filter(name => name === 'console' || name === 'toggleButton').length, 0,
      'console bindings are not touched in onContentViewSet');
    assert.strictEqual(view.records.fabListeners, 1, 'fab listener registered once');
    activity.onContentViewSet(view);
    assert.strictEqual(uiPending.length, 1, 'repeated same contentView does not reschedule');
    assert.strictEqual(view.records.fabListeners, 1, 'repeated same contentView does not re-register fab');
    checks.push('same contentView schedules exactly once and only binds fab eagerly');

    await flushAfterFirstFrame();
    await waitFor(() => view.records.adapterSet === 1 && view.records.order.includes('drawer'),
      'normal setup complete');
    assertSetup(view.records, 'normal');
    assert.strictEqual(view.records.adapter.preInflatedViews.length, 8,
      'all grid item views are still batch inflated');
    assert.strictEqual(counters.defineClass, 2, 'defineClass compiled once for Holder+Adapter');
    checks.push('setup order pager -> grid -> drawer; console/drawer menu deferred');

    view.navigation.click(R.id.navigation_log);
    assert.strictEqual(view.records.consoleToggleListeners, 1, 'console listener registered once on page 1');
    assert.strictEqual(view.records.consoleNested, false, 'console nested scrolling disabled');
    view.toggleButton.click(R.id.buttonWarning);
    assert.deepStrictEqual(view.records.logLevels, ['W'], 'console warning filter works');
    view.navigation.click(R.id.navigation_log);
    assert.strictEqual(view.records.consoleToggleListeners, 1, 're-entering console does not duplicate listener');
    checks.push('console initializes lazily once on page 1 and keeps log-level behavior');

    view.drawer.slide(0.2);
    assert.strictEqual(view.records.emailTitle, 'pro@autojs.org', 'drawer email title set on first open');
    assert.strictEqual(view.records.drawerNavListeners, 1, 'drawer click listener set on first open');
    view.drawer.slide(0.5);
    assert.strictEqual(view.records.drawerNavListeners, 1, 'drawer menu setup is idempotent');
    assert.strictEqual(view.drawerNavigation.click(R.id.item_docs), true);
    assert.deepStrictEqual(appOpened, ['https://pro.autojs.org/docs/v9'], 'drawer docs click still works');
    checks.push('drawer menu lazily initialized once and navigation links still work');

    const adapter = view.records.adapter;
    const holders = [];
    for (let index = 0; index < 8; index++) {
      const holder = adapter.onCreateViewHolder(view.grid, 0);
      adapter.onBindViewHolder(holder, index);
      holders.push(holder);
    }
    assert.strictEqual(new Set(imageUrls).size, 8, 'all 8 image URLs bound');
    assert.strictEqual(adapter.preInflatedViews.length, 0, 'all pre-inflated items consumed');
    holders[0].itemView.click();
    assert.strictEqual(holders[0].item.checked, true, 'card click toggles data');
    view.fab.click();
    assert.strictEqual(view.records.snackbarShown, 1, 'fab still shows run snackbar');
    assert.strictEqual(typeof view.records.snackbarAction, 'function', 'cancel action still present');
    view.records.snackbarAction();
    assert.ok(consoleLogs.some(line => line.includes('功能1') && line.includes('"checked":true')),
      'fab logs checked card');
    assert.ok(consoleLogs.some(line => line.includes('已取消')), 'cancel action still logs');
    activity.onOptionsItemSelected({getItemId: () => R.id.action_abouts});
    assert.strictEqual(counters.dialogs, 1, 'about menu still opens a dialog');
    assert.strictEqual(activity.__lastDialog.title, 'Abouts');
    checks.push('grid images, card selection, fab and about dialog still work');

    // 2. Drawer opened before the deferred setup runs: the post-setup state check must still
    // configure the lazy menu exactly once.
    const earlyDrawer = new MainActivityCtor();
    earlyDrawer.onCreate(null);
    const earlyView = makeContentView('early-drawer');
    wireHost(earlyView);
    earlyView.drawer.open = true;
    earlyDrawer.onContentViewSet(earlyView);
    await flushAfterFirstFrame();
    await waitFor(() => earlyView.records.adapterSet === 1 && earlyView.records.drawerListeners.length === 2,
      'early drawer setup complete');
    assert.strictEqual(earlyView.records.emailTitle, 'pro@autojs.org',
      'already-open drawer gets its menu configured by the setup guard');
    assert.strictEqual(earlyView.records.drawerNavListeners, 1);
    earlyView.drawer.slide(1);
    assert.strictEqual(earlyView.records.drawerNavListeners, 1, 'early-open guard is idempotent');
    checks.push('drawer opened before setup still gets exactly one lazy menu configuration');

    // 3. Recreate before the old first-frame callback runs: old view must not set up.
    const recreated = new MainActivityCtor();
    recreated.onCreate(null);
    const stale = makeContentView('stale');
    wireHost(stale);
    recreated.onContentViewSet(stale);
    assert.strictEqual(uiPending.length, 1, 'stale view scheduled before recreation');
    recreated.onCreate(null);
    const fresh = makeContentView('fresh');
    wireHost(fresh);
    recreated.onContentViewSet(fresh);
    assert.strictEqual(uiPending.length, 2, 'new view schedules a new callback');
    await flushAfterFirstFrame();
    await waitFor(() => fresh.records.adapterSet === 1 && fresh.records.order.includes('drawer'),
      'fresh setup complete');
    assert.strictEqual(stale.records.adapterSet, undefined, 'stale first-frame callback is dropped');
    assert.strictEqual(stale.records.pageListeners.length, 1,
      'stale view keeps only the host-host page listener; sample adds none');
    assertSetup(fresh.records, 'fresh');
    assert.strictEqual(counters.defineClass, 2, 'recreation reuses compiled classes');
    checks.push('old view callback dropped after recreation; new view gets full idempotent setup');

    // 3. Recreate after console was initialized: the new view's console must initialize again.
    const afterConsole = new MainActivityCtor();
    afterConsole.onCreate(null);
    const first = makeContentView('first');
    wireHost(first);
    afterConsole.onContentViewSet(first);
    await flushAfterFirstFrame();
    await waitFor(() => first.records.adapterSet === 1, 'first setup complete');
    first.navigation.click(R.id.navigation_log);
    assert.strictEqual(first.records.consoleToggleListeners, 1);
    afterConsole.onCreate(null);
    const second = makeContentView('second');
    wireHost(second);
    afterConsole.onContentViewSet(second);
    await flushAfterFirstFrame();
    await waitFor(() => second.records.adapterSet === 1 && second.records.order.includes('drawer'),
      'second setup complete');
    assert.strictEqual(second.records.access.includes('console'), false,
      'console is lazy on the recreated view');
    second.navigation.click(R.id.navigation_log);
    assert.strictEqual(second.records.consoleToggleListeners, 1,
      'console listener is re-registered once after recreation');
    assert.strictEqual(first.records.consoleToggleListeners, 1, 'old console listener is not duplicated');
    checks.push('console lazy flag resets per Activity recreation');

    // 4. Close before the first frame: deferred setup must not touch the view.
    const closing = new MainActivityCtor();
    closing.onCreate(null);
    const pending = makeContentView('pending-close');
    wireHost(pending);
    closing.onContentViewSet(pending);
    closing.onDestroy();
    await flushAfterFirstFrame();
    await new Promise(resolve => setTimeout(resolve, 30));
    assert.strictEqual(pending.records.adapterSet, undefined, 'closed session does not build grid');
    assert.strictEqual(pending.records.pageListeners.length, 1,
      'closed session keeps only the host page listener; sample adds none');
    assert.strictEqual(closing._closed, true, 'onDestroy marks sample closed');
    checks.push('closed-session guard drops deferred setup');

    // Close while an already-started grid is awaiting class compilation.
    // Closing only before afterFirstFrame would miss this continuation race.
    const closingInFlight = new MainActivityCtor();
    closingInFlight.onCreate(null);
    const inFlightView = makeContentView('close-during-compile');
    wireHost(inFlightView);
    closingInFlight.onContentViewSet(inFlightView);
    const inFlightGrid = closingInFlight._setupGrid(inFlightView.grid, inFlightView);
    closingInFlight.onDestroy();
    await inFlightGrid;
    await flushAfterFirstFrame();
    assert.strictEqual(inFlightView.records.adapterSet, undefined,
      'closing during defineClass await must not attach a stale adapter');
    checks.push('closed-session guard drops in-flight grid continuation');

    earlyDrawer.onBackPressed();
    assert.strictEqual(earlyView.drawer.open, false, 'back first closes the open drawer');
    assert.strictEqual(earlyDrawer.__finishCount, undefined, 'drawer back does not finish the UI');
    earlyDrawer.onBackPressed();
    assert.strictEqual(earlyDrawer.__finishCount, 1, 'back with drawer closed finishes exactly once');
    checks.push('back closes the drawer before finishing the activity');

    assert.deepStrictEqual(consoleErrors, [], 'sample logged no setup errors');
    assert.strictEqual(counters.drawerToggle, 5, 'one drawer toggle per initialized view');
    assert.strictEqual(counters.drawerToggleSync, 5, 'one toggle sync per initialized view');

    const result = {
      ok: true,
      checks,
      order: view.records.order,
      counters,
      normal: {
        scheduledAfterFirstFrame: 1,
        pageListeners: view.records.pageListeners.length,
        navListeners: view.records.navListeners,
        drawerListeners: view.records.drawerListeners.length,
        consoleToggleListeners: view.records.consoleToggleListeners,
        cards: adapter.data.length,
        preInflated: 8,
        images: new Set(imageUrls).size,
        logLevels: view.records.logLevels,
        drawerLinks: appOpened,
      },
      recreate: {
        staleSkipped: stale.records.adapterSet === undefined,
        freshSetup: fresh.records.adapterSet === 1,
        reusedClasses: counters.defineClass === 2,
        consoleReset: second.records.consoleToggleListeners === 1,
      },
      closed: {
        skipped: pending.records.adapterSet === undefined && pending.records.pageListeners.length === 1,
      },
    };
    process.stdout.write('M3_LAZY_SETUP_RESULT ' + JSON.stringify(result) + '\n');
    process.exitCode = 0;
  } catch (error) {
    process.stderr.write('M3_LAZY_SETUP_FAIL ' + String(error && error.stack ? error.stack : error) + '\n');
    process.stderr.write('consoleErrors=' + JSON.stringify(consoleErrors) + '\n');
    process.exitCode = 1;
  }
})();
"""


class M3LazySetupTest(unittest.TestCase):
    def test_setup_scheduling_order_and_idempotence(self) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not on PATH")
        self.assertTrue(SAMPLE.is_file(), f"sample main.js missing: {SAMPLE}")

        # Feed the harness through stdin: the test does not create temp files, which
        # also keeps it working under the workspace-write sandbox.
        completed = subprocess.run(
            [node, "-", str(SAMPLE)],
            input=HARNESS,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )

        output = completed.stdout + "\n" + completed.stderr
        self.assertEqual(
            completed.returncode,
            0,
            msg="JS VM harness failed:\n" + output,
        )

        result = None
        for line in completed.stdout.splitlines():
            if line.startswith("M3_LAZY_SETUP_RESULT "):
                result = json.loads(line.split(" ", 1)[1])
                break
        self.assertIsNotNone(result, msg="missing M3_LAZY_SETUP_RESULT:\n" + output)
        assert result is not None
        self.assertTrue(result["ok"])
        self.assertEqual(result["order"], ["pager", "grid", "drawer"])
        self.assertEqual(result["normal"]["scheduledAfterFirstFrame"], 1)
        self.assertEqual(result["normal"]["pageListeners"], 2)  # host + sample console hook
        self.assertEqual(result["normal"]["navListeners"], 2)  # sample setter replaces the optional host listener
        self.assertEqual(result["normal"]["drawerListeners"], 2)  # toggle + lazy menu listener
        self.assertEqual(result["normal"]["consoleToggleListeners"], 1)
        self.assertEqual(result["normal"]["cards"], 8)
        self.assertEqual(result["normal"]["preInflated"], 8)
        self.assertEqual(result["normal"]["images"], 8)
        self.assertEqual(result["normal"]["logLevels"], ["W"])
        self.assertEqual(result["normal"]["drawerLinks"], ["https://pro.autojs.org/docs/v9"])
        self.assertTrue(result["recreate"]["staleSkipped"])
        self.assertTrue(result["recreate"]["freshSetup"])
        self.assertTrue(result["recreate"]["reusedClasses"])
        self.assertTrue(result["recreate"]["consoleReset"])
        self.assertTrue(result["closed"]["skipped"])
        self.assertEqual(result["counters"]["defineClass"], 2)
        self.assertEqual(result["counters"]["inflateBatch"], 5)
        self.assertEqual(result["counters"]["drawerToggle"], 5)
        self.assertEqual(result["counters"]["drawerToggleSync"], 5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
