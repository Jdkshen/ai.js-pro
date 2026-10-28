"nodejs";
// Device regression: stop while an unmodified, fixed-size native grid is laying out.
// No data mutations or missing notify calls are involved in this reproducer.
const ui = require('ui');
const {android, androidx} = $java.Packages;
let bound = 0;
let scrollTimer;
(async () => {
    const Holder = await $java.defineClass(class StopGridHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        constructor(view) { super(view); }
    });
    const Adapter = await $java.defineClass(class StopGridAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter {
        constructor() { super(); }
        getItemCount() { return 120; }
        onDetachedFromRecyclerView() {
            clearInterval(scrollTimer);
            console.log('STOP_GRID_DETACHED');
        }
        onCreateViewHolder(parent) {
            const text = new android.widget.TextView(parent.getContext());
            text.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, 160));
            return new Holder(text);
        }
        onBindViewHolder(holder, position) {
            holder.itemView.setText('Stop regression cell ' + position);
            if (++bound === 8) console.log('STOP_GRID_READY');
        }
    });
    class StopGrid extends ui.Activity {
        get layoutXml() { return '<androidx.recyclerview.widget.RecyclerView id="grid" w="*" h="*"/>'; }
        onContentViewSet(root) {
            const grid = root.binding.grid;
            grid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));
            grid.setAdapter(new Adapter());
            let target = 0;
            scrollTimer = setInterval(() => {
                target = (target + 13) % 120;
                grid.scrollToPosition(target);
                grid.requestLayout();
            }, 35);
        }
    }
    ui.setMainActivity(StopGrid);
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
