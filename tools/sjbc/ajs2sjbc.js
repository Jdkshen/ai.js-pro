const { OP } = require('./opcodes');

// ================= 词法 =================
function tokenize(src) {
  const t = [];
  const re = /\s*(?:(\/\/[^\n]*)|("(?:[^"\\]|\\.)*")|([0-9]+(?:\.[0-9]+)?)|([A-Za-z_]\w*)|(===|!==|==|!=|<=|>=|&&|\|\||\+\+|--|\+=|-=|\*=|\/=|[+\-*/%(){};,\[\].<>=:!?]))/g;
  let m;
  while ((m = re.exec(src))) {
    if (m[1]) continue;
    if (m[2]) t.push({ k: 'str', v: JSON.parse(m[2]) });
    else if (m[3]) t.push({ k: 'num', v: parseFloat(m[3]) });
    else if (m[4]) t.push({ k: 'id', v: m[4] });
    else t.push({ k: 'op', v: m[5] });
  }
  return t;
}

// ================= 语法 =================
class P {
  constructor(t) { this.t = t; this.i = 0; }
  peek(k = 0) { return this.t[this.i + k]; }
  next() { return this.t[this.i++]; }
  at(v) { const x = this.peek(); return !!x && x.v === v && (x.k === 'op' || x.k === 'id'); }
  eat(v) { const x = this.next(); if (!x || x.v !== v) throw new Error('want ' + v + ' got ' + (x && x.v)); return x; }
  program() { const funcs = [], main = [];
    while (this.i < this.t.length) { const x = this.peek();
      if (x && x.k === 'id' && x.v === 'function' && this.peek(1) && this.peek(1).k === 'id') {
        this.next(); const n = this.next().v; this.eat('(');
        const ps = []; if (!this.at(')')) { ps.push(this.next().v); while (this.at(',')) { this.next(); ps.push(this.next().v); } }
        this.eat(')'); funcs.push({ n, ps, b: this.block() });
      } else main.push(this.stmt()); }
    return { funcs, main }; }
  block() { this.eat('{'); const b = []; while (!this.at('}')) b.push(this.stmt()); this.eat('}'); return b; }
  stmt() { const x = this.peek();
    if (x && x.k === 'id' && x.v === 'let') { this.next(); const n = this.next().v; this.eat('='); const e = this.expr(); this.eat(';'); return { t:'let', n, e }; }
    if (x && x.k === 'id' && x.v === 'while') { this.next(); this.eat('('); const c = this.expr(); this.eat(')'); return { t:'while', c, b:this.block() }; }
    if (x && x.k === 'id' && x.v === 'for') { this.next(); this.eat('(');
      const init = (this.at(';') ? null : (this.at('let') ? (this.next(), (()=>{ const n=this.next().v; this.eat('='); return {t:'let',n,e:this.expr()}; })())
                                                     : (()=>{ const e=this.expr(); return this.assignTail(e) || {t:'expr',e}; })()));
      this.eat(';');
      const cond = this.at(';') ? null : this.expr();
      this.eat(';');
      const step = this.at(')') ? null : (()=>{ const e=this.expr(); return this.assignTail(e) || {t:'expr',e}; })();
      this.eat(')'); return { t:'for', init, cond, step, b:this.block() }; }
    if (x && x.k === 'id' && x.v === 'break') { this.next(); this.eat(';'); return { t:'break' }; }
    if (x && x.k === 'id' && x.v === 'continue') { this.next(); this.eat(';'); return { t:'continue' }; }
    if (x && x.k === 'id' && x.v === 'if') { this.next(); this.eat('('); const c=this.expr(); this.eat(')'); const a=this.block();
      let o=null; if (this.at('else')) { this.next(); o = this.at('{') ? this.block() : [this.stmt()]; } return { t:'if', c, a, o }; }
    if (x && x.k === 'id' && x.v === 'return') { this.next(); const e=this.expr(); this.eat(';'); return { t:'return', e }; }
    if (x && x.k === 'id' && x.v === 'throw') { this.next(); const e=this.expr(); this.eat(';'); return { t:'throw', e }; }
    if (x && x.k === 'id' && x.v === 'try') { this.next(); const body=this.block();
      let cat=null, fin=null, ev=null;
      if (this.at('catch')) { this.next(); this.eat('('); ev=this.next().v; this.eat(')'); cat=this.block(); }
      if (this.at('finally')) { this.next(); fin=this.block(); }
      return { t:'try', body, cat, fin, ev }; }
    if (x && x.v === '{') return this.block();
    const e = this.expr();
    const asg = this.assignTail(e);
    this.eat(';'); return asg || { t:'expr', e };
  }
  assignTail(e) {
    for (const op of ['=', '+=', '-=', '*=', '/=']) {
      if (this.at(op)) { this.next(); const v = this.expr();
        if (op === '=') return { t:'set', e, v };
        return { t:'set', e, v: { t:'bin', o: op[0], l: e, r: v } }; } }
    return null; }
  expr() { const c = this.or(); if (this.at('?')) { this.next(); const a = this.expr(); this.eat(':'); const b = this.expr(); return { t: 'cond', c, a, b }; } return c; }
  or() { let l=this.and(); while (this.at('||')) { this.next(); l={t:'bin',o:'||',l,r:this.and()}; } return l; }
  and() { let l=this.cmp(); while (this.at('&&')) { this.next(); l={t:'bin',o:'&&',l,r:this.cmp()}; } return l; }
  cmp() { let l=this.add(); const x=this.peek(); if (x && x.k==='op' && ['==','!=','===','!==','<','<=','>','>='].includes(x.v)) { const o=this.next().v; return {t:'bin',o,l,r:this.add()}; } return l; }
  add() { let l=this.mul(); while (this.at('+')||this.at('-')) { const o=this.next().v; l={t:'bin',o,l,r:this.mul()}; } return l; }
  mul() { let l=this.un(); while (this.at('*')||this.at('/')||this.at('%')) { const o=this.next().v; l={t:'bin',o,l,r:this.un()}; } return l; }
  un() { if (this.at('-')) { this.next(); return {t:'neg',e:this.un()}; }
         if (this.at('!')) { this.next(); return {t:'not',e:this.un()}; }
         return this.postfix(); }
  postfix() { let e = this.prim();
    for (;;) { if (this.at('.')) { this.next(); const n=this.next().v; e={t:'mem',o:e,k:n}; }
              else if (this.at('[')) { this.next(); const idx=this.expr(); this.eat(']'); e={t:'idx',o:e,idx}; }
              else if (this.at('(')) { this.next(); const args=[]; if (!this.at(')')) { args.push(this.expr()); while (this.at(',')) { this.next(); args.push(this.expr()); } }
                     this.eat(')'); e = (e.t==='var') ? {t:'call',n:e.n,args} : {t:'callv',f:e,args}; }
              else break; }
    if (this.at('++')||this.at('--')) { const d=this.next().v==='++'?1:-1; return {t:'incdec',e,d}; }
    return e; }
  prim() { const x = this.next();
    if (x.k === 'num') return { t:'num', v:x.v };
    if (x.k === 'str') return { t:'str', v:x.v };
    if (x.k === 'id' && x.v === 'function') { // 函数表达式
      this.eat('('); const ps=[]; if (!this.at(')')) { ps.push(this.next().v); while (this.at(',')) { this.next(); ps.push(this.next().v); } }
      this.eat(')'); return { t:'fn', ps, b:this.block() }; }
    if (x.k === 'id') return { t:'var', n:x.v };
    if (x.v === '(') { const e=this.expr(); this.eat(')'); return e; }
    if (x.v === '[') { const el=[]; if (!this.at(']')) { el.push(this.expr()); while (this.at(',')) { this.next(); el.push(this.expr()); } } this.eat(']'); return { t:'arr', el }; }
    if (x.v === '{') { const kv=[]; if (!this.at('}')) { for(;;) { const k=this.next().v; this.eat(':'); kv.push([k,this.expr()]); if (this.at(',')) { this.next(); continue; } break; } } this.eat('}'); return { t:'obj', kv }; }
    throw new Error('expr? ' + (x && x.v)); }
}

const BIN = { '+':OP.ADD,'-':OP.SUB,'*':OP.MUL,'/':OP.DIV,'%':OP.MOD,
              '==':OP.EQ,'===':OP.EQ,'!=':OP.NE,'!==':OP.NE,'<':OP.LT,'<=':OP.LE,'>':OP.GT,'>=':OP.GE };

// ================= 编译 =================
function compile(src, hostTable) {
  const { funcs, main } = new P(tokenize(src)).program();
  const pool = [];
  const K = v => { const i = pool.findIndex(x => x === v); if (i >= 0) return i; pool.push(v); return pool.length - 1; };

  // ---- 作用域树 ----
  let fnSeq = 0;
  const root = { id: fnSeq++, params: [], body: main, parent: null, children: [], locals: new Map(), upvals: [], upByName: new Map(), nextSlot: 0 };
  const declaredTop = new Map();   // 顶层具名函数 → id
  const fnByNode = new Map();

  const MAXLOCAL = 64;   // M14：前 7 个局部进 r0..r6，其余溢出到 r7 的帧数组
  function addLocal(fn, name) {
    if (fn.nextSlot >= MAXLOCAL) throw new Error('局部变量过多（fn#' + fn.id + ' 已用 ' + fn.nextSlot + ' 个，上限 ' + MAXLOCAL + '）');
    fn.locals.set(name, { slot: fn.nextSlot, boxed: false, kind: 'local' });
    fn.nextSlot++;
    fn.peakSlot = Math.max(fn.peakSlot || 0, fn.nextSlot);
  }
  function collectLocals(fn, body) {
    // ★ 参数必须先占 r0..r(n-1)：调用约定就是"实参写进帧的 r0.."
    for (const p of fn.params) if (!fn.locals.has(p)) addLocal(fn, p);
    for (const s of walk(body)) {
      if (s.t === 'let') { if (!fn.locals.has(s.n)) addLocal(fn, s.n); }
      if (s.t === 'try' && s.cat && s.ev && !fn.locals.has(s.ev)) addLocal(fn, s.ev);
    }
  }
  function* walk(list) { for (const s of list) { yield s; if (s.b && Array.isArray(s.b)) yield* walk(s.b); if (s.a && Array.isArray(s.a)) yield* walk(s.a); if (s.o && Array.isArray(s.o)) yield* walk(s.o); if (s.body && Array.isArray(s.body)) yield* walk(s.body);
    if (s.init && typeof s.init === 'object' && s.init.t) yield s.init; if (s.cat && Array.isArray(s.cat)) yield* walk(s.cat); if (s.fin && Array.isArray(s.fin)) yield* walk(s.fin); } }

  function registerFn(params, body, parent) {
    const fn = { id: fnSeq++, params, body, parent, children: [], locals: new Map(), upvals: [], upByName: new Map(), nextSlot: 0 };
    parent.children.push(fn);
    collectLocals(fn, body);
    scanNested(fn, body);
    return fn;
  }
  function scanNested(fn, body) {
    for (const s of walk(body)) {
      // 具名函数声明在语句层已拆出；这里处理函数表达式
    }
    forEachNode(body, node => { if (node.t === 'fn') { const c = registerFn(node.ps, node.b, fn); fnByNode.set(node, c); } });
  }
  function forEachNode(list, fn2) {
    for (const s of list) { fn2(s); for (const key of ['b','a','o','body','cat','fin']) if (Array.isArray(s[key])) forEachNode(s[key], fn2);
      if (s.init && typeof s.init === 'object' && s.init.t) forEachNode([s.init], fn2);
      if (s.step && typeof s.step === 'object' && s.step.t) forEachNode([s.step], fn2);
      if (s.e && typeof s.e === 'object') walkExpr(s.e, fn2); if (s.v && typeof s.v === 'object') walkExpr(s.v, fn2); if (s.c && typeof s.c === 'object') walkExpr(s.c, fn2); }
  }
  function walkExpr(e, fn2) {
    if (!e || typeof e !== 'object') return; fn2(e);
    for (const k of ['l','r','e','o','f','idx']) if (e[k] && typeof e[k] === 'object') walkExpr(e[k], fn2);
    if (Array.isArray(e.el)) e.el.forEach(x => walkExpr(x, fn2));
    if (Array.isArray(e.args)) e.args.forEach(x => walkExpr(x, fn2));
    if (Array.isArray(e.kv)) e.kv.forEach(([, v]) => walkExpr(v, fn2));
  }

  // 顶层具名函数注册为子函数（id 1.. 顺序固定）
  const topFns = [];
  for (const f of funcs) { const fn = registerFn(f.ps, f.b, root); declaredTop.set(f.n, fn); topFns.push(fn); }
  collectLocals(root, main);

  // ---- 引用解析：把跨函数引用变成 upvalue ----
  function upIndex(fn, name) {
    if (fn.upByName.has(name)) return fn.upByName.get(name);
    if (!fn.parent) throw new Error('未定义变量 ' + name);
    const p = fn.parent;
    if (p.locals.has(name)) { p.locals.get(name).boxed = true; fn.upvals.push({ name, kind: 'local' }); }
    else { const pi = upIndex(p, name); fn.upvals.push({ name, kind: 'upval', from: pi }); }
    fn.upByName.set(name, fn.upvals.length - 1);
    return fn.upvals.length - 1;
  }
  function resolveRefs(fn, body) {
    forEachNode(body, node => {
      if (node.t === 'var') {
        if (!fn.locals.has(node.n) && !fn.upByName.has(node.n)) {
          if (fn.parent) { node.up = upIndex(fn, node.n); } else if (declaredTop.has(node.n)) { node.fnRef = declaredTop.get(node.n); }
        }
      }
      if (node.t === 'call') { /* 名字调用稍后判定 */ }
      if (node.t === 'set' && node.e && node.e.t === 'var' && !fn.locals.has(node.e.n) && fn.parent) { node.e.up = upIndex(fn, node.e.n); }
    });
    for (const c of fn.children) resolveRefs(c, c.body);
  }
  resolveRefs(root, main);

  // ================= 代码生成 =================
  const chunks = [];
  function genFn(fn) {
    const ops = [], labels = new Map(); let seq = 0;
    const newLabel = () => 'F' + fn.id + '_L' + (seq++);
    const place = n => labels.set(n, ops.length);
    let varTop = fn.params.length, tmpTop = 8;   // r0..r7 局部，r8..r13 临时，r14/r15 PAIR
    const FRAME = 7;                       // 前 7 个局部进 r0..r6，其余溢出到 r7 的帧数组
    const nLocal = fn.peakSlot || fn.nextSlot;   // ★ 峰值（块级回收会让 nextSlot 回落）
    const useFrame = nLocal > FRAME;
    const spilled = n => useFrame && n >= FRAME;
    const ctx = [];
    const hidden = new Map();      // 内部槽（异常对象等）
    const allocHidden = () => { if (varTop >= 8) throw new Error('局部变量过多(>8)'); const s = varTop++; return s; };
    const alloc = () => { if (tmpTop > 15) throw new Error('临时寄存器耗尽'); return tmpTop++; };
    const resetTemps = () => { tmpTop = 8; };
    const isTemp = r => r >= 8;
    const free = r => { if (r >= 8 && r === tmpTop - 1) tmpTop--; };
    const dest = r => { if (isTemp(r)) return r; const d = alloc(); R(OP.MOV, d, r); return d; };
    const R = (op, rd=0, ra=0, rb=0) => ops.push({ op, rd, ra, rb, imm: 0 });
    const I = (op, rd=0, imm=0) => ops.push({ op, rd, ra: 0, rb: 0, imm });
    const JP = (op, rs, label) => ops.push({ op, rd: 0, ra: rs, rb: 0, imm: 0, label });
    const slotOf = n => { const l = fn.locals.get(n); if (!l) throw new Error('未定义变量 ' + n + ' (fn' + fn.id + ')'); return l.slot; };
    const isBoxed = n => { const l = fn.locals.get(n); return !!(l && l.boxed); };

    // 读变量（含 cell 解引用 / upvalue）
    function readVar(node) {
      if (fn.locals.has(node.n)) {
        const s = slotOf(node.n);
        if (spilled(s)) {
          const ix = alloc(); I(OP.LOADK, ix, s - FRAME);
          const d = alloc(); R(OP.AGET, d, FRAME, ix); free(ix);
          if (isBoxed(node.n)) { const c = alloc(); R(OP.GETCELL, c, d); return c; }
          return d;
        }
        if (!isBoxed(node.n)) return s;
        const d = alloc(); R(OP.GETCELL, d, s); return d;
      }
      const idx = node.up !== undefined ? node.up : upIndex(fn, node.n);
      const c = alloc(); I(OP.GETUP, c, idx);
      const d = alloc(); R(OP.GETCELL, d, c); free(c); return d;
    }
    function writeVar(node, valueReg) {
      if (fn.locals.has(node.n)) {
        const s = slotOf(node.n);
        if (spilled(s)) {
          const ix = alloc(); I(OP.LOADK, ix, s - FRAME);
          if (isBoxed(node.n)) { const c = alloc(); R(OP.AGET, c, FRAME, ix); R(OP.SETCELL, c, valueReg); free(c); }
          else { const pw = allocWindow(2); R(OP.MOV, pw, FRAME); R(OP.MOV, pw + 1, valueReg); ops.push({ op: OP.ASET, rd: pw, ra: ix, rb: 0, imm: 0 }); shrinkTo(pw); }
          free(ix); return;
        }
        if (!isBoxed(node.n)) { R(OP.MOV, s, valueReg); return; }
        R(OP.SETCELL, s, valueReg); return;
      }
      const idx = node.up !== undefined ? node.up : upIndex(fn, node.n);
      const c = alloc(); I(OP.GETUP, c, idx);
      R(OP.SETCELL, c, valueReg); free(c);
    }

    function expr(e) {
      if (e.t === 'num') { const r = alloc(); I(OP.LOADK, r, K(e.v)); return r; }
      if (e.t === 'str') { const r = alloc(); I(OP.STR, r, K(e.v)); return r; }
      if (e.t === 'var') {
        if (e.fnRef !== undefined) { const r = alloc(); I(OP.CLOSURE, r, e.fnRef); ops[ops.length-1].imm = (e.fnRef << 8) | 0; return r; }
        return readVar(e);
      }
      if (e.t === 'fn') { const c = fnByNode.get(e); return makeClosure(c); }
      if (e.t === 'cond') {
        const d = alloc();
        const c = expr(e.c); const lel = newLabel(), lend = newLabel();
        JP(OP.BF, c, lel); free(c);
        const va = expr(e.a); R(OP.MOV, d, va); free(va);
        JP(OP.JMP, 0, lend); place(lel);
        const vb = expr(e.b); R(OP.MOV, d, vb); free(vb);
        place(lend);
        return d; }
      if (e.t === 'neg') { const r = dest(expr(e.e)); R(OP.NEG, r, r); return r; }
      if (e.t === 'not') { const r = dest(expr(e.e)); const z = alloc(); I(OP.LOADK, z, K(0)); R(OP.EQ, r, r, z); free(z); return r; }
      if (e.t === 'incdec') {
        if (e.e.t !== 'var') throw new Error('++/-- 只支持变量');
        const cur = dest(readVar(e.e)); const one = alloc(); I(OP.LOADK, one, K(1));
        R(e.d > 0 ? OP.ADD : OP.SUB, cur, cur, one); free(one);
        writeVar(e.e, cur); return cur;
      }
      if (e.t === 'bin') {
        const l = dest(expr(e.l)), r = expr(e.r);
        R(e.o === '&&' ? OP.MUL : (e.o === '||' ? OP.ADD : BIN[e.o]), l, l, r);
        free(r); return l;
      }
      if (e.t === 'arr') {
        const d = alloc(); I(OP.NEWARR, d, e.el.length);
        for (let i = 0; i < e.el.length; i++) {
          const v = expr(e.el[i]); const ix = alloc(); I(OP.LOADK, ix, K(i));
          R(OP.MOV, 14, d); R(OP.MOV, 15, v);
          ops.push({ op: OP.ASET, rd: 14, ra: ix, rb: 0, imm: 0 }); free(ix); free(v);
        }
        return d;
      }
      if (e.t === 'obj') {
        const d = alloc(); R(OP.NEWOBJ, d);
        for (const [k, vex] of e.kv) {
          const v = expr(vex); const kr = alloc(); I(OP.STR, kr, K(k));
          R(OP.MOV, 14, d); R(OP.MOV, 15, v);
          ops.push({ op: OP.OSET, rd: 14, ra: kr, rb: 0, imm: 0 }); free(kr); free(v);
        }
        return d;
      }
      if (e.t === 'mem') { const o = expr(e.o); const d = dest(o);
        if (e.k === 'length') { R(OP.ALEN, d, d); return d; }
        const kr = alloc(); I(OP.STR, kr, K(e.k)); R(OP.OGET, d, d, kr); free(kr); return d; }
      if (e.t === 'idx') { const o = dest(expr(e.o)), ix = expr(e.idx); R(OP.AGET, o, o, ix); free(ix); return o; }
      if (e.t === 'call') {
        const hidx = hostTable.indexOf(e.n);
        if (hidx >= 0) {
          const base = allocWindow(Math.max(1, e.args.length));
          e.args.forEach((a, i) => evalInto(base + i, a));
          R(OP.CALL, base, 0, 0); ops[ops.length - 1].imm = (hidx << 8) | e.args.length;
          shrinkTo(base + 1); return base;
        }
        if (declaredTop.has(e.n)) {
          const target = declaredTop.get(e.n);
          if (target && target.upvals && target.upvals.length > 0) {
            // ★ 该具名函数引用了外层局部变量 ⇒ 必须带闭包环境调用（CALLF 没有 upvalue）
            const fwin = allocWindow(1 + Math.max(1, e.args.length));
            const cl = makeClosure(target);
            R(OP.MOV, fwin, cl); free(cl);
            e.args.forEach((a, i) => evalInto(fwin + 1 + i, a));
            R(OP.CALLV, fwin, 0, 0); ops[ops.length - 1].imm = e.args.length;
            shrinkTo(fwin + 1); return fwin;
          }
          const base = allocWindow(Math.max(1, e.args.length));
          e.args.forEach((a, i) => evalInto(base + i, a));
          R(OP.CALLF, base, 0, 0); ops[ops.length - 1].imm = (target.id << 8) | e.args.length;
          shrinkTo(base + 1); return base;
        }
        // 变量里放的是闭包 ⇒ 走 CALLV
        {
          const asVar = { t: 'var', n: e.n };
          if (fn.locals.has(e.n)) { /* ok */ }
          else { if (fn.parent) asVar.up = upIndex(fn, e.n); else throw new Error('未定义函数 ' + e.n); }
          const fwin = allocWindow(1 + Math.max(1, e.args.length));
          evalInto(fwin, asVar);
          e.args.forEach((a, i) => evalInto(fwin + 1 + i, a));
          R(OP.CALLV, fwin, 0, 0); ops[ops.length - 1].imm = e.args.length;
          shrinkTo(fwin + 1); return fwin;
        }
      }
      if (e.t === 'callv') {
        const fwin = allocWindow(1 + Math.max(1, e.args.length));
        evalInto(fwin, e.f);
        e.args.forEach((a, i) => evalInto(fwin + 1 + i, a));
        R(OP.CALLV, fwin, 0, 0); ops[ops.length - 1].imm = e.args.length;
        shrinkTo(fwin + 1); return fwin;
      }
      throw new Error('expr? ' + e.t);
    }
    // 把表达式算进指定槽：算完把临时区收回（值已落槽）
    function evalInto(dst, e) { const save = tmpTop; const r = expr(e); if (r !== dst) R(OP.MOV, dst, r); tmpTop = save; }
    const allocWindow = n => { const b = tmpTop; if (tmpTop + n - 1 > 15) throw new Error('调用窗口寄存器不足'); tmpTop += n; return b; };
    const shrinkTo = b => { tmpTop = b; };

    function makeClosure(child) {
      const r = alloc();
      for (const uv of child.upvals) {
        if (uv.kind === 'local') {
          const s = slotOf(uv.name);
          if (!isBoxed(uv.name)) throw new Error('捕获的变量未被装箱: ' + uv.name);
          ops.push({ op: OP.CAPTURE, rd: 0, ra: s, rb: 0, imm: 0 });
        } else {
          const c = alloc(); I(OP.GETUP, c, uv.from);
          ops.push({ op: OP.CAPTURE, rd: 0, ra: c, rb: 0, imm: 0 });
          free(c);
        }
      }
      I(OP.CLOSURE, r, 0); ops[ops.length - 1].imm = (child.id << 8) | child.upvals.length;
      return r;
    }

    const stmts = list => list.forEach(stmt);
    function stmt(s) { stmtInner(s); resetTemps(); }
    function stmtInner(s) {
      if (s.t === 'let') { const r = expr(s.e); const l = fn.locals.get(s.n);
        if (l.boxed) { const c = alloc(); R(OP.NEWCELL, c, r); R(OP.MOV, l.slot, c); free(c); }
        else writeVar({ n: s.n }, r);
        free(r); return; }
      if (s.t === 'expr') { const r = expr(s.e); free(r); return; }
      if (s.t === 'return') { const r = expr(s.e); ops.push({ op: OP.RETV, rd: 0, ra: r, rb: 0, imm: 0 }); free(r); return; }
      if (s.t === 'throw') { const r = expr(s.e); ops.push({ op: OP.THROW, rd: 0, ra: r, rb: 0, imm: 0 }); free(r); return; }
      if (s.t === 'set') {
        if (s.e.t === 'var') { const r = expr(s.v); writeVar(s.e, r); free(r); return; }
        if (s.e.t === 'idx') { const o = expr(s.e.o), ix = expr(s.e.idx), v = expr(s.v);
          R(OP.MOV, 14, o); R(OP.MOV, 15, v);
          ops.push({ op: OP.ASET, rd: 14, ra: ix, rb: 0, imm: 0 }); free(o); free(ix); free(v); return; }
        if (s.e.t === 'mem') { const o = expr(s.e.o), kr = alloc(); I(OP.STR, kr, K(s.e.k)); const v = expr(s.v);
          R(OP.MOV, 14, o); R(OP.MOV, 15, v);
          ops.push({ op: OP.OSET, rd: 14, ra: kr, rb: 0, imm: 0 }); free(o); free(kr); free(v); return; }
        throw new Error('不支持的赋值目标');
      }
      if (s.t === 'while') { const top = newLabel(); place(top); const c = expr(s.c); const end = newLabel(); JP(OP.BF, c, end); free(c);
        ctx.push({ br: end, cont: top }); stmts(s.b); ctx.pop(); JP(OP.JMP, 0, top); place(end); return; }
      if (s.t === 'for') {
        if (s.init) stmtInner(s.init);
        const top = newLabel(); place(top);
        const end = newLabel(), stepL = newLabel();
        if (s.cond) { const c = expr(s.cond); JP(OP.BF, c, end); free(c); }
        ctx.push({ br: end, cont: stepL }); stmts(s.b); ctx.pop();
        place(stepL); if (s.step) stmtInner(s.step);
        JP(OP.JMP, 0, top); place(end); return; }
      if (s.t === 'break') { if (!ctx.length) throw new Error('break 不在循环里'); JP(OP.JMP, 0, ctx[ctx.length-1].br); return; }
      if (s.t === 'continue') { if (!ctx.length) throw new Error('continue 不在循环里'); JP(OP.JMP, 0, ctx[ctx.length-1].cont); return; }
      if (s.t === 'if') { const c = expr(s.c), el = newLabel(), end = newLabel(); JP(OP.BF, c, el); free(c); stmts(s.a);
        if (s.o) { JP(OP.JMP, 0, end); place(el); stmts(s.o); place(end); } else place(el); return; }
      if (s.t === 'try') {
        if (s.cat && s.ev && !fn.locals.has(s.ev)) fn.locals.set(s.ev, { slot: allocHidden(), boxed: false, kind: 'local' });
        const lc = newLabel(), lend = newLabel();
        JP(OP.TRY, 0, lc);
        stmts(s.body);
        ops.push({ op: OP.ENDTRY, rd: 0, ra: 0, rb: 0, imm: 0 });
        if (s.fin) stmts(s.fin);
        JP(OP.JMP, 0, lend);
        place(lc);
        if (s.cat) {
          const excSlot = hidden.get('__exc') ?? (hidden.set('__exc', allocHidden()), hidden.get('__exc'));
          const t = alloc(); ops.push({ op: OP.GETEXC, rd: t, ra: 0, rb: 0, imm: 0 }); 
          const l = fn.locals.get(s.ev);
          if (!l) { fn.locals.set(s.ev, { slot: allocHidden(), boxed: false, kind: 'local' }); }
          const lv = fn.locals.get(s.ev);
          if (lv.boxed) { const cc = alloc(); R(OP.NEWCELL, cc, t); R(OP.MOV, lv.slot, cc); free(cc); } else R(OP.MOV, lv.slot, t);
          free(t);
          stmts(s.cat);
        } else {
          const t = alloc(); ops.push({ op: OP.GETEXC, rd: t, ra: 0, rb: 0, imm: 0 });
          if (s.fin) stmts(s.fin);
          ops.push({ op: OP.THROW, rd: 0, ra: t, rb: 0, imm: 0 }); free(t);
        }
        if (s.cat && s.fin) stmts(s.fin);
        place(lend); return;
      }
      if (Array.isArray(s)) { stmts(s); return; }
      throw new Error('stmt? ' + s.t);
    }
    if (useFrame) I(OP.NEWARR, FRAME, nLocal - FRAME);   // 帧数组（溢出局部都住这里）
    resetTemps();
    for (const _p of fn.params) {   // 被闭包捕获的参数：装箱成单元
      const _l = fn.locals.get(_p);
      if (_l && _l.boxed) { const _t = alloc(); R(OP.NEWCELL, _t, _l.slot); R(OP.MOV, _l.slot, _t); free(_t); }
    }
    resetTemps();
    stmts(fn.body);
    // ★ M14：函数体末尾补隐式返回（否则 pc 越过末尾 ⇒ VM 会把整个程序结束掉，而不是返回调用者）
    if (fn.id === 0) ops.push({ op: OP.RET, rd: 0, ra: 0, rb: 0, imm: 0 });
    else { R(OP.RETV, 0, 0); }   // 返回 0（无 return 的函数）
    return { fn, ops, labels };
  }

  chunks.push(genFn(root));
  const ordered = [];
  (function walkFns(f) { for (const c of f.children) { ordered.push(c); walkFns(c); } })(root);
  // 需要按 fn.id 顺序排列 entry 表
  const allFns = [root, ...ordered].sort((a, b) => a.id - b.id);
  for (const f of allFns) if (f !== root) chunks.push(genFn(f));

  // ---- 拼接 + 标签回填 ----
  const byId = new Map(chunks.map(c => [c.fn.id, c]));
  const codeBase = new Map(); let base = 0;
  for (const f of allFns) { codeBase.set(f.id, base); base += byId.get(f.id).ops.length; }
  const allOps = [];
  for (const f of allFns) for (const o of byId.get(f.id).ops) allOps.push(o);
  const pcOf = []; let off = 0;
  for (let i = 0; i < allOps.length; i++) { pcOf[i] = off; off += (allOps[i].op === OP.LOADF ? 6 : 4); }   // ★ 按真实指令长度累加（LOADF=6）
  const totalBytes = off;
  const globalLabel = new Map();
  for (const f of allFns) for (const [k, local] of byId.get(f.id).labels) globalLabel.set(k, codeBase.get(f.id) + local);
  for (const o of allOps) if (o.label) { const t = globalLabel.get(o.label); if (t === undefined) throw new Error('未知标签 ' + o.label); o.imm = pcOf[t] !== undefined ? pcOf[t] : totalBytes;
      if (o.imm < 0 || o.imm > totalBytes) throw new Error('跳转目标越界: ' + o.imm + ' > ' + totalBytes + ' (label ' + o.label + ')'); }
  const entries = new Array(allFns.length).fill(0);
  for (const f of allFns) entries[f.id] = pcOf[codeBase.get(f.id)];
  const bytes = [];
  for (const o of allOps) bytes.push(o.op, ((o.rd & 15) << 4) | (o.ra & 15), ((o.rb & 15) << 4) | ((o.imm >> 8) & 15), o.imm & 0xFF);
  return { bytes, pool, entries, chunks: allFns.length, ops: allOps.length };
}
module.exports = { compile, tokenize, P };
