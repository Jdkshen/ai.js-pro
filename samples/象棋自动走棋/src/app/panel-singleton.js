// @engine quickjs
"use strict";

// QuickJS engines.all() exposes only the current engine on the phone. Keep a
// short, renewed lease on shared storage so every launch path sees the owner.
const LEASE_MS = 5000;

function pathFor(files, name) {
    return files.join(files.getSdcardPath(), ".aijspro-floaty/" + name);
}

function ownId(engines) {
    try {
        const self = engines.myEngine();
        return Number(typeof self.getId === "function" ? self.getId() : self.id);
    } catch (error) { return NaN; }
}

function read(files, path) {
    try { return JSON.parse(String(files.read(path) || "")); }
    catch (error) { return null; }
}

function ownerPath(files) { return pathFor(files, "panel-owner.json"); }
function ticketPath(files) { return pathFor(files, "panel-handoff.json"); }

function admit(engines, files, selfName, now) {
    const at = now == null ? Date.now() : now;
    const id = ownId(engines);
    if (!isFinite(id)) return { ok: false, reason: "读不到面板脚本编号" };
    const owner = read(files, ownerPath(files));
    const live = owner && owner.id !== id && owner.until >= at && owner.until <= at + LEASE_MS;
    const ticket = read(files, ticketPath(files));
    const handoff = !!(live && ticket && ticket.fromId === owner.id &&
        ticket.until >= at && ticket.until <= at + LEASE_MS);
    if (live && !handoff) {
        return { ok: false, reason: "已有面板脚本在运行（引擎 " + owner.id + "），本次不重复开窗" };
    }
    const claim = { id: id, until: at + LEASE_MS, nonce: String(id) + ":" + at + ":" + Math.random() };
    try {
        files.write(ownerPath(files), JSON.stringify(claim));
        const written = read(files, ownerPath(files));
        if (!written || written.nonce !== claim.nonce) {
            return { ok: false, reason: "面板启动竞争，已有另一实例取得窗口" };
        }
        if (handoff) endHandoff(files);
        return { ok: true, id: id, handoff: handoff };
    } catch (error) { return { ok: false, reason: "面板占用记录不可写：" + error }; }
}

function touch(engines, files, now) {
    const id = ownId(engines);
    const owner = read(files, ownerPath(files));
    if (!owner || owner.id !== id) return false;
    const at = now == null ? Date.now() : now;
    try {
        files.write(ownerPath(files), JSON.stringify({ id: id, until: at + LEASE_MS, nonce: owner.nonce }));
        return true;
    } catch (error) { return false; }
}

function release(engines, files) {
    const owner = read(files, ownerPath(files));
    if (owner && owner.id === ownId(engines)) {
        try { files.write(ownerPath(files), ""); } catch (error) { /* expiry is the fallback */ }
    }
}

function beginHandoff(engines, files, selfName, now) {
    const id = ownId(engines);
    const owner = read(files, ownerPath(files));
    if (!owner || owner.id !== id) return false;
    const at = now == null ? Date.now() : now;
    try {
        files.write(ticketPath(files), JSON.stringify({ fromId: id, until: at + LEASE_MS }));
        return true;
    } catch (error) { return false; }
}

function endHandoff(files) {
    try { files.write(ticketPath(files), ""); } catch (error) { /* expiry is the fallback */ }
}

module.exports = { admit: admit, touch: touch, release: release,
    beginHandoff: beginHandoff, endHandoff: endHandoff };
