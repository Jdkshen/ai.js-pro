"nodejs";
const assert = require('assert');
const path = require('path');
const image = require('image');
const {Color} = require('color');
const {Rect} = require('@autojs/opencv');
const target = Color.parse('#009688');
const multi = {firstColor: target, offsetColors: [{offsetX: 1, offsetY: 1, color: Color.parse('#ffffff')}]};
const file = path.join(__dirname, 'pro-color-region.png');
(async () => {
  const bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888);
  const stream = new java.io.FileOutputStream(file);
  try {
    bitmap.eraseColor(android.graphics.Color.BLACK);
    bitmap.setPixel(2, 3, target.toInt());
    bitmap.setPixel(3, 4, -1);
    assert.strictEqual(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream), true);
  } finally { stream.close(); bitmap.recycle(); }
  const frame = await image.readImage(file);
  try {
    const options = {region: new Rect(1, 1, 5, 5), threshold: 0};
    for (const point of [image.findColorSync(frame, target, options),
      await image.findColor(frame, target, options),
      image.findMultiColorsSync(frame, multi, options),
      await image.findMultiColors(frame, multi, options)]) {
      assert.deepStrictEqual({x: point.x, y: point.y}, {x: 2, y: 3});
    }
    assert.strictEqual(image.detectsColor(frame, target, 2, 3), true);
    assert.strictEqual(image.detectsMultiColors(frame, multi, 2, 3), true);
    console.log('PRO_COLOR_REGION_RESULT ' + JSON.stringify({ok: true, x: 2, y: 3, cases: 6}));
  } finally { frame.recycle(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
