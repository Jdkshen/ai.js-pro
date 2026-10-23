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
        // task-231：防止 onContentViewSet 重复触发时重复建列表/控制台。
        this._deferredSetupStarted = false;
    }

    onCreate(savedInstanceState) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this.getWindow(), false);
        // 应用主题
        this.getTheme().applyStyle(ui.R.style.MainTheme, true);
        super.onCreate(savedInstanceState);
    }

    onContentViewSet(view) {
        // task-234：首帧回调只保留 fab 等极轻量设置；ViewPager/Drawer/Grid/Console 全部
        // 移到 _setupDeferred，避免 onContentViewSet 同步做 100ms+ 的原生 setup。
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

    /** 延迟初始化 ViewPager/Drawer/列表/控制台；onContentViewSet 多次触发时单 flag 只初始化一次。 */
    _scheduleDeferredSetup(view) {
        if (this._deferredSetupStarted) {
            return;
        }
        this._deferredSetupStarted = true;
        setTimeout(() => {
            this._setupDeferred(view).catch(console.error);
        }, 0);
    }

    async _setupDeferred(view) {
        // task-234：顺序固定 Pager → Drawer → Grid → Console；单项失败记录后继续，保证底部
        // 页签/导航/列表/控制台最终都尽量完成。
        try {
            this._setupViewPager(view.binding.viewPager, view.binding.navigation);
        } catch (error) {
            console.error('M3 ViewPager 初始化失败', error);
        }
        try {
            this._setupDrawer(view.binding.drawer, view.binding.toolbar, view.binding.drawerNavigation);
        } catch (error) {
            console.error('M3 Drawer 初始化失败', error);
        }
        try {
            await this._setupGrid(view.binding.grid);
        } catch (error) {
            console.error('M3 列表初始化失败', error);
        }
        try {
            this._setupConsole(view.binding.console, view.binding.toggleButton);
        } catch (error) {
            console.error('M3 控制台初始化失败', error);
        }
    }

    _setupDrawer(drawer, toolbar, navigation) {
        this.setSupportActionBar(toolbar);
        // 配置ToolBar左上角点击时打开侧拉菜单
        const toggle = new androidx.appcompat.app.ActionBarDrawerToggle(this, drawer, toolbar, 0, 0);
        toggle.syncState();
        drawer.addDrawerListener(toggle);
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

    _setupViewPager(viewPager, navigation) {
        const bottomNavigationIds = [ui.R.id.navigation_dashboard, ui.R.id.navigation_log, ui.R.id.navigation_settings];
        viewPager.initAdapterFromChildren();
        // ViewPager和底部导航栏同步
        navigation.setOnItemSelectedListener((item) => {
            viewPager.setCurrentItem(bottomNavigationIds.indexOf(item.getItemId()));
            return true;
        })
        viewPager.addOnPageChangeListener(new androidx.viewpager.widget.ViewPager.OnPageChangeListener({
            onPageScrolled: (position, positionOffset, positionOffsetPixels) => {
            },
            onPageSelected: (position) => {
                navigation.setSelectedItemId(bottomNavigationIds[position])
            },
            onPageScrollStateChanged: (state) => {
            },
        }));
    }

    async _setupGrid(grid) {
        // task-231：defineClass 已在 main() 里提前启动，这里只等待；不在内容回调里编译。
        if (!gridAdapterClassPromise) {
            startGridClassCompilation();
        }
        // 两个 defineClass 已在 main() 启动；这里同时等待，不串行编译。
        const MyAdapter = (await Promise.all([gridAdapterClassPromise, gridHolderClassPromise]))[0];
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
        grid.setAdapter(new MyAdapter(this.cardList, preInflatedViews));
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