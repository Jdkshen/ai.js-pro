// @engine quickjs
"use strict";
module.exports = {
    inputWidth: 192,
    inputHeight: 192,
    channels: 1,
    mean: 0.5,
    std: 0.5,
    classes: ["帅", "将", "仕", "士", "相", "象", "車", "车", "马", "馬", "炮", "砲", "兵", "卒"],
    scoreThreshold: 0.78,
    modelFile: "models/xiangqi_ocr_192.onnx"
};
