"ui";
// QuickJS regression: previously each getter on main waited for itself for 2s.
ui.layout('<vertical><text id="probe" text="before"/></vertical>');
setTimeout(function () {
    try {
        var elapsed = ui.run(function () {
            var view = ui.findView('probe'), start = Date.now();
            view.attr('textColor', '#112233');
            if (view.attr('color') !== '#FF112233') throw Error('QUICKJS_COLOR_ROUNDTRIP');
            view.attr('text', 'after');
            if (view.attr('text') !== 'after') throw Error('QUICKJS_TEXT_ROUNDTRIP');
            view.attr('textColorHint', '#334455');
            if (view.attr('textColorHint') !== '#FF334455') throw Error('QUICKJS_HINT_ROUNDTRIP');
            view.attr('color', '@android:color/holo_blue_light');
            var color = view.attr('textColor');
            if (!/^#[A-F0-9]{8}$/.test(color)) throw Error('QUICKJS_RESOURCE_COLOR');
            return Date.now() - start;
        });
        if (elapsed >= 1000) throw Error('QUICKJS_MAIN_GETTER_BLOCKED ' + elapsed);
        console.log('QUICKJS_UI_MAIN_ATTRIBUTES_OK ' + elapsed);
    } finally { ui.close(); }
}, 120);
