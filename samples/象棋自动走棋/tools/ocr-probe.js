// @engine quickjs
console.log("OCR_PROBE_START");
console.log("$ocr=" + (typeof $ocr));
if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") throw new Error("$ocr unavailable");
const session = $ocr.create({ backend: "ppocr" });
console.log("OCR_BACKEND_READY");
try { if (session.release) session.release(); } catch (e) {}
console.log("OCR_PROBE_OK");
