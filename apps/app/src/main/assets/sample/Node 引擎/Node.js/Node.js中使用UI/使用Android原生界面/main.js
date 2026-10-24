"nodejs";
"ui nodejs";
require('rhino').install();

const ui = require('ui');
const app = require('app');
const $java = $autojs.java;

// 要使用Android原生界面&资源的特性，需要在project.json中加上androidResources属性
//
// task-231（M3 初始化拆批）：
// 两个 $java.defineClass 不再放在 onContentViewSet -> _setupGrid 里串行编译；
// loadAndroidResources 完成后立即启动，与 setMainActivity / Activity show 并行。
// onContentViewSet 只做轻量 chrome；列表/控制台初始化延后到 setTimeout(0)，再 await 已启动的 Promise。
//
// task-238（M3 首屏/重建）：
// - afterFirstFrame 后按「可见内容优先」执行：控制台监听 -> Grid -> 下一拍 Drawer；
// - console/toggle 的 binding、Drawer 菜单项与点击监听都推迟到真正需要时；
// - 显式绑定底部导航与 ViewPager，兼容未自动连线的本地 Activity；同页跳转不重复触发；
// - 以 contentView 身份做幂等，Activity 重建时用新 view 重新 setup，旧 view 的回调丢弃。
let gridViewHolderClass = null;
let gridHolderClassPromise = null;
let gridAdapterClassPromise = null;

/**
 * 并行启动 MyViewHolder/MyAdapter 的 defineClass。
 * Adapter 方法体里的 gridViewHolderClass 是模块级延迟引用：两个 defineClass 可以同时开始，
 * 等 _setupGrid 同时 await 两个 Promise 时 Holder 已赋值，再实例化 Adapter。
 */
function startGridClassCompilation() {
    if (gridHolderClassPromise) {
        return;
    }
    gridHolderClassPromise = $java.defineClass(
        class MyViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            constructor(itemView) {
                super(itemView);
                itemView.setOnClickListener(() => {
                    const checked = !itemView.isChecked();
                    itemView.setChecked(checked);
                    this.item.checked = checked;
                });
                this.title = itemView.binding.title;
                this.image = itemView.binding.image;
            }

            bind(item, position) {
                this.title.setText(item.title);
                ui.imageLoader.loadImageInto(this.image, item.url).catch(console.error);
                this.itemView.setChecked(item.checked ?? false);
                this.item = item;
            }
        }
    );
    // 如果页面在极短时间内完成，_setupGrid 会 await 并看到真实错误；这里先挂 no-op
    // 防止 setMainActivity 之前 Promise reject 冒成 unhandledRejection。
    gridHolderClassPromise = gridHolderClassPromise.then((holderClass) => {
        gridViewHolderClass = holderClass;
        return holderClass;
    });
    gridHolderClassPromise.catch(() => {});
    // 与 Holder 并行启动 Adapter defineClass（definition 只读 prototype 方法，不执行方法体）。
    gridAdapterClassPromise = $java.defineClass(
        class MyAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter {
            constructor(data, preInflatedViews) {
                super();
                this.data = data;
                // task-233：优先消费 ui.inflateBatch 预生成的 itemView；耗尽后回退 LayoutInflater。
                this.preInflatedViews = Array.isArray(preInflatedViews) ? preInflatedViews : [];
            }

            onCreateViewHolder(parent, viewType) {
                const preInflated = this.preInflatedViews.length > 0
                    ? this.preInflatedViews.shift() : null;
                if (preInflated) {
                    return new gridViewHolderClass(preInflated);
                }
                // 预生成 View 取完（或批量失败）时保持逐条 LayoutInflater 回退。
                return new gridViewHolderClass(android.view.LayoutInflater.from(parent.getContext())
                    .inflate(ui.R.layout.grid_item, parent, false));
            }

            onBindViewHolder(holder, position) {
                holder.bind(this.data[position]);
            }

            getItemCount() {
                return this.data.length;
            }
        }
    );
    gridAdapterClassPromise.catch(() => {});
}

async function main() {
    // 加载Android资源
    await ui.loadAndroidResources();
    // task-231：先同步启动两个 defineClass（Holder/Adapter 并行），再 setMainActivity；
    // defineClass 的异步编译与 Activity show 重叠，不再等 onContentViewSet 才串行执行。
    startGridClassCompilation();
    ui.setMainActivity(MainActivity);
}
main().catch(console.error);

class MainActivity extends ui.Activity {

    // 默认布局ID，对应于文件res/layout/activity_main
    get layoutId() {
        return ui.R.layout.activity_main;
    }

    constructor() {
        super();
        // 卡片列表初始数据
        this.cardList = [
            { title: '功能1', url: 'https://pic.rmb.bdstatic.com/bjh/171cd6cf9e22e5a8ac1567725a71a8e4.jpeg' },
            { title: '功能2', url: 'https://pic.rmb.bdstatic.com/bjh/f99b58ad941c90bb94d2c8969d5a7fb7.jpeg' },
            { title: '功能3', url: 'https://pic.rmb.bdstatic.com/bjh/d3de1d7f8b4381e952de1499d4892329.jpeg' },
            { title: '功能4', url: 'https://pic.rmb.bdstatic.com/bjh/b97040da6b4d791391a96dea7acf1580.jpeg' },
            { title: '功能5', url: 'https://pic.rmb.bdstatic.com/bjh/8dd74c59886964ffdda4401ea4bf78ed.jpeg' },
            { title: '功能6', url: 'https://pic.rmb.bdstatic.com/bjh/3cbb35d423544aac493178079c200913.jpeg' },
            { title: '功能7', url: 'https://pic.rmb.bdstatic.com/bjh/791d0f965a41243b994f47247e84d722.jpeg' },
            { title: '功能8', url: 'https://picsum.photos/800' },
        ];
        // task-238：按 contentView 身份做幂等；Activity 重建会换新 view，不能沿用旧 flag。
        this._setupView = null;
        this._closed = false;
        this._consoleReady = false;
        this._drawerMenuReady = false;
    }

    onCreate(savedInstanceState) {
        // 重建时旧 view 的延迟 setup 必须失效；真正的关闭由 onDestroy 置 _closed。
        this._closed = false;
        this._setupView = null;
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this.getWindow(), false);
        // 应用主题
        this.getTheme().applyStyle(ui.R.style.MainTheme, true);
        super.onCreate(savedInstanceState);
    }

    onDestroy() {
        // 宿主在 Activity 重建时只发 pause/stop，不发 destroy；这里只处理真正关闭。
        this._closed = true;
        this._setupView = null;
        this._consoleReady = false;
        this._drawerMenuReady = false;
        super.onDestroy();
    }

    onBackPressed() {
        const view = this._setupView;
        if (view && this._isCurrentView(view)) {
            const drawer = view.binding.drawer;
            if (drawer.isDrawerVisible(android.view.Gravity.START)) {
                drawer.closeDrawers();
                return;
            }
        }
        this.finish();
    }

    onContentViewSet(view) {
        // task-238：同一 contentView 的重复回调直接忽略；重建的新 view 重新走完整调度。
        if (this._closed || this._setupView === view) {
            return;
        }
        this._setupView = view;
        this._consoleReady = false;
        this._drawerMenuReady = false;
        // task-234：首帧前只做 fab；console/toggle 的 binding 留到用户真正切到控制台页再取。
        view.binding.fab.setOnClickListener(() => {
            console.log('已勾选卡片:', this.cardList.filter(item => item.checked));
            const Snackbar = com.google.android.material.snackbar.Snackbar;
            Snackbar.make(view, "开始运行", 0)
                .setAnchorView(ui.R.id.fab)
                .setAction("取消", () => {
                    console.log('已取消');
                }).show()
        });
        this._scheduleDeferredSetup(view);
    }

    _isCurrentView(view) {
        return !this._closed && this._setupView === view && !this.isFinishing();
    }

    /** 延迟初始化 ViewPager/列表/抽屉；同一个 contentView 只调度一次，旧 view 的回调按身份丢弃。 */
    _scheduleDeferredSetup(view) {
        // task-237/238：首帧后再做重型原生 setup，避免挡住首屏。
        ui.afterFirstFrame(view, () => {
            if (!this._isCurrentView(view)) {
                return;
            }
            this._setupDeferred(view).catch(console.error);
        });
    }

    async _setupDeferred(view) {
        // task-238：可见内容优先。先绑定底部导航与控制台懒加载监听；
        // 然后建首屏 Grid；Drawer 离屏，放到下一拍，给 grid 的 requestLayout/首帧留出时间。
        // 单项失败记录后继续，保证导航/列表/抽屉最终都尽量完成。
        try {
            this._setupViewPager(view);
        } catch (error) {
            console.error('M3 ViewPager 初始化失败', error);
        }
        if (!this._isCurrentView(view)) {
            return;
        }
        try {
            await this._setupGrid(view.binding.grid, view);
        } catch (error) {
            console.error('M3 列表初始化失败', error);
        }
        if (!this._isCurrentView(view)) {
            return;
        }
        // 让 grid setAdapter 之后的布局/绘制先跑完，再执行离屏 Drawer 的原生 setup。
        await new Promise(resolve => setTimeout(resolve, 0));
        if (!this._isCurrentView(view)) {
            return;
        }
        try {
            this._setupDrawer(view.binding.drawer, view.binding.toolbar, view.binding.drawerNavigation);
        } catch (error) {
            console.error('M3 Drawer 初始化失败', error);
        }
    }

    /** 控制台只在用户切到控制台页时初始化；按 contentView 重置，Activity 重建后仍会执行。 */
    _setupConsoleLazy(view) {
        if (this._consoleReady || !this._isCurrentView(view)) {
            return;
        }
        let consoleView;
        let toggleButton;
        try {
            consoleView = view.binding.console;
            toggleButton = view.binding.toggleButton;
        } catch (error) {
            console.error('M3 控制台 View 获取失败', error);
            return;
        }
        if (!consoleView || !toggleButton) {
            console.error('M3 控制台 View 缺失');
            return;
        }
        // 先置位再注册，避免 setup 失败重试时重复 addOnButtonCheckedListener。
        this._consoleReady = true;
        try {
            this._setupConsole(consoleView, toggleButton);
        } catch (error) {
            console.error('M3 控制台初始化失败', error);
        }
    }

    _setupDrawer(drawer, toolbar, navigation) {
        this.setSupportActionBar(toolbar);
        // 抽屉图标/开合动画仍由 ActionBarDrawerToggle 负责；菜单内容延后到首次滑出。
        const toggle = new androidx.appcompat.app.ActionBarDrawerToggle(this, drawer, toolbar, 0, 0);
        toggle.syncState();
        drawer.addDrawerListener(toggle);
        // task-238：邮件标题与菜单点击只在抽屉第一次可见时配置，避免首屏后的 eager setup。
        drawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.DrawerListener({
            onDrawerSlide: (drawerView, slideOffset) => {
                if (slideOffset > 0) this._setupDrawerMenu(navigation);
            },
            onDrawerOpened: () => this._setupDrawerMenu(navigation),
            onDrawerClosed: () => {
            },
            onDrawerStateChanged: (newState) => {
            },
        }));
        // 用户可能在 Drawer setup 完成前就点开/滑开了抽屉；补一次状态检查。
        try {
            if (drawer.isDrawerOpen(android.view.Gravity.START) || drawer.isDrawerVisible(navigation)) {
                this._setupDrawerMenu(navigation);
            }
        } catch (ignored) {
            // 查询不可用时退回一次配置，避免抽屉已经打开却没有菜单监听。
            this._setupDrawerMenu(navigation);
        }
    }

    _setupDrawerMenu(navigation) {
        if (this._drawerMenuReady) {
            return;
        }
        this._drawerMenuReady = true;
        // 设置侧拉菜单中邮件Item的标题
        navigation.getMenu().findItem(ui.R.id.item_email).setTitle('pro@autojs.org');
        // 侧拉菜单点击监听
        navigation.setNavigationItemSelectedListener(item => {
            switch (item.getItemId()) {
                case ui.R.id.item_docs:
                    app.openUrl('https://pro.autojs.org/docs/v9');
                    break;
                case ui.R.id.item_forum:
                    app.openUrl('https://blog.autojs.org/');
                    break;
                case ui.R.id.item_m3:
                    app.openUrl('https://m3.material.io/');
                    break;
            }
            return true;
        });
    }

    _setupViewPager(view) {
        const viewPager = view.binding.viewPager;
        const navigation = view.binding.navigation;
        const ids = [ui.R.id.navigation_dashboard, ui.R.id.navigation_log, ui.R.id.navigation_settings];
        viewPager.initAdapterFromChildren();
        // setOnItemSelectedListener replaces any previous listener. Local Activity resource
        // layouts are not guaranteed to have host wiring, so keep the explicit binding.
        navigation.setOnItemSelectedListener(item => {
            if (!this._isCurrentView(view)) return false;
            const position = ids.indexOf(item.getItemId());
            if (position < 0) return false;
            if (viewPager.getCurrentItem() !== position) viewPager.setCurrentItem(position);
            return true;
        });
        viewPager.addOnPageChangeListener(new androidx.viewpager.widget.ViewPager.OnPageChangeListener({
            onPageScrolled: (position, positionOffset, positionOffsetPixels) => {
            },
            onPageSelected: (position) => {
                if (!this._isCurrentView(view)) return;
                if (position === 1) this._setupConsoleLazy(view);
                if (position >= 0 && position < ids.length) navigation.setSelectedItemId(ids[position]);
            },
            onPageScrollStateChanged: (state) => {
            },
        }));
        // 重建/恢复时 ViewPager 可能已经停在控制台页，补一次检查。
        if (viewPager.getCurrentItem() === 1) {
            this._setupConsoleLazy(view);
        }
    }

    async _setupGrid(grid, view = this._setupView) {
        // task-231：defineClass 已在 main() 里提前启动，这里只等待；不在内容回调里编译。
        if (!gridAdapterClassPromise) {
            startGridClassCompilation();
        }
        // 两个 defineClass 已在 main() 启动；这里同时等待，不串行编译。
        const MyAdapter = (await Promise.all([gridAdapterClassPromise, gridHolderClassPromise]))[0];
        if (!this._isCurrentView(view)) {
            return;
        }
        // 设置为表格布局，列数为2
        const layoutManager = new androidx.recyclerview.widget.GridLayoutManager(this, 2);
        grid.setLayoutManager(layoutManager);
        // task-233：Adapter 类就绪后一次批量预 inflate 全部 grid_item（减少逐条 LayoutInflater/渲染回调）。
        // 失败时不阻断列表：预生成数组留空，MyAdapter 自动回退 LayoutInflater。
        let preInflatedViews = [];
        try {
            preInflatedViews = ui.inflateBatch(ui.R.layout.grid_item, this.cardList.length, grid);
        } catch (error) {
            console.error('M3 grid_item 批量预 inflate 失败，回退逐条 LayoutInflater', error);
        }
        if (this._isCurrentView(view)) {
            grid.setAdapter(new MyAdapter(this.cardList, preInflatedViews));
        }
    }

    _setupConsole(consoleView, toggleButton) {
        // 禁用控制台的嵌套滑动，去除控制台滑动时导致Toolbar自动变色效果
        consoleView.getContentView().setNestedScrollingEnabled(false);
        toggleButton.addOnButtonCheckedListener((btn, checkedId, isChecked) => {
            if (isChecked) {
                switch (checkedId) {
                    case ui.R.id.buttonLog:
                        consoleView.setLogLevel("V");
                        break;
                    case ui.R.id.buttonWarning:
                        consoleView.setLogLevel("W");
                        break;
                    case ui.R.id.buttonError:
                        consoleView.setLogLevel("E");
                        break;
                }
            }
        });
    }

    onCreateOptionsMenu(menu) {
        this.getMenuInflater().inflate(ui.R.menu.menu_main, menu);
        return true;
    }

    onOptionsItemSelected(item) {
        switch (item.getItemId()) {
            case ui.R.id.action_abouts: {
                this._showMaterial3Alert("Abouts", "Node.js M3 Demo\nAuto.js Pro V9 With Node.js");
                break;
            }
            default:
                return super.onOptionsItemSelected(item)
        }
        return true;
    }


    _showMaterial3Alert(title, message) {
        const MaterialAlertDialogBuilder = com.google.android.material.dialog.MaterialAlertDialogBuilder;
        new MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }
}
