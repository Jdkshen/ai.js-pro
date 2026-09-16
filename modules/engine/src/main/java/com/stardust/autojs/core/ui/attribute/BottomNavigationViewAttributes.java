package com.stardust.autojs.core.ui.attribute;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.stardust.autojs.core.ui.inflater.ProjectResources;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.util.Colors;
import com.stardust.autojs.core.ui.inflater.util.Ids;

import java.io.File;
import java.util.List;

/**
 * {@code com.google.android.material.bottomnavigation.BottomNavigationView} 的属性。
 *
 * <p>重点是 {@code app:menu}：原生布局的底部导航栏靠它出项。真资源要用 aapt2 编好再
 * {@code inflateMenu(int)}，本引擎没有资源编译器，所以读工程的 {@code res/menu/*.xml} 自己建菜单项。
 * 项 id 走 {@link Ids}，与布局里 {@code @+id/xxx}、脚本里 {@code ui.R.id.xxx} 是同一张名字表，
 * 因此 {@code ui.navigation.setOnNavigationItemSelectedListener} 里的 {@code item.getItemId()}
 * 能和 {@code ui.R.id.navigation_home} 对上。
 */
public class BottomNavigationViewAttributes extends ViewAttributes {

    private static final String LOG_TAG = "BottomNavAttributes";

    public BottomNavigationViewAttributes(ResourceParser resourceParser, View view) {
        super(resourceParser, view);
    }

    @Override
    protected void onRegisterAttrs() {
        super.onRegisterAttrs();
        registerAttr("menu", this::setMenu);
        registerAttr("selectedItemId", value -> getView().setSelectedItemId(Ids.parse(value)));
        registerAttr("itemIconTint", value ->
                getView().setItemIconTintList(ColorStateList.valueOf(Colors.parse(getView(), value))));
        registerAttr("itemTextColor", value ->
                getView().setItemTextColor(ColorStateList.valueOf(Colors.parse(getView(), value))));
    }

    @Override
    public BottomNavigationView getView() {
        return (BottomNavigationView) super.getView();
    }

    private void setMenu(String value) {
        ProjectResources resources = getDrawables().getProjectResources();
        if (resources == null) {
            Log.w(LOG_TAG, "app:menu 需要工程模式（project.json 里声明 androidResources）：" + value);
            return;
        }
        String name = ProjectResources.nameOfReference(value);
        // 这里**不**走 framework 的 inflateMenu(真 id)：那样菜单项 id 由 MenuInflater 按编译值给，
        // 与脚本里的 ui.R.id.xxx 对不上 —— 而脚本典型写法是
        //   ui.navigation.setOnNavigationItemSelectedListener(item => ids.indexOf(item.itemId))
        // 所以菜单项 id 必须与 ui.R.id.* 同源（本项目自己建项时用的就是 resources.idOf）。
        File file = resources.fileOf(ProjectResources.TYPE_MENU, name);
        if (file == null) {
            Log.w(LOG_TAG, "找不到菜单资源：" + value);
            return;
        }
        List<ProjectResources.MenuEntry> entries = ProjectResources.parseMenu(file);
        Menu menu = getView().getMenu();
        menu.clear();
        for (int i = 0; i < entries.size(); i++) {
            ProjectResources.MenuEntry entry = entries.get(i);
            String idName = entry.getIdName();
            // id 走资源表（有编译包时就是真 id，与 ui.R.id.* 一致），否则是引擎自己的名字表。
            int itemId = idName == null || idName.isEmpty()
                    ? Menu.NONE : resources.idOf(ProjectResources.TYPE_ID, idName);
            MenuItem item = menu.add(Menu.NONE, itemId, i, entry.getTitle());
            applyIcon(item, entry.getIcon());
        }
    }

    /**
     * 菜单图标是普通的 drawable 引用（可能落在 app 资源，也可能在工程 {@code res/} 里）。
     *
     * <p>取不到就只留文字：我们自己的 APK 没有带全套 Material 图标（示例引用的
     * {@code ic_home_black_48dp} 既不工程里也不在 app 里），不该因此让整份布局 inflate 失败。
     */
    private void applyIcon(MenuItem item, String icon) {
        if (icon == null || icon.isEmpty()) {
            return;
        }
        try {
            Drawable drawable = getDrawables().parse(getView(), icon);
            item.setIcon(drawable);
        } catch (Exception e) {
            Log.w(LOG_TAG, "菜单项图标取不到，只显示文字：" + icon);
        }
    }
}
