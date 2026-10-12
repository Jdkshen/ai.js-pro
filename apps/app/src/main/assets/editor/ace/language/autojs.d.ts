/** AI.js Pro's Android scripting environment; Node.js APIs are not assumed. */
declare function toast(message: any): void;
declare function toastLog(message: any): void;
declare function sleep(milliseconds: number): void;
declare function exit(): never;
declare function random(min: number, max: number): number;
declare function click(x: number, y: number): boolean;
declare function click(text: string, index?: number): boolean;
declare function longClick(x: number, y: number): boolean;
declare function press(x: number, y: number, duration: number): boolean;
declare function swipe(x1: number, y1: number, x2: number, y2: number, duration: number): boolean;
declare function gesture(duration: number, ...points: number[][]): boolean;
declare function gestures(...strokes: any[]): boolean;
declare function back(): boolean;
declare function home(): boolean;
declare function recents(): boolean;
declare function setScreenMetrics(width: number, height: number): void;
declare function requestScreenCapture(landscape?: boolean): boolean;
declare function captureScreen(): ImageWrapper;
declare function importClass(javaClass: any): void;
declare function importPackage(javaPackage: any): void;
declare function require(module: string): any;
declare function setTimeout(callback: (...args: any[]) => void, delay?: number, ...args: any[]): number;
declare function clearTimeout(id: number): void;
declare function setInterval(callback: (...args: any[]) => void, delay?: number, ...args: any[]): number;
declare function clearInterval(id: number): void;
declare const module: { exports: any };
declare const exports: any;
declare const java: any, android: any, Packages: any, context: any, activity: any, runtime: any;
declare const ui: any, floaty: any, events: any, engines: any, dialogs: any, app: any;
declare const colors: any, http: any, storages: any, sensors: any, media: any, shell: any;
declare const $shell: any, $settings: any, $plugins: any, $images: any, $ocr: any, $yolo: any;
declare const threads: { start(action: () => void): any; shutDownAll(): void; [name: string]: any };
declare const console: {
    log(...values: any[]): void; info(...values: any[]): void; warn(...values: any[]): void;
    error(...values: any[]): void; debug(...values: any[]): void; verbose(...values: any[]): void;
    show(): void; hide(): void; clear(): void; setTitle(title: string): void; [name: string]: any;
};
declare const files: {
    read(path: string, encoding?: string): string;
    write(path: string, text: string, encoding?: string): void;
    append(path: string, text: string, encoding?: string): void;
    exists(path: string): boolean; isFile(path: string): boolean; isDir(path: string): boolean;
    listDir(path: string, filter?: (name: string) => boolean): string[];
    join(...paths: string[]): string; path(relativePath: string): string; cwd(): string;
    create(path: string): boolean; createWithDirs(path: string): boolean; remove(path: string): boolean;
    copy(from: string, to: string): boolean; move(from: string, to: string): boolean;
    getName(path: string): string; getExtension(path: string): string; [name: string]: any;
};
interface ImageWrapper {
    getWidth(): number; getHeight(): number; getBitmap(): any; recycle(): void; [name: string]: any;
}
declare const images: {
    read(path: string): ImageWrapper; load(url: string): ImageWrapper;
    captureScreen(): ImageWrapper; requestScreenCapture(landscape?: boolean): boolean;
    pixel(image: ImageWrapper, x: number, y: number): number;
    save(image: ImageWrapper, path: string, format?: string, quality?: number): void;
    findImage(image: ImageWrapper, template: ImageWrapper, options?: any): { x: number; y: number } | null;
    findColor(image: ImageWrapper, color: string | number, options?: any): { x: number; y: number } | null;
    clip(image: ImageWrapper, x: number, y: number, width: number, height: number): ImageWrapper;
    resize(image: ImageWrapper, size: number[], interpolation?: string): ImageWrapper; [name: string]: any;
};
declare const device: {
    width: number; height: number; brand: string; model: string; sdkInt: number; release: string;
    getBattery(): number; isCharging(): boolean; vibrate(milliseconds: number): void;
    keepScreenOn(timeout?: number): void; cancelKeepingAwake(): void; [name: string]: any;
};
interface UiObject {
    click(): boolean; longClick(): boolean; text(): string; bounds(): any; parent(): UiObject;
    child(index: number): UiObject; children(): any; setText(text: string): boolean; [name: string]: any;
}
interface UiSelector {
    findOne(timeout?: number): UiObject; findOnce(index?: number): UiObject; find(): UiObject[];
    exists(): boolean; waitFor(): void; click(): boolean; [name: string]: any;
}
declare function text(value: string): UiSelector;
declare function textContains(value: string): UiSelector;
declare function textMatches(value: string | RegExp): UiSelector;
declare function id(value: string): UiSelector;
declare function desc(value: string): UiSelector;
declare function className(value: string): UiSelector;
declare function packageName(value: string): UiSelector;
declare function selector(): UiSelector;
declare const auto: { (): void; waitFor(): void; service: any };
declare function log(...values: any[]): void;
