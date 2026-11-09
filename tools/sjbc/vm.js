const { OP, LEN } = require('./opcodes');

class Closure { constructor(entry, upvals) { this.entry = entry; this.upvals = upvals; } }

function run(code, pool, host, entries, entryIndex = 0, trace = false) {
  const REGN = 16;
  const stack = [{ R: new Array(REGN).fill(0), retPc: -1, retReg: 0, upvals: [] }];
  const handlers = [];              // {depth, target}
  const capStack = [];              // 捕获栈
  let pc = entries[entryIndex] | 0, steps = 0;
  const log = [], tr = [];
  const top = () => stack[stack.length - 1];

  while (pc < code.length) {
    if (++steps > 5e7) throw new Error('step limit');
    const F = top(), R = F.R;
    const op = code[pc], u1 = code[pc+1], u2 = code[pc+2], u3 = code[pc+3];
    const rd = (u1>>4)&15, ra = u1&15, rb = (u2>>4)&15, imm = (u2<<8)|u3;
    const ln = LEN[op] || 4;
    if (trace) tr.push(`pc=${pc} op=0x${op.toString(16)} depth=${stack.length}`);

    switch (op) {
      case OP.NOP: break;
      case OP.LOADK: R[rd] = pool[imm]; break;
      case OP.STR: R[rd] = pool[imm]; break;
      case OP.MOV: R[rd] = R[ra]; break;
      case OP.LOADF: { const b=((u2<<24)|(u3<<16)|(code[pc+4]<<8)|code[pc+5])>>>0;
                       R[rd] = new Float32Array(new Uint32Array([b]).buffer)[0]; break; }
      case OP.CLOSURE: { const fidx = u2, ncap = u3, caps = [];
                         for (let i=0;i<ncap;i++) caps.unshift(capStack.pop());
                         R[rd] = new Closure(fidx, caps); break; }
      case OP.CAPTURE: capStack.push(R[ra]); break;
      case OP.NEWCELL: R[rd] = [R[ra]]; break;
      case OP.GETCELL: R[rd] = (R[ra] || [])[0]; break;
      case OP.SETCELL: if (R[rd]) R[rd][0] = R[ra]; break;
      case OP.GETUP: R[rd] = F.upvals[imm]; break;
      case OP.SETUP: F.upvals[imm] = R[ra]; break;
      case OP.ADD: { const a=R[ra], b=R[rb];
                     R[rd] = (typeof a==='string'||typeof b==='string') ? String(a)+String(b) : a+b; break; }
      case OP.SUB: R[rd] = R[ra] - R[rb]; break;
      case OP.MUL: R[rd] = R[ra] * R[rb]; break;
      case OP.DIV: R[rd] = R[rb] === 0 ? 0 : R[ra] / R[rb]; break;
      case OP.MOD: R[rd] = R[rb] === 0 ? 0 : R[ra] % R[rb]; break;
      case OP.NEG: R[rd] = -R[ra]; break;
      case OP.EQ: R[rd] = R[ra] === R[rb] ? 1 : 0; break;
      case OP.NE: R[rd] = R[ra] !== R[rb] ? 1 : 0; break;
      case OP.LT: R[rd] = R[ra] <  R[rb] ? 1 : 0; break;
      case OP.LE: R[rd] = R[ra] <= R[rb] ? 1 : 0; break;
      case OP.GT: R[rd] = R[ra] >  R[rb] ? 1 : 0; break;
      case OP.GE: R[rd] = R[ra] >= R[rb] ? 1 : 0; break;
      case OP.JMP: pc = imm; continue;
      case OP.BT: if (R[ra] !== 0) { pc = imm; continue; } break;
      case OP.BF: if (R[ra] === 0) { pc = imm; continue; } break;
      case OP.NEWARR: R[rd] = new Array(imm).fill(0); break;
      case OP.ALEN: R[rd] = (R[ra] || []).length; break;
      case OP.AGET: R[rd] = (R[ra] || [])[R[rb]]; break;
      case OP.ASET: { const a=R[rd]; if (a) a[R[ra]] = R[rd+1]; break; }
      case OP.NEWOBJ: R[rd] = {}; break;
      case OP.OGET: R[rd] = (R[ra] || {})[R[rb]]; break;
      case OP.OSET: { const o=R[rd]; if (o) o[R[ra]] = R[rd+1]; break; }
      case OP.CALL: { const fidx=u2, argc=u3, base=rd, args=[];
                      for (let i=0;i<argc;i++) args.push(R[base+i]);
                      R[base] = host[fidx] ? host[fidx](...args) : undefined;
                      log.push({t:'host', fidx, args}); break; }
      case OP.CALLF: { const fidx=u2, argc=u3, base=rd;
                       const nr = new Array(REGN).fill(0);
                       for (let i=0;i<argc;i++) nr[i] = R[base+i];
                       stack.push({ R:nr, retPc: pc+ln, retReg: base, upvals: [] });
                       pc = entries[fidx]|0; continue; }
      case OP.CALLV: { const argc=u3, base=rd; const fn = R[base];
                       if (!(fn instanceof Closure)) throw new Error('CALLV on non-closure');
                       const nr = new Array(REGN).fill(0);
                       for (let i=0;i<argc;i++) nr[i] = R[base+1+i];
                       stack.push({ R:nr, retPc: pc+ln, retReg: base, upvals: fn.upvals });
                       pc = entries[fn.entry]|0; continue; }
      case OP.RETV: { const v = R[ra], F2 = stack.pop();
                      if (stack.length === 0) return { log, tr, steps };
                      top().R[F2.retReg] = v; pc = F2.retPc; continue; }
      case OP.TRY: handlers.push({ depth: stack.length-1, target: imm }); break;
      case OP.ENDTRY: { for (let i=handlers.length-1;i>=0;i--) if (handlers[i].depth === stack.length-1) { handlers.splice(i,1); break; } break; }
      case OP.THROW: {
        const val = R[ra];
        let caught = false;
        while (handlers.length) {
          const h = handlers.pop();
          if (h.depth <= stack.length-1) {
            while (stack.length-1 > h.depth) stack.pop();
            const Fr = top(); Fr.pendingExc = val;
            pc = h.target; caught = true; break;
          }
        }
        if (!caught) return { log, tr, steps, uncaught: val };
        continue;
      }
      case OP.GETEXC: { const Fr = top(); R[rd] = Fr.pendingExc; Fr.pendingExc = undefined; break; }
      case OP.PRINT: log.push({ t:'print', v: R[ra] }); break;
      case OP.RET: return { log, tr, steps };
      default: throw new Error('bad opcode 0x' + op.toString(16));
    }
    pc += ln;
  }
  return { log, tr, steps };
}
module.exports = { run, Closure };
